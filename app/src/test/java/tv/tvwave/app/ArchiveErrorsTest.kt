package tv.tvwave.app

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ArchiveErrorsTest {
    private val now=1794132000000L
    private val start=now-7200000L
    private val channel=Channel("fixture","Test","Test","https://fixture.lunexas.top/iptv/test/1/index.m3u8",archiveDays=7)
    private val programme=Programme("fixture",start,start+3600000L,"Test")
    private fun open(status:Int=200,manifest:String,segmentStatus:Int=200,segment:ByteArray=byteArrayOf(0x47)):ArchiveSession {
        val client=OkHttpClient.Builder().addInterceptor { chain->
            val media=chain.request().url.encodedPath.endsWith(".ts")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if(media)segmentStatus else status)
                .message("Fixture").body((if(media)segment else manifest.toByteArray()).toResponseBody()).build()
        }.build()
        return IlookArchive.open(client,channel,programme,0,now)
    }
    private fun manifest(time:Long=start-20000)="#EXTM3U\n#EXTINF:10,\nhttps://fixture.lunexas.top/arch/$time.ts\n"
    @Test fun serverFailureIsNotReportedAsUserNetworkFailure(){
        val e=assertThrows(UserError::class.java){open(502,"")}
        assertEquals(tr("archive_server_error",502),e.message)
        assertEquals(tr("archive_server_busy"),ArchiveErrors.http(429))
        assertEquals(tr("archive_server_timeout"),ArchiveErrors.http(504))
    }
    @Test fun missingAndDeniedMediaRemainDistinct(){
        assertEquals(ArchiveErrors.http(404),assertThrows(UserError::class.java){open(manifest=manifest(),segmentStatus=404)}.message)
        assertEquals(ArchiveErrors.http(403),assertThrows(UserError::class.java){open(403,"")}.message)
    }
    @Test fun htmlAndNonVideoPayloadAreInvalidRecordings(){
        assertEquals(tr("archive_invalid_response"),assertThrows(UserError::class.java){open(manifest="<html>error</html>")}.message)
        assertEquals(tr("archive_invalid_response"),assertThrows(UserError::class.java){open(manifest=manifest(),segment="not video".toByteArray())}.message)
    }
    @Test fun wrongTimestampIsNotSilentlyPlayed(){
        assertEquals(tr("archive_wrong_time"),assertThrows(UserError::class.java){open(manifest=manifest(start+3600000))}.message)
        assertEquals(start-20000,open(manifest=manifest()).firstSegmentStartMs)
    }
    @Test fun transportFailuresDoNotInventArchiveAbsence(){
        assertEquals(tr("archive_response_timeout"),ArchiveErrors.failure(SocketTimeoutException()))
        assertEquals(tr("archive_address_unresolved"),ArchiveErrors.failure(IOException("secret URL",UnknownHostException())))
        assertEquals(tr("archive_incomplete_response"),ArchiveErrors.failure(EOFException()))
        assertEquals(tr("archive_transfer_failed"),ArchiveErrors.failure(IOException("secret URL")))
    }
}
