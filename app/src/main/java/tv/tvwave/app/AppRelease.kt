package tv.tvwave.app

import org.json.JSONObject
import java.math.BigInteger

class UpdateFailure(val key: String) : Exception(key)

/** Stable release tags only. Compare components numerically, never as strings. */
data class AppVersion(private val parts: List<BigInteger>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val result = (parts.getOrNull(i) ?: BigInteger.ZERO).compareTo(other.parts.getOrNull(i) ?: BigInteger.ZERO)
            if (result != 0) return result
        }
        return 0
    }
    companion object {
        fun parse(value: String): AppVersion? = value.removePrefix("v").takeIf {
            it.length <= 64 && it.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}"))
        }?.split('.')?.map { it.toBigInteger() }?.let(::AppVersion)
    }
}

data class AppRelease(val version: String, val notes: String, val apkUrl: String, val size: Long, val sha256: String) {
    fun localizedNotes(language: String): String {
        val sections = notes.split(Regex("(?m)^## +"))
        val label = if (language == "ru") "Русский" else "English"
        val selected = sections.firstOrNull { it.lineSequence().first().trim().equals(label, true) }
            ?: sections.firstOrNull { it.lineSequence().first().trim().equals("English", true) }
        val text = selected?.substringAfter('\n', "") ?: notes
        return text.replace(Regex("\\[([^]]+)\\]\\([^)]+\\)"), "$1")
            .replace("`", "").replace("**", "").replace(Regex("(?m)^#{1,6} +"), "")
            .trim().take(12000)
    }

    companion object {
        const val API = "https://api.github.com/repos/OlegKocha/tvwave/releases/latest"
        const val MAX_APK_BYTES = 128L * 1024 * 1024

        fun newer(json: String, installed: String): AppRelease? {
            val root = JSONObject(json)
            if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
            val tag = root.getString("tag_name")
            val version = AppVersion.parse(tag) ?: throw UpdateFailure("update_invalid_release")
            val current = AppVersion.parse(installed) ?: throw UpdateFailure("update_invalid_release")
            if (version <= current) return null
            val assets = root.getJSONArray("assets")
            val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == "tvwave.apk" }
            val asset = matches.singleOrNull() ?: throw UpdateFailure("update_missing_apk")
            val url = asset.getString("browser_download_url")
            val digest = asset.optString("digest").lowercase()
            val size = asset.getLong("size")
            if (asset.optString("state") != "uploaded" || size !in 1..MAX_APK_BYTES ||
                url != "https://github.com/OlegKocha/tvwave/releases/download/$tag/tvwave.apk" ||
                !digest.matches(Regex("sha256:[a-f0-9]{64}"))) throw UpdateFailure("update_invalid_release")
            return AppRelease(tag.removePrefix("v"), root.optString("body"), url, size, digest.removePrefix("sha256:"))
        }
    }
}
