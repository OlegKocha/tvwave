package tv.tvwave.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*

@RunWith(AndroidJUnit4::class)
class LogoDeviceTest {
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext.also{check(it.packageName=="tv.tvwave.app.debug");check(android.os.Build.MODEL.contains("sdk"))}
    private val host="http://10.0.2.2:8767"
    @Test fun seed(){
        TvFixture.seed(2)
        Repository(ctx).use{repo->
            val s=repo.load().copy(epgAutoUpdate=false);repo.save(s)
            val now=System.currentTimeMillis();val fmt=SimpleDateFormat("yyyyMMddHHmmss Z",Locale.ROOT)
            val staged=File(ctx.cacheDir,"logo-stage.db")
            for((index,source) in s.sources.withIndex()){
                val xml="<tv>"+(1..16).joinToString(""){i->
                    val icon=when(i){1->if(index==0)"good.png"else"blue.png";3->"broken.png";4->"missing.png";5->"huge.png";6->"blue.png";else->""}
                    "<channel id=\"test$i\"><display-name>Тестовый канал $i</display-name>"+(if(icon.isNotEmpty())"<icon src=\"$host/$icon\"/>"else"")+"</channel>"
                }+(1..16).joinToString(""){i->"<programme channel=\"test$i\" start=\"${fmt.format(Date(now-3600000))}\" stop=\"${fmt.format(Date(now+3600000))}\"><icon src=\"$host/poster.png\"/><title>Передача $i</title></programme>"}+"</tv>"
                EpgCache.stage(staged,xml.byteInputStream(),s.channels,source,now,{});EpgCache.install(repo.writableDatabase,staged,source,s)
            }
            android.database.sqlite.SQLiteDatabase.deleteDatabase(staged)
        }
    }
    @Test fun linksMigrationAndBackup(){
        seed()
        var original:AppState
        Repository(ctx).use{repo->
            var s=repo.load();val c=s.channels[0].id
            assertEquals("$host/good.png",EpgCache.logos(repo.readableDatabase)[c]);assertFalse(EpgCache.logos(repo.readableDatabase).containsKey(s.channels[1].id))
            s=s.copy(epgSources=s.sources.reversed());repo.save(s);assertEquals("$host/blue.png",EpgCache.logos(repo.readableDatabase)[c])
            s=s.copy(epgBindings=mapOf(c to EpgBinding(s.sources[1].id,"test1")));repo.save(s);assertEquals("$host/good.png",EpgCache.logos(repo.readableDatabase)[c])
            val backup=BackupFile.decode(BackupFile.encode(s));assertEquals(s,backup);assertFalse(String(StateCodec.encode(backup)).contains("good.png"));original=s
        }
        // Recreate the previous catalogue shape; reopen through the real v4 -> v5 upgrade.
        android.database.sqlite.SQLiteDatabase.openDatabase(ctx.getDatabasePath("tvwave.db").path,null,0).use{db->
            db.execSQL("ALTER TABLE guide_catalog RENAME TO saved_catalog")
            db.execSQL("CREATE TABLE guide_catalog (source TEXT,epg TEXT,name TEXT,aliases TEXT,PRIMARY KEY(source,epg))")
            db.execSQL("INSERT INTO guide_catalog SELECT source,epg,name,aliases FROM saved_catalog")
            db.execSQL("DROP TABLE saved_catalog");db.version=4
        }
        Repository(ctx).use{repo->
            assertEquals(original,repo.load());assertTrue(EpgCache.logos(repo.readableDatabase).isEmpty())
            assertTrue(EpgCache.status(repo.readableDatabase,original.sources[0]).updated>0)
            assertFalse(EpgCache.schedule(repo.readableDatabase,original.channels[0].id,System.currentTimeMillis(),System.currentTimeMillis()+1000).isEmpty())
            repo.save(original,resetCache=true);assertTrue(EpgCache.logos(repo.readableDatabase).isEmpty())
        }
    }
    @Test fun boundedLoadingAndCancellation()=runBlocking {
        ChannelLogos(ctx).use{loader->
            val good=loader.load("$host/good.png");assertNotNull(good);assertTrue(good!!.width<=128)
            assertSame(good,loader.load("$host/good.png"))
            for(path in listOf("broken.png","missing.png","huge.png","wide.png"))assertNull(loader.load("$host/$path"))
            assertNull(loader.load("file:///etc/hosts"))
            val task=async{loader.load("$host/slow.png")};delay(50);task.cancelAndJoin();assertTrue(task.isCancelled)
        }
        ChannelLogos(ctx).use{assertNotNull(it.load("$host/good.png"))}
    }
    @Test fun realProviderSamples()=runBlocking {
        ChannelLogos(ctx).use{loader->
            for(url in listOf("http://epg.one/img/8900.png","https://iptvx.one/picons/kamin.png")){
                val bitmap=loader.load(url);assertNotNull(url,bitmap);assertTrue(bitmap!!.width<=128&&bitmap.height<=128)
            }
        }
    }
    @Test fun seedProviderPreview(){
        seed()
        Repository(ctx).use{repo->
            val font=InstrumentationRegistry.getArguments().getString("fontPercent")?.toIntOrNull()?:100
            require(font in 60..130)
            repo.save(repo.load().copy(fontPercent=font))
            val source=repo.load().sources[0].id
            repo.writableDatabase.execSQL("UPDATE guide_catalog SET icon=? WHERE source=? AND epg='test1'",arrayOf("http://epg.one/img/8900.png",source))
            repo.writableDatabase.execSQL("UPDATE guide_catalog SET icon=? WHERE source=? AND epg='test6'",arrayOf("https://iptvx.one/picons/kamin.png",source))
        }
    }
}
