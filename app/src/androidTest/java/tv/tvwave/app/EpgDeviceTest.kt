package tv.tvwave.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EpgDeviceTest {
    @Test fun multipleSourcesManualChoiceRemovalAndConfigOnlyRestore(){
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ctx.packageName.endsWith(".debug"));ctx.deleteDatabase("tvwave.db")
        val id="56e6a36d-8015-42b4-94f0-d9a08fa01a91"
        val raw="#EXTM3U\n#EXTINF:-1,Channel HD\nhttps://example.invalid/live\n"
        val p=Playlist(id,"Test","",raw,M3u.parse(raw,id));val c=p.channels.single()
        val a=legacyEpgSource("https://example.invalid/a.xml");val b=legacyEpgSource("https://example.invalid/b.xml")
        val now=Epg.time("20261027200000 +0300")!!
        fun xml(title:String)="""<?xml version="1.0"?><!DOCTYPE tv SYSTEM "https://example.invalid/no.dtd"><tv><channel id="one"><display-name>Channel</display-name></channel><channel id="manual"><display-name>Other name</display-name></channel><programme channel="one" start="20261027190000 +0300" stop="20261027210000 +0300"><title>$title</title></programme><programme channel="manual" start="20261027190000 +0300" stop="20261027210000 +0300"><title>Manual programme</title></programme></tv>"""
        val staged=File(ctx.cacheDir,"epg-test.db")
        Repository(ctx).use{repo->
            var state=AppState(uiLanguage="ru",playlists=listOf(p),epgSources=listOf(a,b),epgAutoUpdate=false)
            repo.save(state)
            for((source,title) in listOf(a to "First source",b to "Second source")){
                EpgCache.stage(staged,xml(title).byteInputStream(),listOf(c),source,now,{})
                EpgCache.install(repo.writableDatabase,staged,source,state)
            }
            fun title()=EpgCache.schedule(repo.readableDatabase,c.id,now,now+1000).single().title
            assertEquals("First source",title());assertEquals(2,EpgCache.catalog(repo.readableDatabase,b.id).size)
            state=state.copy(epgBindings=mapOf(c.id to EpgBinding(b.id,"manual")));repo.save(state)
            assertEquals("Manual programme",title())
            val bytes=BackupFile.encode(state);assertTrue(bytes.size<2048)
            val restored=BackupFile.decode(bytes);assertEquals(state,restored)
            repo.save(restored,resetCache=true)
            assertTrue(EpgCache.schedule(repo.readableDatabase,c.id,now,now+1000).isEmpty())
            assertEquals(mapOf(c.id to EpgBinding(b.id,"manual")),repo.load().epgBindings)
            EpgCache.stage(staged,xml("Reloaded").byteInputStream(),listOf(c),b,now,{})
            EpgCache.install(repo.writableDatabase,staged,b,restored);assertEquals("Manual programme",title())
            repo.save(restored.copy(epgSources=listOf(a),epgBindings=emptyMap()))
            assertTrue(EpgCache.catalog(repo.readableDatabase,b.id).isEmpty())
        }
        android.database.sqlite.SQLiteDatabase.deleteDatabase(staged)
    }
    @Test fun migrateOldCacheWithoutLosingSchedules(){
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;assertTrue(ctx.packageName.endsWith(".debug"))
        val f=File(ctx.cacheDir,"migration.db");android.database.sqlite.SQLiteDatabase.deleteDatabase(f)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(f,null).use{db->
            db.execSQL("CREATE TABLE epg_meta (id INTEGER,url TEXT,updated INTEGER)");db.execSQL("INSERT INTO epg_meta VALUES(1,'https://example.invalid/old',100)")
            db.execSQL("CREATE TABLE epg_mapping(channel TEXT,epg TEXT)");db.execSQL("INSERT INTO epg_mapping VALUES('c','one')")
            db.execSQL("CREATE TABLE epg_programme(epg TEXT,start INTEGER,end INTEGER,title TEXT,description TEXT)");db.execSQL("INSERT INTO epg_programme VALUES('one',1,1000,'Old programme','')")
            EpgCache.migrate(db);assertEquals("Old programme",EpgCache.schedule(db,"c",2,900).single().title)
            assertEquals(legacyEpgSource("https://example.invalid/old").id,EpgCache.links(db)["c"]?.source)
        }
        android.database.sqlite.SQLiteDatabase.deleteDatabase(f)
    }
}
