package tv.tvwave.app

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class EpgRetentionTest {
    @Test fun calendarMidnightAcrossMonthYearAndDst(){
        for((now,expected,zone) in listOf(
            Triple("20261030173000 +0300","20261025000000 +0300","Europe/Moscow"),
            Triple("20261106173000 +0300","20261101000000 +0300","Europe/Moscow"),
            Triple("20260101173000 +0300","20251227000000 +0300","Europe/Moscow"),
            Triple("20260330173000 +0200","20260325000000 +0100","Europe/Berlin")
        ))assertEquals(Epg.time(expected),EpgRetention.cutoff(Epg.time(now)!!,TimeZone.getTimeZone(zone)))
    }
    @Test fun weeklyAndClockRollback(){
        val now=Epg.time("20261030120000 +0300")!!
        assertTrue(EpgRetention.due(0,now))
        assertFalse(EpgRetention.due(now,now+EpgRetention.WEEK_MS-1))
        assertTrue(EpgRetention.due(now,now+EpgRetention.WEEK_MS))
        assertTrue(EpgRetention.due(now,now-1))
    }
    @Test fun importKeepsOverlapAndFiveCalendarDaysButNotOlderData(){
        val now=System.currentTimeMillis();val cutoff=EpgRetention.cutoff(now)
        val format=java.text.SimpleDateFormat("yyyyMMddHHmmss Z",java.util.Locale.ROOT)
        fun show(title:String,start:Long,end:Long)="<programme channel=\"one\" start=\"${format.format(java.util.Date(start))}\" stop=\"${format.format(java.util.Date(end))}\"><title>$title</title></programme>"
        val xml="<tv><channel id=\"one\"><display-name>One</display-name></channel>"+
            show("old",cutoff-3600000,cutoff)+show("overlap",cutoff-3600000,cutoff+3600000)+
            show("fiveDays",cutoff+3600000,cutoff+7200000)+show("current",now-3600000,now+3600000)+
            show("farFuture",now+4*86400000,now+5*86400000)+"</tv>"
        val saved=mutableListOf<Programme>()
        Epg.parse(xml.byteInputStream(),emptyList(),now,{saved.add(it)},includeUnmatched=true)
        assertEquals(listOf("overlap","fiveDays","current"),saved.map{it.title})
    }
}
