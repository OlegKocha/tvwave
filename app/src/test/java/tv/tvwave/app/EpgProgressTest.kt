package tv.tvwave.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class EpgProgressTest {
    @Test fun unknownSizeAndReadAheadNeverClaimCompletion(){
        assertNull(EpgProgress.percent(100,-1))
        assertNull(EpgProgress.percent(0,0))
        assertFalse(EpgProgress.downloading(100,-1).contains("%"))
        assertEquals(0,EpgProgress.percent(-1,100))
        assertEquals(60,EpgProgress.percent(60,100))
        assertEquals(99,EpgProgress.percent(100,100))
        assertEquals(99,EpgProgress.percent(120,100))
        assertTrue(EpgProgress.processing(760000,60,100).endsWith("≈60%"))
    }
    @Test fun localPlainAndGzipFilesHaveMonotonicProcessingProgress(){
        val now=Epg.time("20261030120000 +0000")!!
        val random=java.util.Random(42)
        val xml=buildString{
            append("<tv><channel id=\"one\"><display-name>One</display-name></channel>")
            repeat(40001){i->
                append("<programme channel=\"one\" start=\"20261030110000 +0000\" stop=\"20261030130000 +0000\"><title>Show $i</title><desc>")
                repeat(24){append(random.nextInt().toUInt().toString(16))}
                append("</desc></programme>")
            }
            append("</tv>")
        }.toByteArray()
        for(gzip in listOf(false,true)){
            val bytes=if(gzip)ByteArrayOutputStream().apply{GZIPOutputStream(this).use{it.write(xml)}}.toByteArray()else xml
            val input=Epg.Bounded(bytes.inputStream(),Epg.MAX_COMPRESSED)
            val progress=mutableListOf<Int>();val counts=mutableListOf<Int>()
            val result=Epg.parse(input,emptyList(),now,{},onProgress={n->counts.add(n);progress.add(EpgProgress.percent(input.count,bytes.size.toLong())!!)},includeUnmatched=true)
            assertEquals(40001,result.programmes)
            assertEquals(listOf(10000,20000,30000,40000),counts)
            assertTrue(progress.zipWithNext().all{(a,b)->a<=b})
            assertTrue(progress.first() in 1..90)
            assertTrue(progress.last() in 90..99)
            assertEquals(bytes.size.toLong(),input.count)
        }
    }
}
