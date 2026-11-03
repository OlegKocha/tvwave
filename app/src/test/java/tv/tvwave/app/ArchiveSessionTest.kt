package tv.tvwave.app

import org.junit.Assert.*
import org.junit.Test

class ArchiveSessionTest {
    private val start=10000000L
    private val session=ArchiveSession(Channel("a","Channel","Group","https://example.invalid/live"),Programme("a",start,start+3600000,"Show"),"file:///fixture.mp4")
    @Test fun seekCannotPassProgrammeOrLiveBoundary(){
        assertEquals(0L,session.clamp(-60000,start+1200000))
        assertEquals(1200000L,session.clamp(1800000,start+1200000))
        assertEquals(3600000L,session.clamp(5400000,start+7200000))
        assertEquals(0L,session.available(start-1))
        assertEquals(600000L,session.clamp(600000,start+1200000))
    }
    @Test fun displaysRelativeTimeIncludingHours(){
        assertEquals("00:00",playbackClock(-1))
        assertEquals("30:00",playbackClock(1800000))
        assertEquals("1:02:03",playbackClock(3723000))
    }
}
