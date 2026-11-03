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
    private class Server(private val body:ByteArray):AutoCloseable {
        val socket=ServerSocket(0);val gate=CountDownLatch(1);val requests=AtomicInteger()
        val url get()="http://127.0.0.1:${socket.localPort}/epg.xml"
        init{Thread{
            while(!socket.isClosed)try{val client=socket.accept();Thread{
                client.use { c->runCatching{
                    val reader=c.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){}
                    requests.incrementAndGet()
                    val out=c.getOutputStream();out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());out.flush()
                    gate.await(25,TimeUnit.SECONDS);out.write(body);out.flush()
                }}
            }.apply{isDaemon=true;start()}}catch(_:Exception){}
        }.apply{isDaemon=true;start()}}
        override fun close(){gate.countDown();socket.close()}
    }
    @Test fun twoSourcesFinishAfterActivityDestroyed(){
        guard();Server(xml()).use{server->
            val sources=listOf(EpgSource(UUID.nameUUIDFromBytes("background-1".toByteArray()).toString(),"One",server.url),EpgSource(UUID.nameUUIDFromBytes("background-2".toByteArray()).toString(),"Two",server.url+"?second"))
            Repository(ctx).use{it.save(state(sources),resetCache=true)}
            val scenario=ActivityScenario.launch(MainActivity::class.java)
            try{
                scenario.onActivity{EpgUpdateService.enqueue(it,sources.map{source->source.id})}
                waitFor{server.requests.get()==1&&EpgUpdateService.state.value.running}
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
