package tv.tvwave.app

import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/** A null body on a validated 304 leaves the installed database as the checkpoint. */
internal class EpgDownload(private val client: OkHttpClient) {
    private val calls = mutableSetOf<Call>()

    fun cancel() = synchronized(calls) { calls.forEach { it.cancel() } }

    fun fetch(source: EpgSource, cached: EpgResponseVersion?, partial: File, complete: File,
              context: CoroutineContext, onBytes: (Long, Long) -> Unit): EpgResponseVersion {
        var conditional = cached != null && (cached.etag.isNotEmpty() || cached.modified.isNotEmpty())
        while (true) {
            context.ensureActive()
            val request = Request.Builder().url(source.url)
            if (conditional && cached != null) {
                if (cached.etag.isNotEmpty()) request.header("If-None-Match",cached.etag)
                if (cached.modified.isNotEmpty()) request.header("If-Modified-Since",cached.modified)
            }
            val call = client.newCall(request.build())
            synchronized(calls) { calls.add(call) }
            try {
                context.ensureActive()
                call.execute().use { response ->
                    val url = response.request.url.toString()
                    val etag = response.header("ETag")
                    val modified = response.header("Last-Modified")
                    if (response.code == 304 && conditional && cached != null) {
                        if (url == cached.effectiveUrl)
                            return cached.copy(etag=etag ?: cached.etag, modified=modified ?: cached.modified)
                        // A redirect now leads to a different representation. Retry without validators.
                        conditional = false
                    } else {
                        if (!response.isSuccessful) throw UserError(tr("the_epg_source_returned_http",response.code))
                        val body = response.body ?: throw UserError(tr("empty_epg_response"))
                        val length = body.contentLength()
                        if (length > Epg.MAX_COMPRESSED) throw UserError(tr("the_epg_source_exceeds_128_mb"))
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(65536)
                        var total = 0L
                        body.byteStream().use { input -> partial.outputStream().use { out ->
                            while (true) {
                                context.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > Epg.MAX_COMPRESSED) throw UserError(tr("the_epg_source_exceeds_128_mb"))
                                digest.update(buffer,0,count)
                                out.write(buffer,0,count)
                                onBytes(total,length)
                            }
                            out.fd.sync()
                        } }
                        check(partial.renameTo(complete)) { "Cannot preserve EPG download" }
                        return EpgResponseVersion(url,etag.orEmpty(),modified.orEmpty(),digest.digest().toHex())
                    }
                }
            } finally {
                synchronized(calls) { calls.remove(call) }
                partial.delete()
            }
        }
    }
}
