package tv.tvwave.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class PerformanceStorageTest {
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext.also{check(it.packageName=="tv.tvwave.app.debug");check(android.os.Build.MODEL.contains("sdk"))}
    private fun config(repo:Repository)=repo.readableDatabase.rawQuery("SELECT payload FROM state ORDER BY id",null).use{c->java.io.ByteArrayOutputStream().apply{while(c.moveToNext())write(c.getBlob(0))}.toByteArray()}
    @Test fun zappingOnlyWritesSmallEncryptedHistoryAndBackupRestoresIt(){
        TvFixture.seed(3,rich=true)
        lateinit var expected:AppState
        Repository(ctx).use{repo->
            var state=repo.load();val before=config(repo)
            repo.writableDatabase.execSQL("CREATE TEMP TRIGGER protect_config BEFORE DELETE ON state BEGIN SELECT RAISE(ABORT,'Configuration rewritten during zapping'); END")
            repeat(40){i->val id=state.channels[i%16].id
                state=state.copy(last=id,history=(listOf(id)+state.history.filter{it!=id}).take(30))
                repo.saveViewing(state.last,state.history)
            }
            assertArrayEquals(before,config(repo));assertEquals(state,repo.load());expected=state
            val bytes=repo.readableDatabase.rawQuery("SELECT payload FROM viewing_state",null).use{it.moveToFirst();it.getBlob(0)}
            assertTrue(bytes.size<65536);assertFalse(bytes.toString(Charsets.UTF_8).contains(state.last))
            assertTrue(runCatching{repo.saveViewing(state.last,listOf(state.last,state.last))}.isFailure)
            assertEquals(state,repo.load())
        }
        Repository(ctx).use{repo->
            assertEquals(expected,repo.load())
            val restored=BackupFile.decode(BackupFile.encode(repo.load()))
            repo.save(AppState(),resetCache=true);repo.save(restored,resetCache=true)
            assertEquals(expected,repo.load())
        }
    }
    @Test fun fullSaveRollsBackBothConfigurationAndViewing(){
        TvFixture.seed()
        Repository(ctx).use{repo->
            val before=repo.load();val bytes=config(repo)
            repo.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_viewing BEFORE INSERT ON viewing_state BEGIN SELECT RAISE(ABORT,'test'); END")
            assertTrue(runCatching{repo.save(before.copy(fontPercent=60,last=before.channels[1].id))}.isFailure)
            assertEquals(before,repo.load());assertArrayEquals(bytes,config(repo))
            repo.writableDatabase.execSQL("DROP TRIGGER fail_viewing")
            val next=before.copy(fontPercent=60,last=before.channels[1].id)
            repo.save(next);assertEquals(next,repo.load())
        }
    }
    @Test fun migrationFromV6KeepsInlineHistoryUntilFirstViewingWrite(){
        TvFixture.seed(2,rich=true)
        lateinit var old:AppState
        Repository(ctx).use{repo->
            old=repo.load().copy(last=repo.load().channels[2].id)
            val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,store.getKey("tvwave-state-v1",null) as SecretKey)}
            val payload=cipher.iv+cipher.doFinal(StateCodec.encode(old))
            val db=repo.writableDatabase
            db.execSQL("DELETE FROM state");db.execSQL("INSERT INTO state VALUES (0,?)",arrayOf(payload))
            db.execSQL("DROP TABLE viewing_state");db.execSQL("DROP TABLE guide_summary");db.version=6
        }
        Repository(ctx).use{repo->
            assertEquals(old,repo.load());assertEquals(7,repo.readableDatabase.version)
            assertTrue(EpgCache.status(repo.readableDatabase,old.sources[0]).programmes>0)
            val next=old.copy(last=old.channels[4].id,history=listOf(old.channels[4].id)+old.history)
            repo.saveViewing(next.last,next.history.distinct())
            assertEquals(next.copy(history=next.history.distinct()),repo.load())
        }
    }
    @Test fun summaryIsLightweightAndTracksMidnightCleanupAndChangedUrl(){
        val file=File(ctx.cacheDir,"performance-summary.db");SQLiteDatabase.deleteDatabase(file)
        val now=Epg.time("20261030120000 +0000")!!
        val source=legacyEpgSource("https://example.invalid/epg")
        try{SQLiteDatabase.openOrCreateDatabase(file,null).use{db->
            EpgCache.create(db);CacheMaintenance.create(db)
            db.execSQL("INSERT INTO guide_meta VALUES (?,?,?)",arrayOf<Any>(source.id,source.url,now))
            db.execSQL("INSERT INTO guide_catalog VALUES (?,'one','One','[\"One\"]','')",arrayOf(source.id))
            for((start,end,title) in listOf(Triple(now-5*86400000,now-4*86400000,"old"),Triple(now+86400000,now+86400000+3600000,"tomorrow")))
                db.execSQL("INSERT INTO guide_programme VALUES (?,'one',?,?,?,'Description')",arrayOf<Any>(source.id,start,end,title))
            val first=EpgCache.status(db,source,now);assertEquals(2,first.programmes);assertTrue(first.tomorrow)
            // A warm status / refresh check must not touch the programme table at all.
            db.execSQL("ALTER TABLE guide_programme RENAME TO hidden_programmes")
            repeat(10){assertEquals(first,EpgCache.status(db,source,now));assertEquals(now,EpgCache.lastUpdated(db,source))}
            db.execSQL("ALTER TABLE hidden_programmes RENAME TO guide_programme")
            assertFalse(EpgCache.status(db,source,now+86400000).tomorrow)
            CacheMaintenance.clean(db,AppState(epgSources=listOf(source)),now)
            assertEquals(1,EpgCache.status(db,source,now).programmes)
            assertEquals(EpgStatus(),EpgCache.status(db,source.copy(url=source.url+"?changed"),now))
            EpgCache.remove(db,source.id);assertEquals(EpgStatus(),EpgCache.status(db,source,now))
        }}finally{SQLiteDatabase.deleteDatabase(file)}
    }
    @Test fun repositoryScheduleCacheInvalidatesForImportBindingOffsetCleanupAndRestore(){
        TvFixture.seed(2,rich=true)
        val now=System.currentTimeMillis();val from=EpgRetention.cutoff(now);val to=now+3*86400000
        Repository(ctx).use{repo->
            var state=repo.load();val channel=state.channels[0].id
            fun check(offset:Int=0):List<Programme>{
                val expected=EpgCache.schedule(repo.readableDatabase,channel,from,to,10000,offset)
                val cached=repo.schedule(channel,from,to,10000,offset)
                assertEquals(expected,cached);return cached
            }
            val original=check();assertTrue(original.isNotEmpty());check()
            val stage=File(ctx.cacheDir,"performance-stage.db")
            val fmt=java.text.SimpleDateFormat("yyyyMMddHHmmss Z",java.util.Locale.ROOT)
            val xml="<tv><channel id=\"test1\"><display-name>One</display-name></channel><programme channel=\"test1\" start=\"${fmt.format(java.util.Date(now-3600000))}\" stop=\"${fmt.format(java.util.Date(now+3600000))}\"><title>Updated</title><desc>Complete new description</desc></programme></tv>"
            try{
                EpgCache.stage(stage,xml.byteInputStream(),state.channels,state.sources[0],now,{})
                repo.installEpg(stage,state.sources[0],state)
                assertEquals("Updated",check().single().title)
                assertEquals(1,EpgCache.status(repo.readableDatabase,state.sources[0]).programmes)
            }finally{SQLiteDatabase.deleteDatabase(stage)}
            state=state.copy(epgBindings=mapOf(channel to EpgBinding(state.sources[1].id,"test1")));repo.save(state)
            assertEquals(original,check());check(90);check(-30)
            // Removing an active source and then restoring must invalidate both caches.
            state=state.copy(epgSources=listOf(state.sources[0]),epgBindings=emptyMap());repo.save(state)
            assertEquals("Updated",check().single().title)
            repo.maintain(state,now+EpgRetention.WEEK_MS)
            assertTrue(check().isEmpty())
            assertEquals(0,EpgCache.status(repo.readableDatabase,state.sources[0]).programmes)
            repo.save(state,resetCache=true);assertTrue(check().isEmpty())
            assertEquals(EpgStatus(),EpgCache.status(repo.readableDatabase,state.sources[0]))
        }
    }
}
