package tv.tvwave.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException

/** No GitHub credentials. The endpoint and allowed asset path are fixed. */
class AppUpdateClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(10, TimeUnit.MINUTES).followSslRedirects(false).build()) {

    private suspend fun request(url: String): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", "TVWave/${BuildConfig.VERSION_NAME}")
            .header("Accept", if (url == AppRelease.API) "application/vnd.github+json" else "application/octet-stream")
            .header("X-GitHub-Api-Version", "2022-11-28").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    suspend fun check(installed: String): AppRelease? = withContext(Dispatchers.IO) {
        request(AppRelease.API).use { response ->
            when (response.code) {
                404 -> throw UpdateFailure("update_no_releases")
                403, 429 -> throw UpdateFailure("update_rate_limit")
            }
            if (!response.isSuccessful) throw UpdateFailure("update_network_error")
            val body = response.body ?: throw UpdateFailure("update_invalid_release")
            val bytes = body.byteStream().use { it.readBytesLimited(1024 * 1024) }
            coroutineContext.ensureActive()
            try { AppRelease.newer(bytes.toString(Charsets.UTF_8), installed) }
            catch (e: UpdateFailure) { throw e }
            catch (_: Exception) { throw UpdateFailure("update_invalid_release") }
        }
    }

    suspend fun download(release: AppRelease, directory: File, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val part = File(directory, "${UUID.randomUUID()}.part")
        val apk = File(directory, part.nameWithoutExtension + ".apk")
        try {
            request(release.apkUrl).use { response ->
                if (!response.isSuccessful) throw UpdateFailure("update_download_error")
                val body = response.body ?: throw UpdateFailure("update_download_error")
                if (body.contentLength() > 0 && body.contentLength() != release.size) throw UpdateFailure("update_corrupt_apk")
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                var reported = -1
                part.outputStream().use { output -> body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        read += count
                        if (read > release.size || read > AppRelease.MAX_APK_BYTES) throw UpdateFailure("update_corrupt_apk")
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                        val percent = (read * 100 / release.size).toInt().coerceAtMost(99)
                        if (percent != reported) { progress(percent); reported = percent }
                    }
                    output.fd.sync()
                } }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (read != release.size || sha != release.sha256) throw UpdateFailure("update_corrupt_apk")
            }
            coroutineContext.ensureActive()
            if (!part.renameTo(apk)) throw IOException("Cannot finish APK download")
            apk
        } catch (e: Exception) { part.delete(); apk.delete(); throw e }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return out.toByteArray()
        if (out.size() + count > limit) throw UpdateFailure("update_invalid_release")
        out.write(buffer, 0, count)
    }
}
