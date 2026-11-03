package tv.tvwave.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EpgSelectionDeviceTest {
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext.also{check(it.packageName.endsWith(".debug"))}
    private val now=Epg.time("20261103120000 +0000")!!
    private val a=EpgSource("a","A","https://example.invalid/a")
    private val c=Channel("local","One","TV","https://example.invalid/live")
    private val state=AppState(playlists=listOf(Playlist("p","P","","",listOf(c))),epgSources=listOf(a))
    private fun fixture(block:(SQLiteDatabase,File)->Unit){
        val main=File(ctx.cacheDir,"selection-main.db");val stage=File(ctx.cacheDir,"selection-stage.db")
        SQLiteDatabase.deleteDatabase(main);SQLiteDatabase.deleteDatabase(stage)
        try{SQLiteDatabase.openOrCreateDatabase(main,null).use{db->EpgCache.create(db);CacheMaintenance.create(db);block(db,stage)}}
        finally{SQLiteDatabase.deleteDatabase(main);SQLiteDatabase.deleteDatabase(stage)}
    }
    private val xml="""<tv><channel id="one"><display-name>One</display-name></channel><channel id="other"><display-name>Other</display-name><icon src="https://example.invalid/logo.png"/></channel><programme channel="one" start="20261103110000 +0000" stop="20261103130000 +0000"><title>One programme</title></programme><programme channel="other" start="20261103110000 +0000" stop="20261103130000 +0000"><title>Other programme</title></programme></tv>"""
    @Test fun importFiltersSchedulesButManualReimportAndEmptySelectionKeepCatalogue()=fixture{db,stage->
        val initial=EpgCache.stage(stage,xml.byteInputStream(),state.channels,a,now,{},state=state)
        assertEquals(1,initial.programmes);EpgCache.install(db,stage,a,state)
        assertEquals(2,EpgCache.catalog(db,"a").size)
        assertEquals(1,EpgCache.status(db,a,now).programmes)
        val manual=state.copy(epgBindings=mapOf("local" to EpgBinding("a","other")))
        EpgCache.rebind(db,manual);assertTrue(EpgCache.schedule(db,"local",now,now+1).isEmpty())
        EpgCache.stage(stage,xml.byteInputStream(),manual.channels,a,now,{},state=manual);EpgCache.install(db,stage,a,manual)
        assertEquals("Other programme",EpgCache.schedule(db,"local",now,now+1).single().title)
        val none=state.copy(playlists=emptyList())
        assertEquals(0,EpgCache.stage(stage,xml.byteInputStream(),emptyList(),a,now,{},state=none).programmes)
        EpgCache.install(db,stage,a,none);assertEquals(2,EpgCache.catalog(db,"a").size)
        assertEquals(now,EpgCache.lastUpdated(db,a));assertEquals(0,EpgCache.status(db,a,now).programmes)
    }
    @Test fun existingCacheShrinksOnceAndKeepsSettingsLogosAndSchedules()=fixture{db,stage->
        val all=state.copy(playlists=listOf(state.playlists.single().copy(channels=listOf(c,c.copy(id="other",name="Other")))))
        EpgCache.stage(stage,xml.byteInputStream(),all.channels,a,now,{},state=all);EpgCache.install(db,stage,a,state)
        db.execSQL("CREATE TABLE state (id INTEGER PRIMARY KEY,payload BLOB)")
        db.execSQL("INSERT INTO state VALUES (0,?)",arrayOf(byteArrayOf(1,2,3)))
        db.beginTransaction()
        try{repeat(1200){db.execSQL("INSERT INTO guide_programme VALUES ('a','other',?,?,'Unused',?)",arrayOf<Any>(now+it,now+3600000+it,"x".repeat(32768)))};db.setTransactionSuccessful()}finally{db.endTransaction()}
        val before=File(db.path).length()
        assertTrue(CacheMaintenance.filterSchedules(db,state))
        CacheMaintenance.compactIfUseful(db)
        assertTrue(File(db.path).length()<before/4)
        assertFalse(CacheMaintenance.filterSchedules(db,state))
        assertEquals(2,EpgCache.catalog(db,"a").size)
        assertEquals("https://example.invalid/logo.png",EpgCache.catalog(db,"a").first{it.id=="other"}.icon)
        assertEquals("One programme",EpgCache.schedule(db,"local",now,now+1).single().title)
        assertEquals(1,EpgCache.status(db,a,now).programmes)
        db.rawQuery("SELECT payload FROM state",null).use{it.moveToFirst();assertArrayEquals(byteArrayOf(1,2,3),it.getBlob(0))}
        println("Filtered database bytes: $before -> ${File(db.path).length()}")
    }
    @Test fun interruptedPruningRollsBackAndRetries()=fixture{db,stage->
        EpgCache.stage(stage,xml.byteInputStream(),state.channels,a,now,{},state=state);EpgCache.install(db,stage,a,state)
        db.execSQL("CREATE TRIGGER fail_filter BEFORE DELETE ON guide_programme BEGIN SELECT RAISE(ABORT,'test'); END")
        assertTrue(runCatching{CacheMaintenance.filterSchedules(db,state.copy(playlists=emptyList()))}.isFailure)
        assertEquals(1,EpgCache.status(db,a,now).programmes)
        db.execSQL("DROP TRIGGER fail_filter")
        assertTrue(CacheMaintenance.filterSchedules(db,state.copy(playlists=emptyList())))
        assertEquals(0,EpgCache.status(db,a,now).programmes)
    }
}
