package tv.tvwave.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class PlaylistRefreshIntegrationTest {
    @Test fun guideRefreshUpdatesUrlPlaylistsBeforeImportAndKeepsFailedAndLocalSources() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName=="tv.tvwave.app.debug")
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        val server=ServerSocket(0)
        val base="http://127.0.0.1:${server.localPort}"
        val requests=CopyOnWriteArrayList<String>()
        fun raw(id:String,url:String)="#EXTM3U\n#EXTINF:-1 tvg-id=\"$id\",$id\n$url\n"
        val oldRaw=raw("one","$base/old")
        val newRaw=raw("one","$base/new")+raw("added","$base/added").substringAfter("\n")
        val now=System.currentTimeMillis()
        val fmt=SimpleDateFormat("yyyyMMddHHmmss Z",Locale.ROOT).apply{timeZone=TimeZone.getTimeZone("UTC")}
        val xml="""<?xml version="1.0" encoding="UTF-8"?><tv>
            <channel id="one"><display-name>one</display-name></channel>
            <channel id="added"><display-name>added</display-name></channel>
            <programme channel="added" start="${fmt.format(Date(now-60000))}" stop="${fmt.format(Date(now+3600000))}"><title>New channel programme</title></programme>
            </tv>"""
        val worker=thread(isDaemon=true) {
            while(!server.isClosed) {
                val socket=try{server.accept()}catch(_:Exception){break}
                socket.use{
                    val reader=it.getInputStream().bufferedReader()
                    val path=reader.readLine().split(" ")[1]
                    while(!reader.readLine().isNullOrEmpty()){}
                    requests.add(path)
                    val code=if(path=="/broken")503 else 200
                    val bytes=(if(path=="/guide")xml else newRaw).toByteArray()
                    it.getOutputStream().apply{
                        write("HTTP/1.1 $code Result\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes);flush()
                    }
                }
            }
        }
        fun playlist(name:String,source:String):Playlist {
            val id=java.util.UUID.randomUUID().toString()
            return Playlist(id,name,source,oldRaw,M3u.parse(oldRaw,id,source))
        }
        val epgId=java.util.UUID.randomUUID().toString()
        val good=playlist("url","$base/list")
        val bad=playlist("bad","$base/broken")
        val local=playlist("file","")
        val initial=AppState(uiLanguage="en",epgAutoUpdate=false,playlists=listOf(good,bad,local),
            favourites=setOf(good.channels.first().id),epgSources=listOf(EpgSource(epgId,"Test","$base/guide")))
        var scenario:ActivityScenario<MainActivity>?=null
        try {
            runBlocking{EpgUpdateService.cancelAndWait(ctx)}
            Repository(ctx).use{it.save(initial,resetCache=true)}
            scenario=ActivityScenario.launch(MainActivity::class.java)
            val revision=EpgUpdateService.state.value.revision
            InstrumentationRegistry.getInstrumentation().runOnMainSync{EpgUpdateService.enqueue(ctx,listOf(epgId))}
            val end=SystemClock.elapsedRealtime()+45000
            while(SystemClock.elapsedRealtime()<end) {
                if(EpgUpdateService.state.value.revision>revision&&!EpgUpdateService.state.value.running)break
                SystemClock.sleep(200)
            }
            assertTrue("EPG import completed",EpgUpdateService.state.value.revision>revision)
            assertFalse(EpgUpdateService.state.value.running)
            assertEquals(listOf("/list","/broken","/guide"),requests.toList())
            assertTrue(EpgUpdateService.state.value.playlistFailures.containsKey(bad.id))
            assertTrue(EpgUpdateService.state.value.failures.isEmpty())
            Repository(ctx).use{repo->
                val saved=repo.load()
                assertEquals(2,saved.playlists[0].channels.size)
                assertEquals("$base/new",saved.playlists[0].channels[0].url)
                assertEquals(bad,saved.playlists[1]);assertEquals(local,saved.playlists[2])
                assertEquals(initial.favourites,saved.favourites)
                assertEquals("New channel programme",repo.schedule(saved.playlists[0].channels[1].id,now-60000,now+3600000).single().title)
            }
            // Activity must see background changes, not continue using the old list.
            scenario.onActivity{activity->
                val getter=MainActivity::class.java.declaredMethods.first{it.name=="getData"&&it.parameterCount==0}.apply{isAccessible=true}
                assertEquals(2,(getter.invoke(activity) as AppState).playlists[0].channels.size)
            }
        } finally {
            runBlocking{EpgUpdateService.cancelAndWait(ctx)}
            scenario?.close();server.close();worker.join(2000)
        }
    }
}
