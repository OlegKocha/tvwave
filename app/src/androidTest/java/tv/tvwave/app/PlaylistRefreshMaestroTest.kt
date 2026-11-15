package tv.tvwave.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Seed/assert helpers only. All refresh actions are performed by Maestro through the UI. */
class PlaylistRefreshMaestroTest {
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName=="tv.tvwave.app.debug")
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
    }
    private val base get()=InstrumentationRegistry.getArguments().getString("fixtureBase")!!
    private fun id(value:String)=UUID.nameUUIDFromBytes(value.toByteArray()).toString()
    private fun initial(auto:Boolean=false):AppState {
        val raw="#EXTM3U\n#EXTINF:-1 tvg-id=\"one\" group-title=\"Тестовые\",Канал один\n$base/old\n"
        val lists=listOf("url" to "$base/list", "broken" to "$base/broken", "file" to "").map{(name,url)->
            Playlist(id(name),name,url,raw,M3u.parse(raw,id(name),url))
        }
        val channel=lists[0].channels[0].id
        val source=EpgSource(id("guide"),"EPG QA","$base/guide")
        return AppState(uiLanguage="ru",resume=false,timeshiftEnabled=false,epgAutoUpdate=auto,playlists=lists,
            favourites=setOf(channel),history=listOf(channel),last=channel,
            epgSources=listOf(source,EpgSource(id("other"),"EPG Other","$base/other")),
            epgBindings=mapOf(channel to EpgBinding(source.id,"one")),epgOffsets=mapOf(channel to 30),
            playlistEpgSources=mapOf(lists[0].id to source.id))
    }
    private fun seed(auto:Boolean) {
        runBlocking{EpgUpdateService.cancelAndWait(ctx)}
        Repository(ctx).use{it.save(initial(auto),resetCache=true)}
    }
    @Test fun seedManual()=seed(false)
    @Test fun seedAutomatic()=seed(true)
    @Test fun assertUnchanged(){Repository(ctx).use{assertEquals(initial(),it.load())}}
    private fun assertUpdated(epg:Boolean=false,other:Boolean=false) {
        Repository(ctx).use{repo->
            val before=initial();val saved=repo.load()
            assertEquals(3,saved.playlists.size)
            assertEquals(before.playlists.drop(1),saved.playlists.drop(1))
            assertEquals(2,saved.playlists[0].channels.size)
            assertEquals(before.playlists[0].channels[0].id,saved.playlists[0].channels[0].id)
            assertEquals("$base/new",saved.playlists[0].channels[0].url)
            assertEquals(before.playlists[0].source,saved.playlists[0].source)
            assertEquals(before.favourites,saved.favourites)
            assertEquals(before.history,saved.history)
            assertEquals(before.epgBindings,saved.epgBindings)
            assertEquals(before.epgOffsets,saved.epgOffsets)
            assertEquals(before.playlistEpgSources,saved.playlistEpgSources)
            if(epg){
                val now=System.currentTimeMillis()
                assertEquals("Программа нового канала",repo.schedule(saved.playlists[0].channels[1].id,now-3600000,now+3600000).single().title)
                assertTrue(EpgCache.lastUpdated(repo.readableDatabase,saved.sources[0])>0L)
                assertEquals(other,EpgCache.lastUpdated(repo.readableDatabase,saved.sources[1])>0L)
            }
        }
    }
    @Test fun assertManualUpdated()=assertUpdated()
    @Test fun assertOneEpgUpdated()=assertUpdated(true,false)
    @Test fun assertAllEpgUpdated()=assertUpdated(true,true)
}
