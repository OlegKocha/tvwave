package tv.tvwave.app

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest

class UpdateFileProvider : FileProvider()

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }
data class AppUpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val release: AppRelease? = null,
    val percent: Int = 0, val file: File? = null, val messageKey: String? = null)

class AppUpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val client = AppUpdateClient()
    private val mutable = MutableStateFlow(AppUpdateState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var autoInstallPending = false
    private val directory = File(app.cacheDir, "updates")

    init {
        // APKs are disposable, never mixed with playlists/backups. Remove stale downloads.
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
    }
    /** Forget a negative check on re-entry, but keep a discovered update until checked again. */
    fun beginVisit() {
        if (mutable.value.phase in listOf(UpdatePhase.DOWNLOADING, UpdatePhase.READY)) return
        job?.cancel(); job = null
        autoInstallPending = false
        mutable.value = mutable.value.release?.let { AppUpdateState(UpdatePhase.AVAILABLE, it) } ?: AppUpdateState()
    }
    fun check() {
        if (job?.isActive == true) return
        val previous = mutable.value.release
        mutable.value = AppUpdateState(UpdatePhase.CHECKING, previous)
        job = viewModelScope.launch {
            try {
                val release = client.check(BuildConfig.VERSION_NAME)
                ensureActive()
                mutable.value = AppUpdateState(if (release == null) UpdatePhase.CURRENT else UpdatePhase.AVAILABLE, release)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ensureActive(); mutable.value = AppUpdateState(UpdatePhase.ERROR, previous, messageKey = (e as? UpdateFailure)?.key ?: "update_network_error") }
        }
    }
    fun download() {
        if (job?.isActive == true) return
        val release = mutable.value.release ?: return
        autoInstallPending = false
        mutable.value = AppUpdateState(UpdatePhase.DOWNLOADING, release)
        job = viewModelScope.launch {
            var downloaded: File? = null
            try {
                withContext(Dispatchers.IO) { directory.listFiles()?.forEach { it.delete() } }
                val file = client.download(release, directory) { percent ->
                    mutable.update { current ->
                        if (isActive && current.phase == UpdatePhase.DOWNLOADING && current.release === release) current.copy(percent = percent) else current
                    }
                }
                downloaded = file
                withContext(Dispatchers.IO) { UpdateApk.verify(getApplication(), file, release) }
                ensureActive()
                autoInstallPending = true
                mutable.value = AppUpdateState(UpdatePhase.READY, release, 100, file)
            } catch (e: CancellationException) { downloaded?.delete(); throw e }
            catch (e: Exception) {
                downloaded?.delete()
                ensureActive()
                mutable.value = AppUpdateState(UpdatePhase.ERROR, release, messageKey = (e as? UpdateFailure)?.key ?: "update_download_error")
            }
        }
    }
    fun consumeAutoInstall(): Boolean = autoInstallPending.also { autoInstallPending = false }
    fun cancel() {
        autoInstallPending = false
        job?.cancel(); job = null
        val release = mutable.value.release
        mutable.value = AppUpdateState(if (release == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE, release)
    }
    fun installerMessage(key: String?) { mutable.value = mutable.value.copy(messageKey = key) }
    fun invalidateDownload(key: String) {
        mutable.value.file?.delete()
        mutable.value = AppUpdateState(UpdatePhase.ERROR, mutable.value.release, messageKey = key)
    }
    suspend fun verifiedFile(): File {
        val state = mutable.value
        val file = state.file ?: throw UpdateFailure("update_download_error")
        val release = state.release ?: throw UpdateFailure("update_invalid_release")
        withContext(Dispatchers.IO) { UpdateApk.verify(getApplication(), file, release) }
        return file
    }
}

object UpdateApk {
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, release: AppRelease) {
        if (!file.isFile || file.length() != release.size) throw UpdateFailure("update_corrupt_apk")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != release.sha256) throw UpdateFailure("update_corrupt_apk")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw UpdateFailure("update_corrupt_apk")
        if (archive.packageName != context.packageName) throw UpdateFailure("update_wrong_package")
        fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (code(archive) <= code(installed) || archive.versionName != release.version) throw UpdateFailure("update_wrong_version")
        fun signatures(info: PackageInfo): Set<String> {
            val values = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return values.orEmpty().map { it.toCharsString() }.toSet()
        }
        val expected = signatures(installed)
        if (expected.isEmpty() || signatures(archive) != expected) throw UpdateFailure("update_wrong_signature")
        if (Build.VERSION.SDK_INT >= 24 && (archive.applicationInfo?.minSdkVersion ?: 0) > Build.VERSION.SDK_INT)
            throw UpdateFailure("update_unsupported_android")
    }
}
