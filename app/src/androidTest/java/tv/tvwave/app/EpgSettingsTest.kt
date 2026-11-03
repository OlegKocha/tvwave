package tv.tvwave.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File

@RunWith(AndroidJUnit4::class)
class EpgSettingsTest {
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext.also{
        check(it.packageName=="tv.tvwave.app.debug")
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
    }
    private fun expected():AppState {
        val lists=(1..3).map{n->
            val id=UUID.nameUUIDFromBytes("portable-list-$n".toByteArray()).toString()
            val raw="#EXTM3U\n"+(1..16).joinToString("\n"){i->"#EXTINF:-1 tvg-id=\"test$i\" group-title=\"Тестовые\",Тестовый канал $i\nhttp://10.0.2.2:8765/video.mp4"}+"\n"
            val url=if(n==2)""else "http://10.0.2.2:8765/playlist.m3u8?list=$n"
            Playlist(id,if(n==2)"Локальный файл"else "Плейлист $n",url,raw,M3u.parse(raw,id,url))
        }
        val sources=listOf(3,1,2).map{legacyEpgSource("http://10.0.2.2:8765/epg/$it.xml").copy(name="EPG 0$it")}
        return AppState(uiLanguage="ru",playlists=lists,epgSources=sources,epgAutoUpdate=false,fontPercent=85,epgIntervalHours=6,
            favourites=setOf(lists[0].channels[0].id,lists[1].channels[2].id),history=listOf(lists[1].channels[2].id,lists[0].channels[0].id),last=lists[1].channels[2].id,
            audioLanguage="en",subtitles=true,resume=false,
            epgBindings=mapOf(lists[0].channels[1].id to EpgBinding(sources[1].id,"test2")),
            playlistEpgSources=mapOf(lists[1].id to sources[2].id),epgOffsets=mapOf(lists[0].channels[0].id to 90,lists[2].channels[15].id to -30))
    }
    @Test fun seedMultipleForBackup(){
        TvFixture.seed(3,rich=true)
        Repository(ctx).use{it.save(expected())}
    }
    @Test fun assertRestoredConfiguration(){
        Repository(ctx).use{repo->
            assertEquals(expected(),repo.load())
            assertEquals(0,repo.readableDatabase.rawQuery("SELECT COUNT(*) FROM guide_programme",null).use{it.moveToFirst();it.getInt(0)})
        }
    }
    @Test fun assertOnlyOneSourceUpdated(){
        Repository(ctx).use{repo->
            val s=repo.load();val statuses=s.sources.associate{it.name to EpgCache.status(repo.readableDatabase,it)}
            // Rich seeded sources have 1536 programmes; only EPG 01 receives the 16-programme HTTP fixture.
            assertEquals(16,statuses.getValue("EPG 01").programmes)
            assertEquals(1536,statuses.getValue("EPG 02").programmes)
            assertEquals(1536,statuses.getValue("EPG 03").programmes)
            assertFalse(statuses.getValue("EPG 01").tomorrow)
            assertTrue(statuses.getValue("EPG 02").tomorrow)
        }
    }
    @Test fun assertReordered(){Repository(ctx).use{repo->assertEquals(listOf("EPG 02","EPG 03","EPG 01"),repo.load().sources.map{it.name})}}
    @Test fun seedTen(){TvFixture.seed(10,font=130,rich=true);Repository(ctx).use{it.save(it.load().copy(epgAutoUpdate=false))}}
    @Test fun assertTenReordered(){Repository(ctx).use{assertEquals((2..10).map{"EPG ${it.toString().padStart(2,'0')}"}+"EPG 01",it.load().sources.map{it.name})}}
    @Test fun seedManagement(){TvFixture.seed(3,rich=true,todayOnly=true);Repository(ctx).use{it.save(it.load().copy(epgAutoUpdate=false))}}
    @Test fun seedProgress(){TvFixture.seed(2,font=130,rich=true);Repository(ctx).use{it.save(it.load().copy(epgAutoUpdate=false))}}
    @Test fun assertAssignmentAndOffset(){Repository(ctx).use{repo->val s=repo.load();assertEquals(s.sources[1].id,s.playlistEpgSources[s.playlists[0].id]);assertEquals(30,s.epgOffsets.values.single())}}
    @Test fun shiftedScheduleUsesCorrectWindowAndPriority(){
        TvFixture.seed(3,rich=true)
        Repository(ctx).use{repo->
            var s=repo.load();val channel=s.channels[0];val now=System.currentTimeMillis()
            val original=EpgCache.schedule(repo.readableDatabase,channel.id,now-86400000,now+86400000,10000)
            val shifted=EpgCache.schedule(repo.readableDatabase,channel.id,now-86400000+5400000,now+86400000+5400000,10000,90)
            assertEquals(original.map{it.copy(start=it.start+5400000,end=it.end+5400000)},shifted)
            s=s.copy(epgSources=s.sources.reversed());repo.save(s)
            assertEquals(s.sources[0].id,EpgCache.links(repo.readableDatabase)[channel.id]?.source)
            s=s.copy(playlistEpgSources=mapOf(s.playlists[0].id to s.sources[2].id));repo.save(s)
            assertEquals(s.sources[2].id,EpgCache.links(repo.readableDatabase)[channel.id]?.source)
            assertEquals(s,BackupFile.decode(BackupFile.encode(s)))
        }
    }
}
