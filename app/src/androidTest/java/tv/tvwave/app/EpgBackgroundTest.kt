package tv.tvwave.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Only the new background-import lifecycle, using a gated local XMLTV server. */
@RunWith(AndroidJUnit4::class)
class EpgBackgroundTest {
    private val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(condition:()->Boolean){
        val end=SystemClock.uptimeMillis()+20000
        while(!condition()&&SystemClock.uptimeMillis()<end)SystemClock.sleep(100)
        assertTrue("Background import did not reach the expected state",condition())
    }
    private fun cancel()=runBlocking{EpgUpdateService.cancelAndWait(ctx)}
    private fun guard(){
        check(ctx.packageName=="tv.tvwave.app.debug")
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        cancel()
    }
    private fun xml():ByteArray {
        val f=SimpleDateFormat("yyyyMMddHHmmss Z",Locale.US);val now=System.currentTimeMillis()
        return "<tv><channel id=\"test1\"><display-name>Test</display-name></channel><programme channel=\"test1\" start=\"${f.format(Date(now-3600000))}\" stop=\"${f.format(Date(now+3600000))}\"><title>Background programme</title></programme></tv>".toByteArray()
    }
    private fun state(sources:List<EpgSource>):AppState {
        val id=UUID.randomUUID().toString();val raw="#EXTM3U\n#EXTINF:-1 tvg-id=\"test1\",Test\nhttp://example.invalid/live\n"
        return AppState(uiLanguage="ru",playlists=listOf(Playlist(id,"Fixture","",raw,M3u.parse(raw,id))),epgSources=sources,epgAutoUpdate=false)
    }
    private data class Reply(val code:Int,val headers:String,val bytes:ByteArray)
    private class Server(body:ByteArray, private val reply:(Map<String,String>)->Reply={ Reply(200,"",body) }):AutoCloseable {
        val socket=ServerSocket(0);val gate=CountDownLatch(1);val requests=AtomicInteger()
        val url get()="http://127.0.0.1:${socket.localPort}/epg.xml"
        init{Thread{
            while(!socket.isClosed)try{val client=socket.accept();Thread{
                client.use { c->runCatching{
                    val reader=c.getInputStream().bufferedReader()
                    reader.readLine()
                    val headers=mutableMapOf<String,String>()
                    while(true){
                        val line=reader.readLine()
                        if(line.isNullOrEmpty())break
                        headers[line.substringBefore(':').lowercase(Locale.ROOT)]=line.substringAfter(':').trim()
                    }
                    val response=reply(headers)
                    requests.incrementAndGet()
                    val out=c.getOutputStream();out.write("HTTP/1.1 ${response.code} Reply\r\n${response.headers}Content-Length: ${response.bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());out.flush()
                    gate.await(25,TimeUnit.SECONDS);out.write(response.bytes);out.flush()
                }}
            }.apply{isDaemon=true;start()}}catch(_:Exception){}
        }.apply{isDaemon=true;start()}}
        override fun close(){gate.countDown();socket.close()}
    }
    @Test fun validatorsTrackOnlySuccessfulImportsForTheCurrentSelection() {
        guard()
        val original=xml()
        val date="Sat, 21 Nov 2026 08:00:00 GMT"
        val requests=java.util.concurrent.CopyOnWriteArrayList<Map<String,String>>()
        val broken=java.util.concurrent.atomic.AtomicBoolean(false)
        Server(original) { headers ->
            requests.add(headers)
            when {
                broken.get() -> Reply(200,"ETag: \"broken\"\r\n","<tv>".toByteArray())
                headers["if-modified-since"]==date -> Reply(304,"",byteArrayOf())
                else -> Reply(200,"Last-Modified: $date\r\n",original)
            }
        }.use { server ->
            server.gate.countDown()
            val source=legacyEpgSource(server.url)
            val initial=state(listOf(source))
            Repository(ctx).use { it.save(initial,resetCache=true) }
            fun refresh(){
                EpgUpdateService.enqueue(ctx,listOf(source.id))
                waitFor { EpgUpdateService.pending(ctx).isEmpty() && !EpgUpdateService.state.value.running }
            }
            fun updated()=Repository(ctx).use { EpgCache.lastUpdated(it.readableDatabase,source) }
            refresh()
            assertTrue(EpgUpdateService.state.value.failures.isEmpty())
            val first=updated()
            assertTrue(first>0L)
            refresh()
            assertEquals(date,requests.last()["if-modified-since"])
            assertTrue(updated()>first)
            val good=updated()
            broken.set(true)
            refresh()
            assertEquals(good,updated())
            assertTrue(source.id in EpgUpdateService.state.value.failures)
            broken.set(false)
            refresh()
            assertEquals(date,requests.last()["if-modified-since"])
            assertNull(requests.last()["if-none-match"])
            val playlist=initial.playlists.single()
            val more=playlist.raw+"#EXTINF:-1,Additional channel\nhttp://example.invalid/extra\n"
            val changed=initial.copy(playlists=listOf(playlist.copy(raw=more,channels=M3u.parse(more,playlist.id))))
            Repository(ctx).use { it.save(changed) }
            refresh()
            assertNull(requests.last()["if-modified-since"])
            assertTrue(EpgUpdateService.state.value.failures.isEmpty())
            Repository(ctx).use { it.save(changed,resetCache=true) }
            refresh()
            assertNull(requests.last()["if-modified-since"])
            assertTrue(updated()>0L)
        }
    }
    @Test fun brokenFirstSourceDoesNotBlockSecond() {
        guard()
        Server("<tv>".toByteArray()).use { first ->
            Server(xml()).use { second ->
                val sources=listOf(legacyEpgSource(first.url),legacyEpgSource(second.url))
                Repository(ctx).use { it.save(state(sources),resetCache=true) }
                second.gate.countDown()
                EpgUpdateService.enqueue(ctx,sources.map{it.id})
                waitFor { first.requests.get()==1 }
                first.gate.countDown()
                waitFor { EpgUpdateService.pending(ctx).isEmpty() && !EpgUpdateService.state.value.running }
                Repository(ctx).use {
                    assertEquals(0L,EpgCache.lastUpdated(it.readableDatabase,sources[0]))
                    assertTrue(EpgCache.lastUpdated(it.readableDatabase,sources[1])>0)
                }
                assertEquals(setOf(sources[0].id),EpgUpdateService.state.value.failures.keys)
            }
        }
    }
    @Test fun twoSourcesFinishAfterActivityDestroyed(){
        guard();Server(xml()).use{server->
            val sources=listOf(EpgSource(UUID.nameUUIDFromBytes("background-1".toByteArray()).toString(),"One",server.url),EpgSource(UUID.nameUUIDFromBytes("background-2".toByteArray()).toString(),"Two",server.url+"?second"))
            Repository(ctx).use{it.save(state(sources),resetCache=true)}
            val scenario=ActivityScenario.launch(MainActivity::class.java)
            try{
                scenario.onActivity{EpgUpdateService.enqueue(it,sources.map{source->source.id})}
                waitFor{server.requests.get()>=1&&EpgUpdateService.state.value.running}
                scenario.close() // onStop AND onDestroy, not just a hidden Compose screen.
                assertTrue(EpgUpdateService.state.value.running)
                server.gate.countDown()
                waitFor{!EpgUpdateService.state.value.running&&EpgUpdateService.pending(ctx).isEmpty()}
                Repository(ctx).use{repo->sources.forEach{assertTrue(EpgCache.lastUpdated(repo.readableDatabase,it)>0)}}
                assertEquals(2,server.requests.get())
                assertTrue(EpgUpdateService.state.value.failures.isEmpty())
            }finally{scenario.close();cancel()}
        }
    }
    @Test fun preservedCompleteDownloadResumesWithoutDownloadingAgain(){
        guard();Server(xml()).use{server->
            val source=EpgSource(UUID.nameUUIDFromBytes("checkpoint".toByteArray()).toString(),"Checkpoint",server.url)
            Repository(ctx).use{it.save(state(listOf(source)),resetCache=true)}
            val key=MessageDigest.getInstance("SHA-256").digest((source.id+"\n"+source.url).toByteArray()).joinToString(""){"%02x".format(it)}
            val dir=File(ctx.filesDir,"epg-work").apply{mkdirs()};File(dir,"$key.complete").writeBytes(xml())
            val scenario=ActivityScenario.launch(MainActivity::class.java)
            try{
                scenario.onActivity{EpgUpdateService.enqueue(it,listOf(source.id))}
                waitFor{Repository(ctx).use{EpgCache.lastUpdated(it.readableDatabase,source)>0}}
                waitFor{!EpgUpdateService.state.value.running}
                assertEquals(0,server.requests.get());assertFalse(File(dir,"$key.complete").exists())
            }finally{scenario.close();cancel()}
        }
    }
    @Test fun cancelDuringDownloadNeverInstallsIt(){
        guard();Server(xml()).use{server->
            val source=EpgSource(UUID.nameUUIDFromBytes("cancel".toByteArray()).toString(),"Cancel",server.url)
            Repository(ctx).use{it.save(state(listOf(source)),resetCache=true)}
            val scenario=ActivityScenario.launch(MainActivity::class.java)
            try{
                scenario.onActivity{EpgUpdateService.enqueue(it,listOf(source.id))}
                waitFor{server.requests.get()==1}
                cancel();server.gate.countDown()
                assertFalse(EpgUpdateService.state.value.running);assertTrue(EpgUpdateService.pending(ctx).isEmpty())
                Repository(ctx).use{assertEquals(0L,EpgCache.lastUpdated(it.readableDatabase,source))}
                assertFalse(File(ctx.filesDir,"epg-work").exists())
            }finally{scenario.close();cancel()}
        }
    }
}
