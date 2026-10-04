package nz.co.mixport.customsvision.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nz.co.mixport.customsvision.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

internal data class AppUpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String,
    val signerSha256: String,
)

internal sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState

    data class Available(
        val release: AppUpdateRelease,
    ) : AppUpdateState

    data class Downloading(
        val release: AppUpdateRelease,
        val percent: Int?,
    ) : AppUpdateState

    data class PermissionRequired(
        val release: AppUpdateRelease,
    ) : AppUpdateState

    data class Failed(
        val release: AppUpdateRelease?,
        val reason: String,
    ) : AppUpdateState
}

internal sealed interface AppUpdateCheckResult {
    data object UpToDate : AppUpdateCheckResult
    data class Available(val release: AppUpdateRelease) : AppUpdateCheckResult
    data object Failed : AppUpdateCheckResult
    data object Skipped : AppUpdateCheckResult
}

internal data class UpdateManifest(
    @SerializedName("schema_version") val schemaVersion: Int,
    @SerializedName("package_name") val packageName: String,
    @SerializedName("version_code") val versionCode: Long,
    @SerializedName("version_name") val versionName: String,
    @SerializedName("apk_download_url") val apkDownloadUrl: String,
    @SerializedName("sha256") val sha256: String,
    @SerializedName("signer_sha256") val signerSha256: String,
)

internal fun validateUpdateManifest(
    manifest: UpdateManifest,
    installedPackageName: String,
    installedVersionCode: Long,
): AppUpdateRelease? {
    require(manifest.schemaVersion == UPDATE_MANIFEST_SCHEMA_VERSION) {
        "Unsupported update manifest schema."
    }
    require(manifest.packageName == installedPackageName) {
        "Update package name does not match this app."
    }
    require(manifest.versionCode > 0L && manifest.versionName.isNotBlank()) {
        "Update version metadata is invalid."
    }
    require(SHA256_PATTERN.matches(manifest.sha256)) {
        "Update checksum is invalid."
    }
    require(SHA256_PATTERN.matches(manifest.signerSha256)) {
        "Update signer fingerprint is invalid."
    }
    require(isAllowedUpdateUrl(manifest.apkDownloadUrl)) {
        "Update download host is not approved."
    }
    if (manifest.versionCode <= installedVersionCode) {
        return null
    }
    return AppUpdateRelease(
        versionCode = manifest.versionCode,
        versionName = manifest.versionName.trim(),
        apkUrl = manifest.apkDownloadUrl,
        apkSha256 = manifest.sha256.lowercase(Locale.US),
        signerSha256 = manifest.signerSha256.lowercase(Locale.US),
    )
}

internal fun isAllowedUpdateUrl(rawUrl: String): Boolean {
    val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
    val host = uri.host?.lowercase(Locale.US) ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.userInfo == null &&
        host in UPDATE_DOWNLOAD_HOSTS
}

internal class AppUpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow<AppUpdateState>(loadCachedState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    suspend fun checkForUpdate(force: Boolean = false): AppUpdateCheckResult = operationMutex.withLock {
        val currentState = _state.value
        if (currentState is AppUpdateState.Downloading) {
            return@withLock AppUpdateCheckResult.Skipped
        }
        val now = System.currentTimeMillis()
        val lastCheckAt = preferences.getLong(KEY_LAST_CHECK_AT, 0L)
        if (!force && now - lastCheckAt < UPDATE_CHECK_INTERVAL_MS) {
            return@withLock AppUpdateCheckResult.Skipped
        }

        _state.value = AppUpdateState.Checking
        return@withLock runCatching {
            withContext(Dispatchers.IO) {
                fetchLatestUpdate()
            }
        }.onSuccess { release ->
            preferences.edit()
                .putLong(KEY_LAST_CHECK_AT, now)
                .apply()
            if (release == null) {
                preferences.edit().remove(KEY_CACHED_RELEASE).apply()
                _state.value = AppUpdateState.Idle
            } else {
                preferences.edit()
                    .putString(KEY_CACHED_RELEASE, gson.toJson(release))
                    .apply()
                _state.value = AppUpdateState.Available(release)
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Update check failed", throwable)
            _state.value = when (currentState) {
                is AppUpdateState.Available -> currentState
                is AppUpdateState.PermissionRequired -> currentState
                else -> AppUpdateState.Idle
            }
        }.fold(
            onSuccess = { release ->
                if (release == null) AppUpdateCheckResult.UpToDate
                else AppUpdateCheckResult.Available(release)
            },
            onFailure = { AppUpdateCheckResult.Failed },
        )
    }

    suspend fun downloadAndInstall(activity: Activity) = operationMutex.withLock {
        val release = when (val currentState = _state.value) {
            is AppUpdateState.Available -> currentState.release
            is AppUpdateState.PermissionRequired -> currentState.release
            is AppUpdateState.Failed -> currentState.release
            else -> null
        } ?: return@withLock

        runCatching {
            val apk = withContext(Dispatchers.IO) {
                val cached = updateApkFile(release)
                if (cached.isFile && runCatching {
                        verifyDownloadedApk(cached, release)
                    }.isSuccess
                ) {
                    cached
                } else {
                    cached.delete()
                    downloadUpdate(release)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                _state.value = AppUpdateState.PermissionRequired(release)
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}"),
                    ),
                )
                return@runCatching
            }
            launchSystemInstaller(activity, apk)
        }.onFailure { throwable ->
            Log.e(TAG, "Unable to prepare app update", throwable)
            _state.value = AppUpdateState.Failed(
                release = release,
                reason = throwable.message ?: "Update verification failed.",
            )
        }
    }

    private fun fetchLatestUpdate(): AppUpdateRelease? {
        val releaseJson = fetchText(GITHUB_LATEST_RELEASE_API, API_RESPONSE_LIMIT_BYTES)
        val githubRelease = gson.fromJson(releaseJson, GithubRelease::class.java)
        val manifestAsset = githubRelease.assets.firstOrNull {
            it.name == UPDATE_MANIFEST_ASSET_NAME
        } ?: return null
        require(isAllowedUpdateUrl(manifestAsset.downloadUrl)) {
            "Update manifest host is not approved."
        }
        val manifestJson = fetchText(manifestAsset.downloadUrl, MANIFEST_LIMIT_BYTES)
        val manifest = gson.fromJson(manifestJson, UpdateManifest::class.java)
        return validateUpdateManifest(
            manifest = manifest,
            installedPackageName = appContext.packageName,
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
        )
    }

    private fun fetchText(rawUrl: String, maxBytes: Int): String {
        val connection = openConnection(rawUrl, "application/vnd.github+json, application/json")
        return connection.useConnection { activeConnection ->
            val bytes = activeConnection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) { "Update metadata is too large." }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun downloadUpdate(release: AppUpdateRelease): File {
        val destination = updateApkFile(release)
        val partial = File(destination.parentFile, "${destination.name}.part")
        destination.parentFile?.mkdirs()
        partial.delete()
        _state.value = AppUpdateState.Downloading(release, percent = 0)

        val connection = openConnection(release.apkUrl, "application/vnd.android.package-archive")
        connection.useConnection { activeConnection ->
            val expectedSize = activeConnection.contentLengthLong.takeIf { it > 0L }
            activeConnection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= MAX_APK_BYTES) { "Update APK is unexpectedly large." }
                        output.write(buffer, 0, read)
                        val percent = expectedSize?.let { size ->
                            ((copied * 100L) / size).toInt().coerceIn(0, 100)
                        }
                        if (percent != null && percent != lastPercent) {
                            lastPercent = percent
                            _state.value = AppUpdateState.Downloading(release, percent)
                        }
                    }
                }
            }
        }

        verifyDownloadedApk(partial, release)
        if (destination.exists()) destination.delete()
        require(partial.renameTo(destination)) { "Unable to finalize downloaded update." }
        _state.value = AppUpdateState.Available(release)
        return destination
    }

    private fun verifyDownloadedApk(file: File, release: AppUpdateRelease) {
        require(file.isFile && file.length() > 0L) { "Downloaded update is empty." }
        require(sha256(file) == release.apkSha256) { "Downloaded update checksum does not match." }

        val archiveInfo = packageInfoForArchive(file)
            ?: error("Downloaded file is not a valid APK.")
        require(archiveInfo.packageName == appContext.packageName) {
            "Downloaded APK has the wrong package name."
        }
        require(packageVersionCode(archiveInfo) == release.versionCode) {
            "Downloaded APK has the wrong version code."
        }
        require(archiveInfo.versionName == release.versionName) {
            "Downloaded APK has the wrong version name."
        }

        val installedInfo = packageInfoForInstalledApp()
        val installedSigners = signerDigests(installedInfo)
        val updateSigners = signerDigests(archiveInfo)
        require(release.signerSha256 in updateSigners) {
            "Downloaded APK signer does not match the release manifest."
        }
        require(installedSigners.any(updateSigners::contains)) {
            "Downloaded APK cannot update the installed signing identity."
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfoForArchive(file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return appContext.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun packageInfoForInstalledApp(): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return appContext.packageManager.getPackageInfo(appContext.packageName, flags)
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return emptySet()
            val current = signingInfo.apkContentsSigners.orEmpty().asList()
            val history = signingInfo.signingCertificateHistory.orEmpty().asList()
            current + history
        } else {
            packageInfo.signatures.orEmpty().asList()
        }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(packageInfo: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }

    private fun launchSystemInstaller(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${BuildConfig.APPLICATION_ID}.updates",
            apk,
        )
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    private fun openConnection(rawUrl: String, accept: String): HttpURLConnection {
        require(isAllowedUpdateUrl(rawUrl)) { "Update URL is not approved." }
        val connection = URL(rawUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "MixportCustomsVision/${BuildConfig.VERSION_NAME}")
        val responseCode = connection.responseCode
        require(responseCode in 200..299) { "Update server returned HTTP $responseCode." }
        require(isAllowedUpdateUrl(connection.url.toString())) {
            "Update redirect host is not approved."
        }
        return connection
    }

    private fun updateApkFile(release: AppUpdateRelease): File {
        return File(File(appContext.cacheDir, UPDATE_CACHE_DIRECTORY), "update-${release.versionCode}.apk")
    }

    private fun loadCachedState(): AppUpdateState {
        val cached = preferences.getString(KEY_CACHED_RELEASE, null) ?: return AppUpdateState.Idle
        val release = runCatching { gson.fromJson(cached, AppUpdateRelease::class.java) }
            .getOrNull()
            ?: return AppUpdateState.Idle
        return if (release.versionCode > BuildConfig.VERSION_CODE.toLong() &&
            isAllowedUpdateUrl(release.apkUrl)
        ) {
            AppUpdateState.Available(release)
        } else {
            AppUpdateState.Idle
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }

    private data class GithubRelease(
        @SerializedName("assets") val assets: List<GithubReleaseAsset> = emptyList(),
    )

    private data class GithubReleaseAsset(
        @SerializedName("name") val name: String = "",
        @SerializedName("browser_download_url") val downloadUrl: String = "",
    )

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/kndhjk/mixport-customs-vision-android/releases/latest"
        private const val UPDATE_MANIFEST_ASSET_NAME = "mixport-update-manifest.json"
        private const val PREFS_NAME = "mixport_app_updates"
        private const val KEY_LAST_CHECK_AT = "last_check_at"
        private const val KEY_CACHED_RELEASE = "cached_release"
        private const val UPDATE_CACHE_DIRECTORY = "updates"
        private const val UPDATE_CHECK_INTERVAL_MS = 15 * 60 * 1_000L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 90_000
        private const val API_RESPONSE_LIMIT_BYTES = 512 * 1024
        private const val MANIFEST_LIMIT_BYTES = 32 * 1024
        private const val MAX_APK_BYTES = 250L * 1024L * 1024L
    }
}

private const val UPDATE_MANIFEST_SCHEMA_VERSION = 1
private val SHA256_PATTERN = Regex("[a-fA-F0-9]{64}")
private val UPDATE_DOWNLOAD_HOSTS = setOf(
    "api.github.com",
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
)
