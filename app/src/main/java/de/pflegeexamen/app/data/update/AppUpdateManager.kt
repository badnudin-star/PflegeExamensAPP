package de.pflegeexamen.app.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface AppUpdateState {
    data object Checking : AppUpdateState
    data object Current : AppUpdateState
    data object Unavailable : AppUpdateState
    data class WaitingForWifi(val version: String, val code: Long) : AppUpdateState
    data class Downloading(val version: String, val code: Long) : AppUpdateState
    data class Ready(val version: String, val code: Long) : AppUpdateState
}

/** No credentials, tracking identifiers or unrestricted URLs. Android still confirms every installation. */
class AppUpdateManager(private val context: Context, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Checking)
    val state: StateFlow<AppUpdateState> = _state
    private val dir = File(context.cacheDir, "app_updates")
    private fun apkFor(code: Long) = File(dir, "update-$code.apk")

    fun start() {
        scope.launch {
            while (isActive) {
                check()
                delay(if (_state.value == AppUpdateState.Unavailable || _state.value is AppUpdateState.WaitingForWifi) 30 * 60_000L else 6 * 60 * 60_000L)
            }
        }
    }
    fun checkNow() { scope.launch { check() } }

    private suspend fun check() = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                _state.value = AppUpdateState.Checking
                val installed = installedInfo()
                val release = fetchRelease()
                if (release == null || release.versionCode <= code(installed)) {
                    _state.value = AppUpdateState.Current
                    return@withContext
                }
                val apk = apkFor(release.versionCode)
                if (validApk(apk, installed, release)) {
                    _state.value = AppUpdateState.Ready(release.versionName, release.versionCode)
                    return@withContext
                }
                val network = context.getSystemService(ConnectivityManager::class.java)
                if (network == null || network.isActiveNetworkMetered) {
                    _state.value = AppUpdateState.WaitingForWifi(release.versionName, release.versionCode)
                    return@withContext
                }
                _state.value = AppUpdateState.Downloading(release.versionName, release.versionCode)
                if (!dir.isDirectory && !dir.mkdirs()) error("cache")
                val temp = File.createTempFile("download-", ".apk", dir)
                try {
                    download(release, temp)
                    if (!validApk(temp, installed, release)) error("invalid apk")
                    if (!temp.renameTo(apk)) error("cache replace")
                } finally { temp.delete() }
                _state.value = AppUpdateState.Ready(release.versionName, release.versionCode)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.value = AppUpdateState.Unavailable }
        }
    }

    /** Must be called from an explicit user action. Never uninstalls the app. */
    suspend fun install(): InstallResult = lock.withLock {
        val ready = _state.value as? AppUpdateState.Ready ?: return@withLock InstallResult.Unavailable
        val apk = apkFor(ready.code)
        val verified = withContext(Dispatchers.IO) {
            runCatching {
                val installed = installedInfo()
                val candidate = archiveInfo(apk) ?: return@runCatching false
                UpdateTrust.accepts(context.packageName, code(installed), signatures(installed),
                    candidate.packageName, code(candidate), signatures(candidate), ready.code)
            }.getOrDefault(false)
        }
        if (!verified) return@withLock InstallResult.Unavailable
        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                try {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    InstallResult.PermissionNeeded
                } catch (_: Exception) { InstallResult.Unavailable }
            } else {
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    InstallResult.Started
                } catch (_: Exception) { InstallResult.Unavailable }
            }
        }
    }

    private fun validApk(file: File, installed: PackageInfo, release: ReleaseAsset): Boolean = runCatching {
        if (!file.isFile || file.length() != release.size) return false
        val archive = archiveInfo(file) ?: return false
        UpdateTrust.accepts(context.packageName, code(installed), signatures(installed), archive.packageName,
            code(archive), signatures(archive), release.versionCode)
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun flags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    private fun installedInfo(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags())
    private fun archiveInfo(file: File): PackageInfo? = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags())
    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signers.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.toSet()
    }

    private fun fetchRelease(): ReleaseAsset? {
        val connection = URL(ReleaseMetadata.API_URL).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "PflegeExamen-Android")
            if (connection.responseCode == 404) return null
            if (connection.responseCode != 200) error("metadata unavailable")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576) error("metadata too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return ReleaseMetadata.parse(bytes.toString(Charsets.UTF_8)) ?: error("invalid metadata")
        } finally { connection.disconnect() }
    }

    private fun download(release: ReleaseAsset, target: File) {
        var url = release.url
        repeat(6) {
            if (!ReleaseMetadata.allowedDownloadUrl(url)) error("invalid redirect")
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000; connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "PflegeExamen-Android")
                when (connection.responseCode) {
                    in 300..399 -> {
                        val location = connection.getHeaderField("Location") ?: error("redirect missing")
                        url = URL(URL(url), location).toString()
                    }
                    200 -> {
                        var count = 0L
                        connection.inputStream.use { input -> target.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                if (count > release.size || count > ReleaseMetadata.MAX_APK) error("download too large")
                                output.write(buffer, 0, n)
                            }
                        } }
                        if (count != release.size) error("download incomplete")
                        return
                    }
                    else -> error("download failed")
                }
            } finally { connection.disconnect() }
        }
        error("too many redirects")
    }
}

enum class InstallResult { Started, PermissionNeeded, Unavailable }
