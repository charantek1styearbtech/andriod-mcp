package com.agent.androidmcp.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val updateAvailable: Boolean,
    val latestVersionCode: Int,
    val latestVersionName: String,
    val downloadUrl: String,
    val apkSize: Long,
    val changelog: String,
    val publishedAt: String
)

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data class Available(val info: UpdateInfo) : UpdateUiState()
    data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : UpdateUiState()
    data class ReadyToInstall(val apkFile: File) : UpdateUiState()
    data class UpToDate(val currentVersion: String) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val DEFAULT_CHECK_URL = "https://andriod-mcp-gateway.onrender.com/api/update/check"

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Extracts the HTTP/HTTPS origin (scheme + host + port) from any given URL or WebSocket URL.
     * E.g.: "wss://andriod-mcp-gateway.onrender.com/device/ws" -> "https://andriod-mcp-gateway.onrender.com"
     *       "ws://192.168.1.6:8080/device/ws" -> "http://192.168.1.6:8080"
     */
    fun resolveHttpBaseUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) {
            return "https://andriod-mcp-gateway.onrender.com"
        }
        val clean = rawUrl.trim()
        val isSecure = clean.startsWith("wss://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)
        val scheme = if (isSecure) "https" else "http"
        val withoutScheme = clean.replaceFirst(Regex("^(wss?|https?)://", RegexOption.IGNORE_CASE), "")
        val hostAndPort = withoutScheme.substringBefore('/')
        return if (hostAndPort.isNotBlank()) {
            "$scheme://$hostAndPort"
        } else {
            "https://andriod-mcp-gateway.onrender.com"
        }
    }

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    fun checkForUpdate(context: Context, baseUrl: String? = null, silent: Boolean = false) {
        if (_uiState.value is UpdateUiState.Downloading) return

        _uiState.value = UpdateUiState.Checking

        scope.launch {
            try {
                val currentCode = getCurrentVersionCode(context)
                val checkBase = resolveHttpBaseUrl(baseUrl)
                val primaryUrl = "$checkBase/api/update/check?currentVersionCode=$currentCode"
                Log.i(TAG, "Checking for updates at primary URL: $primaryUrl")

                var responseString: String? = null
                var effectiveOrigin = checkBase

                // 1. Try primary gateway endpoint
                try {
                    val request = Request.Builder().url(primaryUrl).build()
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        responseString = response.body?.string().orEmpty()
                    } else {
                        Log.w(TAG, "Primary update server returned HTTP ${response.code} ($primaryUrl)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Primary update server connection failed ($primaryUrl): ${e.message}")
                }

                // 2. If primary failed (e.g. Render 404/asleep), try GitHub Raw release metadata as fallback
                if (responseString.isNullOrBlank()) {
                    val githubFallbackUrl = "https://raw.githubusercontent.com/charantek1styearbtech/andriod-mcp/main/version.json"
                    Log.i(TAG, "Attempting GitHub fallback update check: $githubFallbackUrl")
                    try {
                        val ghReq = Request.Builder().url(githubFallbackUrl).build()
                        val ghResp = httpClient.newCall(ghReq).execute()
                        if (ghResp.isSuccessful) {
                            val ghBody = ghResp.body?.string().orEmpty()
                            val ghJson = JSONObject(ghBody)
                            val latestCode = ghJson.optInt("versionCode", currentCode)
                            val isUpdateAvailable = latestCode > currentCode
                            
                            // Synthesize standard checkUpdate response
                            val synthesized = JSONObject().apply {
                                put("updateAvailable", isUpdateAvailable)
                                put("latestVersionCode", latestCode)
                                put("latestVersionName", ghJson.optString("versionName", "1.1.0"))
                                put("downloadUrl", ghJson.optString("downloadUrl", "$checkBase/api/update/download"))
                                put("apkSize", ghJson.optLong("apkSize", 0L))
                                put("changelog", ghJson.optString("changelog", "Bug fixes and performance improvements."))
                                put("publishedAt", ghJson.optString("publishedAt", ""))
                            }
                            responseString = synthesized.toString()
                            effectiveOrigin = checkBase
                            Log.i(TAG, "Successfully retrieved update info from GitHub fallback!")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "GitHub fallback check failed: ${e.message}")
                    }
                }

                if (responseString.isNullOrBlank()) {
                    val msg = "Could not connect to update server ($checkBase). Check network or server status."
                    if (!silent) _uiState.value = UpdateUiState.Error(msg)
                    return@launch
                }

                val json = JSONObject(responseString)
                val updateAvailable = json.optBoolean("updateAvailable", false)
                val latestCode = json.optInt("latestVersionCode", currentCode)
                val latestName = json.optString("latestVersionName", "1.0.0")
                var downloadUrl = json.optString("downloadUrl", "")
                if (downloadUrl.startsWith("/")) {
                    downloadUrl = "$effectiveOrigin$downloadUrl"
                }
                val apkSize = json.optLong("apkSize", 0L)
                val changelog = json.optString("changelog", "Bug fixes and performance improvements.")
                val publishedAt = json.optString("publishedAt", "")

                val info = UpdateInfo(
                    updateAvailable = updateAvailable,
                    latestVersionCode = latestCode,
                    latestVersionName = latestName,
                    downloadUrl = downloadUrl,
                    apkSize = apkSize,
                    changelog = changelog,
                    publishedAt = publishedAt
                )

                if (updateAvailable) {
                    Log.i(TAG, "Update available: v$latestName (Build $latestCode)")
                    _uiState.value = UpdateUiState.Available(info)
                } else {
                    val currentName = getCurrentVersionName(context)
                    Log.i(TAG, "App is up to date: v$currentName")
                    if (!silent) {
                        _uiState.value = UpdateUiState.UpToDate("v$currentName (Build $currentCode)")
                    } else {
                        _uiState.value = UpdateUiState.Idle
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates: ${e.message}", e)
                if (!silent) {
                    _uiState.value = UpdateUiState.Error(e.message ?: "Failed to connect to update server")
                }
            }
        }
    }

    fun startDownload(context: Context, updateInfo: UpdateInfo) {
        if (_uiState.value is UpdateUiState.Downloading) return

        _uiState.value = UpdateUiState.Downloading(0, 0L, updateInfo.apkSize)

        scope.launch {
            try {
                val updatesDir = File(context.cacheDir, "updates")
                if (!updatesDir.exists()) {
                    updatesDir.mkdirs()
                }

                val destinationFile = File(updatesDir, "android-agent-update.apk")
                if (destinationFile.exists()) {
                    destinationFile.delete()
                }

                var effectiveUrl = updateInfo.downloadUrl
                Log.i(TAG, "Downloading APK from: $effectiveUrl to ${destinationFile.absolutePath}")

                var request = Request.Builder().url(effectiveUrl).build()
                var response = httpClient.newCall(request).execute()

                // If primary download URL fails or returns 404, fallback to GitHub Release CDN
                if (!response.isSuccessful && !effectiveUrl.contains("github.com")) {
                    val ghDownloadUrl = "https://github.com/charantek1styearbtech/andriod-mcp/releases/download/v${updateInfo.latestVersionName}/app-debug.apk"
                    Log.i(TAG, "Primary download returned HTTP ${response.code}. Falling back to GitHub release: $ghDownloadUrl")
                    request = Request.Builder().url(ghDownloadUrl).build()
                    response = httpClient.newCall(request).execute()
                }

                if (!response.isSuccessful) {
                    _uiState.value = UpdateUiState.Error("Download failed with HTTP ${response.code}")
                    return@launch
                }

                val responseBody = response.body
                if (responseBody == null) {
                    _uiState.value = UpdateUiState.Error("Empty response body from update server")
                    return@launch
                }

                val totalBytes = if (responseBody.contentLength() > 0) responseBody.contentLength() else updateInfo.apkSize
                var downloadedBytes = 0L

                responseBody.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        var lastPercent = 0

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read

                            val percent = if (totalBytes > 0) {
                                ((downloadedBytes * 100) / totalBytes).toInt()
                            } else {
                                0
                            }

                            if (percent != lastPercent) {
                                lastPercent = percent
                                _uiState.value = UpdateUiState.Downloading(percent, downloadedBytes, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }

                Log.i(TAG, "Download finished (${destinationFile.length()} bytes). Prompting installer...")
                _uiState.value = UpdateUiState.ReadyToInstall(destinationFile)

                // Trigger package installation prompt
                withContext(Dispatchers.Main) {
                    installApk(context, destinationFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error downloading update: ${e.message}", e)
                _uiState.value = UpdateUiState.Error("Download error: ${e.message}")
            }
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                _uiState.value = UpdateUiState.Error("APK file not found on disk")
                return
            }

            // Check unknown source install permission on Android 8.0+ (Oreo)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Log.w(TAG, "App cannot request package installs. Redirecting to Settings...")
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            Log.i(TAG, "Launching Package Installer for URI: $apkUri")

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            _uiState.value = UpdateUiState.Error("Installer error: ${e.message}")
        }
    }

    fun dismissState() {
        _uiState.value = UpdateUiState.Idle
    }
}
