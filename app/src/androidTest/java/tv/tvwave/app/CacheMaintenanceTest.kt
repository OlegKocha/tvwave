package tv.tvwave.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CacheMaintenanceTest {
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName=="tv.tvwave.app.debug");check(android.os.Build.MODEL.contains("sdk"))
    }
    private fun database(test:(SQLiteDatabase,File)->Unit){
        val file=File(ctx.cacheDir,"maintenance-test.db")
        SQLiteDatabase.deleteDatabase(file)
        try{SQLiteDatabase.openOrCreateDatabase(file,null).use { db->EpgCache.create(db);CacheMaintenance.create(db);test(db,file) }}
        finally{SQLiteDatabase.deleteDatabase(file)}
    }
    private val now=Epg.time("20261030120000 +0300")!!
    private val state=AppState(epgSources=listOf(EpgSource("a","First","https://example.com/a"),EpgSource("b","Second","https://example.com/b")))
    private fun add(db:SQLiteDatabase,source:String,title:String,start:Long,end:Long,description:String="") {
        db.execSQL("INSERT INTO guide_programme VALUES (?,'one',?,?,?,?)",arrayOf<Any>(source,start,end,title,description))
    }
    private fun titles(db:SQLiteDatabase)=db.rawQuery("SELECT title FROM guide_programme ORDER BY title",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    @Test fun weeklyCleanupPersistsAndPreservesConfigurationCataloguesAndOverlap()=database{db,file->
        val cutoff=EpgRetention.cutoff(now)
        db.execSQL("CREATE TABLE state (id INTEGER PRIMARY KEY,payload BLOB)")
        db.execSQL("INSERT INTO state VALUES (0,?)",arrayOf(byteArrayOf(1,2,3)))
        for(source in state.sources){
            db.execSQL("INSERT INTO guide_catalog VALUES (?,'one','One','[\"One\"]','https://example.com/logo.png')",arrayOf(source.id))
            db.execSQL("INSERT INTO guide_meta VALUES (?,?,?)",arrayOf<Any>(source.id,source.url,now))
        }
        db.execSQL("INSERT INTO guide_link VALUES ('channel','a','one')")
        add(db,"a","old",cutoff-7200000,cutoff)
        add(db,"a","overlap",cutoff-3600000,cutoff+1)
        add(db,"b","recent",cutoff,now+1000)
        add(db,"removed-source","orphan",now,now+1000)
        add(db,"b","future",now+86400000,now+90000000)
        assertTrue(CacheMaintenance.clean(db,state,now))
        assertEquals(listOf("future","overlap","recent"),titles(db))
        assertEquals(2,state.sources.sumOf{EpgCache.catalog(db,it.id).size})
        assertEquals(1,EpgCache.links(db).size);assertEquals(1,EpgCache.logos(db).size)
        db.rawQuery("SELECT payload FROM state",null).use{it.moveToFirst();assertArrayEquals(byteArrayOf(1,2,3),it.getBlob(0))}
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use{assertEquals(now,CacheMaintenance.lastSuccess(it))}
        assertFalse(CacheMaintenance.clean(db,state,now+EpgRetention.WEEK_MS-1))
        assertTrue(CacheMaintenance.clean(db,state,now+EpgRetention.WEEK_MS))
        assertTrue(titles(db).isEmpty())
    }
    @Test fun failedCleanupRollsBackAndIsRetried()=database{db,_->
        add(db,"a","old",now-10*86400000,now-9*86400000)
        db.execSQL("CREATE TRIGGER fail_cleanup BEFORE DELETE ON guide_programme BEGIN SELECT RAISE(ABORT,'test'); END")
        assertTrue(runCatching{CacheMaintenance.clean(db,state,now)}.isFailure)
        assertEquals(0L,CacheMaintenance.lastSuccess(db));assertEquals(listOf("old"),titles(db))
        db.execSQL("DROP TRIGGER fail_cleanup")
        assertTrue(CacheMaintenance.clean(db,state,now));assertTrue(titles(db).isEmpty())
    }
    @Test fun manualTimeCorrectionPreservesTheDisplayedBoundary()=database{db,_->
        val cutoff=EpgRetention.cutoff(now)
        add(db,"a","shifted",cutoff-7200000,cutoff-1800000)
        add(db,"a","expired",cutoff-10800000,cutoff-7200000)
        CacheMaintenance.clean(db,state.copy(epgOffsets=mapOf("channel" to 60)),now)
        assertEquals(listOf("shifted"),titles(db))
    }
    @Test fun compactionReturnsRealDiskSpaceAndSkipsLowStorage()=database{db,file->
        // Some Android devices create auto-vacuum databases; explicitly exercise
        // the fallback for a database which retains deleted pages.
        db.execSQL("PRAGMA auto_vacuum=NONE");db.execSQL("VACUUM")
        db.beginTransaction()
        try{repeat(1600){i->add(db,"a","old$i",now-10*86400000+i,now-9*86400000,"x".repeat(32768))};db.setTransactionSuccessful()}
        finally{db.endTransaction()}
        add(db,"a","keep",now,now+3600000)
        val before=file.length()
        assertTrue(before>32L*1024*1024)
        CacheMaintenance.clean(db,state,now)
        assertFalse(CacheMaintenance.compactIfUseful(db,0))
        assertTrue(CacheMaintenance.compactIfUseful(db))
        println("Compaction bytes: $before -> ${file.length()}")
        assertTrue(file.length()<before/4);assertEquals(listOf("keep"),titles(db))
        assertFalse(CacheMaintenance.compactIfUseful(db))
    }
    @Test fun onlyAbandonedKnownTemporaryFilesAreRemoved(){
        val dir=File(ctx.cacheDir,"maintenance-files").apply{mkdirs()}
        try{
            val old=listOf("epg-download.tmp","epg-staged.db","epg-staged.db-journal","epg-download-abc-123.tmp","epg-staged-abc-123.db-wal")
            val keep=listOf("backup.tvwave","playlist.m3u8","epg.xml.gz","epg-download-new.tmp")
            for(name in old+keep)File(dir,name).apply{writeText("keep");setLastModified(if(name=="epg-download-new.tmp")now else now-2*86400000)}
            val logos=File(dir,"channel-logos").apply{mkdirs()};File(logos,"cached").writeText("logo")
            assertEquals(old.size,CacheMaintenance.cleanAbandonedFiles(dir,now))
            keep.forEach{assertTrue(File(dir,it).exists())};assertTrue(File(logos,"cached").exists())
        }finally{dir.deleteRecursively()}
    }
    @Test fun upgradeV5PreservesSettingsAndBackup(){
        Repository(ctx).use { repo->
            val before=repo.load()
            repo.writableDatabase.execSQL("DROP TABLE IF EXISTS cache_maintenance")
            repo.writableDatabase.version=5
            repo.close()
            Repository(ctx).use { updated->
                assertEquals(before,updated.load())
                assertEquals(0L,CacheMaintenance.lastSuccess(updated.readableDatabase))
                updated.maintain(before,now)
                assertEquals(before,updated.load())
                assertEquals(before,BackupFile.decode(BackupFile.encode(updated.load())))
            }
        }
    }
}
