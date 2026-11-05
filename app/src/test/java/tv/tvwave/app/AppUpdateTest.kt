package tv.tvwave.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class AppUpdateTest {
    private val bytes = "synthetic APK bytes".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun metadata(tag: String = "v0.7.5") = JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)
        .put("body", "## Русский\nИсправление звука\n## English\nAudio fix")
        .put("assets", JSONArray().put(JSONObject().put("name", "tvwave.apk").put("state", "uploaded")
            .put("size", bytes.size).put("digest", "sha256:$sha")
            .put("browser_download_url", "https://github.com/OlegKocha/tvwave/releases/download/$tag/tvwave.apk")))
    private fun client(code: Int = 200, response: ByteArray = bytes) = AppUpdateClient(OkHttpClient.Builder()
        .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("Fixture").body(response.toResponseBody()).build() }.build())
    private fun failure(key: String, block: () -> Unit) {
        try { block(); fail("Expected $key") } catch (e: UpdateFailure) { assertEquals(key, e.key) }
    }

    @Test fun stableVersionComparisonNeverOffersEqualOrOlderRelease() {
        assertTrue(AppVersion.parse("v0.7.10")!! > AppVersion.parse("0.7.9")!!)
        assertEquals(0, AppVersion.parse("1.2")!!.compareTo(AppVersion.parse("1.2.0")!!))
        assertNull(AppVersion.parse("0.7.5-beta"))
        assertNull(AppRelease.newer(metadata("v0.7.3").toString(), "0.7.4"))
        assertNull(AppRelease.newer(metadata("v0.7.4").toString(), "0.7.4"))
        assertEquals("0.7.5", AppRelease.newer(metadata().toString(), "0.7.4")!!.version)
        assertNull(AppRelease.newer(metadata().put("draft", true).toString(), "0.7.4"))
        assertNull(AppRelease.newer(metadata().put("prerelease", true).toString(), "0.7.4"))
    }
    @Test fun rejectMissingApkForeignDownloadUrlAndUnverifiedRelease() {
        failure("update_missing_apk") { AppRelease.newer(metadata().put("assets", JSONArray()).toString(), "0.7.4") }
        for ((key, value) in listOf("browser_download_url" to "https://example.invalid/tvwave.apk", "digest" to "", "size" to -1, "state" to "new")) {
            val json = metadata().apply { getJSONArray("assets").getJSONObject(0).put(key, value) }
            failure("update_invalid_release") { AppRelease.newer(json.toString(), "0.7.4") }
        }
    }
    @Test fun releaseNotesUseTheSelectedLanguage() {
        val release = AppRelease.newer(metadata().toString(), "0.7.4")!!
        assertEquals("Исправление звука", release.localizedNotes("ru"))
        assertEquals("Audio fix", release.localizedNotes("en"))
        assertEquals("Audio fix", release.localizedNotes("de"))
    }
    @Test fun localizedReleaseNotesKeepCategoryHeadingsAndMetadata() {
        val json=metadata().put("published_at","2026-11-04T10:00:00Z")
            .put("body","## Русский\n### Новое\n- Первая функция\n## Улучшено\n- Работа пульта\n## English\n### New\n- First feature\n## Improved\n- Remote navigation")
        val release=AppRelease.newer(json.toString(),"0.7.4")!!
        assertEquals("2026-11-04T10:00:00Z",release.publishedAt)
        assertTrue(release.localizedNotes("ru").contains("Улучшено\n- Работа пульта"))
        assertFalse(release.localizedNotes("ru").contains("Remote navigation"))
        assertTrue(release.localizedNotes("en").contains("Improved\n- Remote navigation"))
        assertFalse(release.localizedNotes("en").contains("Первая функция"))
    }
    @Test fun metadataErrorsDoNotPretendTheAppIsUpToDate() {
        failure("update_rate_limit") { runBlocking { client(403).check("0.7.4") } }
        failure("update_rate_limit") { runBlocking { client(429).check("0.7.4") } }
        failure("update_no_releases") { runBlocking { client(404).check("0.7.4") } }
        failure("update_invalid_release") { runBlocking { client(response = "<html>error</html>".toByteArray()).check("0.7.4") } }
        assertNull(runBlocking { client(response = metadata("v0.7.3").toString().toByteArray()).check("0.7.4") })
    }
    @Test fun successfulDownloadVerifiesSizeAndDigest() = runBlocking {
        val directory = Files.createTempDirectory("update-test").toFile()
        try {
            val values = mutableListOf<Int>()
            val release = AppRelease.newer(metadata().toString(), "0.7.4")!!
            val file = client().download(release, directory) { values.add(it) }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals("apk", file.extension)
            assertTrue(values.all { it in 0..99 })
            assertFalse(directory.listFiles()!!.any { it.extension == "part" })
        } finally { directory.deleteRecursively() }
    }
    @Test fun corruptOrTruncatedDownloadIsRemoved() {
        val directory = Files.createTempDirectory("update-test").toFile()
        try {
            val release = AppRelease.newer(metadata().toString(), "0.7.4")!!
            for (response in listOf(ByteArray(bytes.size) { 1 }, bytes.dropLast(1).toByteArray(), bytes + byteArrayOf(1))) {
                failure("update_corrupt_apk") { runBlocking { client(response = response).download(release, directory) {} } }
                assertTrue(directory.listFiles()!!.isEmpty())
            }
            failure("update_download_error") { runBlocking { client(500).download(release, directory) {} } }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun cancellingDownloadRemovesPartialFile() = runBlocking {
        val directory = Files.createTempDirectory("update-cancel").toFile()
        val payload = ByteArray(1024 * 1024)
        try {
            val release = AppRelease.newer(metadata().toString(), "0.7.4")!!.copy(size = payload.size.toLong())
            val job = launch { client(response = payload).download(release, directory) { cancel() } }
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
