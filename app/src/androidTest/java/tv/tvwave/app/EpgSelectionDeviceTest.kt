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
    @Test fun fullPlaylistHorizonIsBrowsableAndSearchable()=fixture { db,stage ->
        val laterDay="20261124"
        val feed=xml.replace("</tv>", """
            <programme channel="one" start="${laterDay}110000 +0000" stop="${laterDay}120000 +0000"><title>November final</title></programme>
            <programme channel="other" start="${laterDay}130000 +0000" stop="${laterDay}140000 +0000"><title>Other final</title></programme>
            </tv>""")
        val adjusted=state.copy(epgOffsets=mapOf(c.id to 60))
        EpgCache.stage(stage,feed.byteInputStream(),state.channels,a,now,{},state=adjusted)
        EpgCache.install(db,stage,a,adjusted)
        val oldest=EpgRetention.cutoff(now)
        val final=EpgCache.guideWindow(db,c.id,oldest,now,60).last()
        assertEquals("November final", final.title)
        assertEquals(Epg.time("20261124120000 +0000"), final.start)
        assertEquals(final, EpgCache.guideWindow(db,c.id,oldest,final.start,60).last())
        assertTrue(EpgCache.hasSchedule(db,c.id,final.start,60))
        val results=EpgSearch.find(db,adjusted,"final",now)
        assertEquals(1, results.programmes.size)
        assertEquals(final.start, results.programmes.single().programme.start)
        assertEquals(2, EpgCache.status(db,a,now).programmes)
    }


    @Test fun lowerSourcesFillOnlyFreeSlotsInGuideAndSearch()=fixture { db,stage ->
        val b=a.copy(id="b")
        val third=a.copy(id="third")
        val config=state.copy(epgSources=listOf(a,b,third),epgOffsets=mapOf(c.id to 60))
        val fmt=java.text.SimpleDateFormat("yyyyMMddHHmmss Z",java.util.Locale.ROOT)
        fun row(hour:Int,duration:Int,title:String) = Triple(hour,duration,title)
        fun install(source:EpgSource,rows:List<Triple<Int,Int,String>>) {
            fun time(hour:Int)=fmt.format(java.util.Date(now+hour*3600000L))
            val id="id-"+source.id
            val raw=buildString {
                append("""<tv><channel id="$id"><display-name>One</display-name></channel>""")
                for((hour,duration,title) in rows)append("""<programme channel="$id" start="${time(hour)}" stop="${time(hour+duration)}"><title>Show $title</title></programme>""")
                append("</tv>")
            }
            EpgCache.stage(stage,raw.byteInputStream(),config.channels,source,now,{},state=config)
            EpgCache.install(db,stage,source,config)
        }
        val top=listOf(row(0,12,"A1"),row(14,10,"A2"),row(24,24,"A3"),row(48,24,"A4"))
        val tail=(3..6).map { row(it*24,24,"Day ${it+1}") }
        val second=listOf(row(0,12,"A1"),row(12,2,"Gap"),row(23,2,"Conflict"),row(48,24,"Wrong"))+tail
        install(third,listOf(row(12,2,"Other gap"),row(72,24,"Wrong tail"),row(168,24,"Day 8")))
        install(b,second)
        install(a,top)
        fun checkVisible(cfg:AppState,expected:List<String>) {
            val guide=EpgCache.guideWindow(db,c.id,now,now,60)
            assertEquals(expected.map{"Show $it"},guide.map{it.title})
            assertEquals(guide,EpgCache.schedule(db,c.id,now,now+10*86400000L,300,60))
            val search=EpgSearch.find(db,cfg,"Show",now).programmes
            assertEquals(guide.asReversed().map{it.title to it.start},search.map{it.programme.title to it.programme.start})
            assertEquals(search.size,search.distinctBy{it.key}.size)
        }
        val expected=listOf("A1","Gap","A2","A3","A4","Day 4","Day 5","Day 6","Day 7","Day 8")
        checkVisible(config,expected)
        install(b,second)
        checkVisible(config,expected)
        val swapped=config.copy(epgSources=listOf(b,a,third))
        EpgCache.rebind(db,swapped)
        checkVisible(swapped,listOf("A1","Gap","Conflict","Wrong","Day 4","Day 5","Day 6","Day 7","Day 8"))
        for(pinned in listOf(config.copy(playlistEpgSources=mapOf("p" to "a")),
                    config.copy(epgBindings=mapOf(c.id to EpgBinding("a","id-a"))))) {
            EpgCache.rebind(db,pinned)
            checkVisible(pinned,top.map{it.third})
        }
        val removed=config.copy(epgSources=listOf(a,third))
        EpgCache.reconcile(db,removed)
        checkVisible(removed,listOf("A1","Other gap","A2","A3","A4","Wrong tail","Day 8"))
    }

    @Test fun upgradeUsesCachedSecondaryScheduleBeforeAnyRefresh() {
        val file=File(ctx.cacheDir,"epg-upgrade-test.db")
        val local=object : android.content.ContextWrapper(ctx) {
            override fun getDatabasePath(name:String)=file
        }
        val pId=java.util.UUID.randomUUID().toString()
        val raw="#EXTM3U\n#EXTINF:-1,One\nhttps://example.invalid/live"
        val playlist=Playlist(pId,"Test","",raw,M3u.parse(raw,pId))
        val sources=listOf("a","b").map { legacyEpgSource("https://example.invalid/$it") }
        val config=AppState(playlists=listOf(playlist),epgSources=sources)
        SQLiteDatabase.deleteDatabase(file)
        try {
            Repository(local).use { repo ->
                repo.save(config)
                val db=repo.writableDatabase
                for((i,source) in sources.withIndex()){
                    db.execSQL("""INSERT INTO guide_catalog VALUES (?,'one','One','["One"]','')""",arrayOf(source.id))
                    db.execSQL("INSERT INTO guide_programme VALUES (?,'one',?,?,?,'')",
                        arrayOf<Any>(source.id,now+i*60000,now+(i+1)*60000,"Show $i"))
                }
                EpgCache.rebind(db,config)
                db.execSQL("DROP VIEW guide_listing")
                db.execSQL("DROP TABLE guide_extra")
                db.version=11
            }
            Repository(local).use { repo ->
                val restored=repo.load()
                val id=restored.channels.single().id
                assertEquals(listOf("Show 0","Show 1"),repo.schedule(id,now,now+120000).map{it.title})
                assertEquals(listOf("Show 1","Show 0"),EpgSearch.find(repo.readableDatabase,restored,"Show",now).programmes.map{it.programme.title})
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }

    @Test fun verifiedTextCorrectionsKeepSourceLayoutAndTimeCorrectionsRebuildIt()=fixture { db,stage ->
        val other=a.copy(id="b",url="https://example.invalid/b")
        val config=state.copy(epgSources=listOf(a,other))
        fun install(source:EpgSource,feed:String){
            val result=EpgCache.stage(stage,feed.byteInputStream(),config.channels,source,now,{},state=config)
            val version=EpgResponseVersion(source.url,"","","test")
            val imported=ImportedEpg(version,EpgHttpCache.selection(config,source.id),EpgRetention.cutoff(now),result.programmes)
            EpgCache.install(db,stage,source,config,imported)
        }
        fun titles()=EpgCache.guideWindow(db,c.id,EpgRetention.cutoff(now),now,0).map{it.title}
        val fallback=xml.replace("110000","120000").replace("One programme","Fallback")
        install(other,fallback)
        install(a,xml)
        assertEquals(listOf("One programme"),titles())
        val edited=xml.replace("One programme</title>","Revised</title><desc>New description</desc>")
        db.execSQL("CREATE TEMP TRIGGER protect_layout BEFORE DELETE ON guide_link BEGIN SELECT RAISE(ABORT,'unexpected remap'); END")
        install(a,edited)
        db.execSQL("DROP TRIGGER protect_layout")
        assertEquals("New description",EpgCache.guideWindow(db,c.id,EpgRetention.cutoff(now),now,0).single().description)
        assertEquals("Revised",EpgSearch.find(db,config,"Revised",now).programmes.single().programme.title)
        assertTrue(EpgSearch.find(db,config,"One programme",now).programmes.isEmpty())
        install(a,edited.replace("130000","120000"))
        assertEquals(listOf("Revised","Fallback"),titles())
        assertEquals(1,EpgSearch.find(db,config,"Fallback",now).programmes.size)
        install(a,edited)
        assertEquals(listOf("Revised"),titles())
        assertTrue(EpgSearch.find(db,config,"Fallback",now).programmes.isEmpty())
        install(a,edited.replace("<display-name>One</display-name>","<display-name>Renamed</display-name>"))
        assertEquals(listOf("Fallback"),titles())
    }

    @Test fun shorterRefreshReplacesCorrectionsAndPreservesEachChannelsTail()=fixture { db,stage ->
        val second=c.copy(id="local-two",name="Other")
        val both=state.copy(playlists=listOf(state.playlists.single().copy(channels=listOf(c,second))))
        val format=java.text.SimpleDateFormat("yyyyMMddHHmmss Z",java.util.Locale.ROOT)
        fun show(channel:String, hour:Int, duration:Int, title:String):String {
            fun time(h:Int)=format.format(java.util.Date(now+h*3600000L))
            return """<programme channel="$channel" start="${time(hour)}" stop="${time(hour+duration)}"><title>$title</title></programme>"""
        }
        fun install(rows:String, source:EpgSource=a){
            val input=xml.substringBefore("<programme")+rows+"</tv>"
            val config=both.copy(epgSources=listOf(source))
            EpgCache.stage(stage,input.byteInputStream(),config.channels,source,now,{},state=config)
            EpgCache.install(db,stage,source,config)
        }
        fun titles(channel:String)=EpgCache.guideWindow(db,channel,now,now,0).map{it.title}
        val original=(0 until 11).joinToString(""){show("one",it*24,24,"Old day ${it+1}")}
        install(original+show("other",0,24,"Old second")+
            show("other",24,24,"Second tail")+show("one",167,3,"Old overlap"))
        val short=show("one",0,12,"Corrected first")+show("one",160,8,"Corrected last")+
            show("other",1,23,"Corrected second")
        repeat(2){install(short)}
        val tail=(8..11).map{"Old day $it"}
        assertEquals(listOf("Corrected first","Corrected last")+tail,titles(c.id))
        assertEquals(listOf("Corrected second","Second tail"),titles(second.id))
        assertEquals(tail.reversed(),EpgSearch.find(db,both,"Old",now).programmes.map{it.programme.title})
        assertEquals(now+11*24*3600000L,EpgCache.status(db,a,now).until)
        assertEquals(8,EpgCache.status(db,a,now).programmes)
        install(show("one",240,24,"Updated day eleven"))
        assertEquals(listOf("Corrected first","Corrected last")+tail.dropLast(1)+"Updated day eleven",titles(c.id))
        assertEquals(listOf("Corrected second","Second tail"),titles(second.id))
        assertEquals(tail.dropLast(1).reversed(),EpgSearch.find(db,both,"Old",now).programmes.map{it.programme.title})
        install(short,a.copy(url="https://example.invalid/new.xml"))
        assertEquals(listOf("Corrected second"),titles(second.id))
    }

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
