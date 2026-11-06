package tv.tvwave.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** An installer result is not proof of an update. Compare with the running package instead. */
object UpdateCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal data class Apk(val packageName: String, val versionCode: Long)

    fun cleanAsync(context: Context, finished: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            try { cleanInstalled(app) }
            catch (e: Exception) { android.util.Log.w("TVWaveUpdate", "APK cleanup deferred", e) }
            finally { finished() }
        }
    }

    @Suppress("DEPRECATION")
    @Synchronized
    internal fun cleanInstalled(context: Context): Int = clean(
        File(context.cacheDir, "updates"), context.packageName, BuildConfig.VERSION_CODE.toLong()
    ) { file ->
        context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.let {
            Apk(it.packageName, if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong())
        }
    }

    internal fun clean(directory: File, packageName: String, installedCode: Long, inspect: (File) -> Apk?): Int {
        return directory.listFiles().orEmpty().count { file ->
            // Never remove an in-progress download, another cache, or a newer APK after cancellation.
            if (!file.isFile || file.extension != "apk") return@count false
            val apk = runCatching { inspect(file) }.getOrNull() ?: return@count false
            apk.packageName == packageName && apk.versionCode in 1..installedCode && file.delete()
        }
    }
}

class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        UpdateCache.cleanAsync(context) { pending.finish() }
    }
}
