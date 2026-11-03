package tv.tvwave.app

import org.junit.Test
import org.junit.Assert.*

class ScheduleMemoryCacheTest {
    @Test fun slidingWindowReusesCompleteDescriptions(){
        val cache=ScheduleMemoryCache();var reads=0
        val description="Full description ".repeat(100)
        repeat(20){step->
            for(channel in (step+19..step+32).toList()+0){
                val rows=cache.get("$channel",0,7200000,if(channel==step+25)10000 else 300,0){_,_,_->reads++;listOf(Programme("$channel",0,3600000,"Title",description))}
                assertEquals(description,rows.single().description)
            }
        }
        assertEquals(34,reads);assertTrue(cache.size<=32)
    }
    @Test fun exactTimeWindowAndExpandedLimit(){
        val cache=ScheduleMemoryCache();var reads=0
        val data=(0..9).map{Programme("one",it*1000L,(it+1)*1000L,"$it")}
        fun get(to:Long,limit:Int)=cache.get("a",0,to,limit,0){_,_,n->reads++;data.take(n)}
        assertEquals(listOf("0","1"),get(1500,2).map{it.title})
        assertEquals(1,reads)
        assertEquals(5,get(4500,10000).size);assertEquals(2,reads)
        assertEquals(7,get(6500,10000).size);assertEquals(2,reads)
        assertEquals(2,get(6500,2).size);assertEquals(2,reads)
    }
    @Test fun invalidationOffsetsAndTimeWindow(){
        val cache=ScheduleMemoryCache();var reads=0
        fun get(from:Long=0,to:Long=1000,offset:Int=0)=cache.get("a",from,to,10000,offset){_,_,_->reads++;emptyList()}
        get();get();assertEquals(1,reads)
        get(offset=30);assertEquals(2,reads)
        get(from=1);assertEquals(3,reads)
        get(to=3600001);assertEquals(4,reads)
        cache.clear();get();assertEquals(5,reads)
    }
    @Test fun lruAndOversizedRowsNeverExceedBudgetOrTruncate(){
        val cache=ScheduleMemoryCache(maxBytes=1024,maxEntries=2);var reads=0
        fun get(id:String,desc:String="")=cache.get(id,0,1000,300,0){_,_,_->reads++;listOf(Programme(id,0,2000,"Title",desc))}
        get("a");get("b");get("a");get("c");get("b")
        assertEquals(4,reads);assertTrue(cache.size<=2);assertTrue(cache.retainedBytes<=1024)
        val huge="x".repeat(2000)
        assertEquals(huge,get("huge",huge).single().description)
        assertEquals(huge,get("huge",huge).single().description)
        assertEquals(6,reads);assertTrue(cache.retainedBytes<=1024)
    }
}
