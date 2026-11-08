package tv.tvwave.app

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Describe observed failures, without guessing that the viewer's internet is at fault. */
object ArchiveErrors {
    fun http(code:Int):String=when(code){
        401,403->tr("the_source_denied_access_to_the_recording_check_your_subscription")
        404,410->tr("this_recording_is_unavailable_the_provider_did_not_return_any_video_tr")
        408,504->tr("archive_server_timeout")
        429->tr("archive_server_busy")
        in 500..599->tr("archive_server_error",code)
        else->tr("archive_request_rejected",code)
    }
    fun failure(error:Throwable):String {
        val causes=generateSequence(error){it.cause}.take(12).toList()
        causes.filterIsInstance<UserError>().firstOrNull()?.let{return it.message.orEmpty()}
        return when {
            causes.any{it is UnknownHostException}->tr("archive_address_unresolved")
            causes.any{it is SSLException}->tr("archive_secure_connection_failed")
            causes.any{it is InterruptedIOException}->tr("archive_response_timeout")
            causes.any{it is EOFException||it is ProtocolException}->tr("archive_incomplete_response")
            causes.any{it is ConnectException}->tr("archive_connection_failed")
            causes.any{it is IOException}->tr("archive_transfer_failed")
            else->tr("archive_open_failed")
        }
    }
}
