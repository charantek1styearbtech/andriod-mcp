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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

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
                val checkBase = if (!baseUrl.isNullOrBlank()) {
                    val trimmed = baseUrl.trim().trimEnd('/')
                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                        trimmed
                    } else if (trimmed.startsWith("ws://")) {
                        "http://" + trimmed.removePrefix("ws://")
                    } else if (trimmed.startsWith("wss://")) {
                        "https://" + trimmed.removePrefix("wss://")
                    } else {
                        "https://$trimmed"
                    }
                } else {
                    DEFAULT_CHECK_URL.substringBefore("/api/update/check")
                }

                val url = "$checkBase/api/update/check?currentVersionCode=$currentCode"

                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val msg = "Server error ${response.code}"
                    if (!silent) _uiState.value = UpdateUiState.Error(msg)
                    return@launch
                }

                val bodyStr = response.body?.string().orEmpty()
                val json = JSONObject(bodyStr)

                val updateAvailable = json.optBoolean("updateAvailable", false)
                val latestCode = json.optInt("latestVersionCode", currentCode)
                val latestName = json.optString("latestVersionName", "1.0.0")
                var downloadUrl = json.optString("downloadUrl", "")
                if (downloadUrl.startsWith("/")) {
                    downloadUrl = "$checkBase$downloadUrl"
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

                Log.i(TAG, "Downloading APK from: ${updateInfo.downloadUrl} to ${destinationFile.absolutePath}")

                val request = Request.Builder().url(updateInfo.downloadUrl).build()
                val response = httpClient.newCall(request).execute()

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
