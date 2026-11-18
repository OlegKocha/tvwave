package tv.tvwave.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgSearchTest {
    private val now=System.currentTimeMillis()
    private val a=Channel("a","Матч ТВ","Спорт","https://example.invalid/live")
    private val b=Channel("b","Матч ТВ HD","Спорт","https://example.invalid/live2")
    private fun state()=AppState(playlists=listOf(Playlist("p","Test","","",listOf(a,b))),epgOffsets=mapOf("b" to 60))
    private fun fixture(block:(SQLiteDatabase)->Unit)=SQLiteDatabase.create(null).use{db->
        EpgCache.create(db)
        db.execSQL("INSERT INTO guide_link VALUES ('a','primary','match'),('b','primary','match'),('removed','primary','unused')")
        block(db)
    }
    private fun row(db:SQLiteDatabase,source:String="primary",id:String="match",title:String,start:Long=now-600000,end:Long=now+600000){
        db.execSQL("INSERT INTO guide_programme VALUES (?,?,?,?,?,?)",arrayOf<Any>(source,id,start,end,title,"Description is loaded only on selection"))
    }
    @Test fun unicodeResolvedSourcesAndTimeOffsets(){fixture{db->
        row(db,title="ПРЯМОЙ МАТЧ")
        row(db,source="lower-priority",title="Матч из другого источника")
        row(db,id="unused",title="Матч удалённого канала")
        val results=EpgSearch.find(db,state()," мАтЧ ",now)
        assertEquals(listOf("a","b"),results.channels.map{it.id})
        assertEquals(2,results.programmes.size)
        assertEquals(setOf("ПРЯМОЙ МАТЧ"),results.programmes.map{it.programme.title}.toSet())
        assertEquals(now-600000+3600000,results.programmes.single{it.channel.id=="b"}.programme.start)
        assertTrue(results.programmes.all{it.programme.description.isEmpty()})
    }}
    @Test fun cyrillicYoAndLiteralCharactersAndBounds(){fixture{db->
        row(db,title="Всё о спорте: 100%",start=now-3600000,end=now)
        row(db,title="Матч очень старый",start=now-12*86400000L,end=now-11*86400000L)
        assertEquals(2,EpgSearch.find(db,state(),"ВСЕ 100%",now).programmes.size)
        assertEquals(0,EpgSearch.find(db,state(),"_",now).programmes.size)
        assertEquals(0,EpgSearch.find(db,state(),"старый",now).programmes.size)
        assertTrue(EpgSearch.find(db,state(),"  ",now).channels.isEmpty())
    }}
    @Test fun newestPageUsesDisplayedTimeAndRespectsCancellation(){fixture{db->
        repeat(1250){row(db,title="Матч $it",start=now+it*60000L,end=now+(it+1)*60000L)}
        val results=EpgSearch.find(db,state(),"матч",now)
        assertTrue(results.hasNextPage)
        assertEquals(EpgSearch.LIMIT,results.programmes.size)
        assertEquals("b",results.programmes.first().channel.id)
        assertEquals("Матч 1249",results.programmes.first().programme.title)
        var cancelled=false
        try{EpgSearch.find(db,state(),"матч",now){throw java.util.concurrent.CancellationException()}}
        catch(e:java.util.concurrent.CancellationException){cancelled=true}
        assertTrue(cancelled)
    }}
    @Test fun repeatedTitleAcrossPagesKeepsEveryMatchingBroadcast(){fixture{db->
        repeat(1100){i->row(db,title="Повтор передачи",start=now+i*60000L,end=now+i*60000L+600000)}
        val results=EpgSearch.find(db,state(),"повтор",now)
        assertTrue(results.hasNextPage)
        assertEquals(EpgSearch.LIMIT,results.programmes.size)
        val keys=mutableSetOf<String>()
        var previousStart=Long.MAX_VALUE
        var page=results
        while(true){
            for(match in page.programmes){
                assertTrue(keys.add(match.key))
                assertTrue(previousStart >= match.programme.start)
                previousStart=match.programme.start
            }
            if(!page.hasNextPage)break
            page=EpgSearch.find(db,state(),"повтор",now,after=page.programmes.last())
        }
        assertEquals(2200,keys.size)
    }}
    @Test fun equalTimesAcrossBoundaryAndExactlyFullLastPage(){fixture{db->
        val channels=(1..400).map{a.copy(id="ch$it",name="Channel")}
        channels.forEach{db.execSQL("INSERT INTO guide_link VALUES (?,'primary','match')",arrayOf(it.id))}
        val state=AppState(playlists=listOf(Playlist("p","Test","","",channels)))
        row(db,title="Sport")
        val first=EpgSearch.find(db,state,"sport",now)
        val second=EpgSearch.find(db,state,"sport",now,first.programmes.last())
        assertTrue(first.hasNextPage)
        assertFalse(second.hasNextPage)
        assertEquals(200,second.programmes.size)
        assertEquals(channels.map{it.id}.sorted(),(first.programmes+second.programmes).map{it.channel.id})
    }}
    @Test fun chronologicalScanUsesCoveringIndexAfterMigration(){fixture{db->
        db.execSQL("DROP INDEX guide_programme_by_time")
        EpgCache.migrate(db)
        val plan=db.rawQuery("EXPLAIN QUERY PLAN SELECT start,title,source,epg,end FROM guide_programme WHERE start<=? AND end>? ORDER BY start DESC,source,epg LIMIT 1024",
            arrayOf(now.toString(),"0")).use{c->
            buildString{while(c.moveToNext())append(c.getString(3))}
        }
        assertTrue(plan,plan.contains("COVERING INDEX guide_programme_by_time"))
    }}
    @Test fun recentSearchesPersistWithThirtyUniqueQueriesAndRemoval() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("search_history_test",0)
        prefs.edit().clear().commit()
        try {
            val history=SearchHistory(prefs)
            (1..31).forEach{history.record("Канал $it")}
            assertEquals((31 downTo 2).map{"Канал $it"},history.read())
            history.record("  КАНАЛ   4  ")
            assertEquals(listOf("КАНАЛ 4")+(31 downTo 2).filter{it!=4}.map{"Канал $it"},SearchHistory(prefs).read())
            assertEquals(history.read(),history.record("  "))
            val state=GuideSearchState()
            state.query="Матч";state.selectedKey="old";state.returnToResults=true
            state.beginVisit(history.read())
            assertEquals("",state.query);assertEquals("",state.selectedKey)
            assertFalse(state.returnToResults)
            assertEquals(30,state.recentQueries.size)
            val remaining=history.remove("канал 4")
            assertEquals(29,remaining.size)
            assertFalse(remaining.any{EpgSearch.normalize(it)=="канал 4"})
            assertEquals(remaining,SearchHistory(prefs).read())
            assertEquals(remaining,history.remove("missing"))
            remaining.forEach{history.remove(it)}
            assertTrue(history.read().isEmpty())
        }finally{prefs.edit().clear().commit()}
    }
}
