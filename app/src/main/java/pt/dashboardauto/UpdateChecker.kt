package pt.dashboardauto

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

object UpdateChecker {
    private const val repository = "fist23/DriveDeck"
    private const val latestReleaseUrl = "https://api.github.com/repos/$repository/releases/latest"
    const val PREFS_NAME = "dashboard_auto"
    const val PENDING_UPDATE_VERSION = "pending_update_version"
    const val PENDING_UPDATE_DOWNLOAD_ID = "pending_update_download_id"
    const val DISMISSED_UPDATE_ALERT_VERSION = "dismissed_update_alert_version"
    const val LAST_UPDATE_STATUS = "last_update_status"
    const val LAST_UPDATE_STATUS_DETAIL = "last_update_status_detail"
    const val UPDATE_INSTALL_COMPLETE_ACTION = "pt.dashboardauto.action.UPDATE_INSTALL_COMPLETE"
    const val TEMP_UPDATE_DIRECTORY = "updates"
    private val executor = Executors.newSingleThreadExecutor()

    data class UpdateInfo(
        val version: String,
        val notes: String,
        val releaseUrl: String,
        val downloadUrl: String?
    )

    fun check(context: Context, result: (UpdateInfo?) -> Unit) {
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val checkUrl = "$latestReleaseUrl?drivedeck_check=${System.currentTimeMillis()}"
                connection = (URL(checkUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "DriveDeck/${BuildConfig.VERSION_NAME}")
                }
                if (connection.responseCode !in 200..299) {
                    setStatus(context, "check_failed", "Não foi possível verificar atualizações agora")
                    result(null)
                    return@execute
                }
                val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
                    result(null)
                    return@execute
                }
                val remoteVersion = release.optString("tag_name").removePrefix("v").trim()
                val currentVersion = installedVersion(context)
                if (remoteVersion.isBlank() || compareVersions(remoteVersion, currentVersion) <= 0) {
                    clearPendingIfInstalled(context, remoteVersion, currentVersion)
                    setStatus(context, "up_to_date", "A app está atualizada")
                    result(null)
                    return@execute
                }
                // Mantém a atualização disponível nas definições mesmo depois de
                // o alerta ter sido dispensado ou o download ter sido iniciado.
                var apkUrl: String? = null
                val assets = release.optJSONArray("assets")
                if (assets != null) {
                    for (index in 0 until assets.length()) {
                        val asset = assets.optJSONObject(index) ?: continue
                        val name = asset.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url").takeIf(String::isNotBlank)
                            break
                        }
                    }
                }
                setStatus(context, "available", "DriveDeck $remoteVersion disponível")
                result(UpdateInfo(remoteVersion, release.optString("body"), release.optString("html_url"), apkUrl))
            } catch (_: Exception) {
                setStatus(context, "check_failed", "Não foi possível verificar atualizações agora")
                result(null)
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun downloadAndInstall(context: Context, info: UpdateInfo, result: (Boolean) -> Unit) {
        val downloadUrl = info.downloadUrl ?: run {
            result(false)
            return
        }
        executor.execute {
            var connection: HttpURLConnection? = null
            val directory = File(context.cacheDir, TEMP_UPDATE_DIRECTORY)
            val partial = File(directory, "drivedeck-${safeFilePart(info.version)}.apk.part")
            val apk = File(directory, "drivedeck-${safeFilePart(info.version)}.apk")
            try {
                cleanupTemporaryDownloads(context)
                if (!directory.exists() && !directory.mkdirs()) throw java.io.IOException("Cannot create update cache")
                setStatus(context, "downloading", "A descarregar DriveDeck ${info.version}")
                connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("User-Agent", "DriveDeck/${BuildConfig.VERSION_NAME}")
                }
                if (connection.responseCode !in 200..299) throw java.io.IOException("Update download failed")
                connection.inputStream.use { input ->
                    partial.outputStream().use { output -> input.copyTo(output, 32 * 1024) }
                }
                if (!partial.isFile || partial.length() <= 0L || !partial.renameTo(apk)) {
                    throw java.io.IOException("Incomplete update download")
                }
                markDownloadStarted(context, info.version, -1L)
                Handler(Looper.getMainLooper()).post {
                    result(UpdateDownloadReceiver.installFromCache(context, apk, info.version))
                }
            } catch (_: Exception) {
                partial.delete()
                apk.delete()
                clearPendingDownload(context)
                cleanupTemporaryDownloads(context)
                setStatus(context, "failed", "Não foi possível descarregar a atualização")
                Handler(Looper.getMainLooper()).post { result(false) }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun safeFilePart(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    @JvmStatic
    fun markDownloadStarted(context: Context, version: String, downloadId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PENDING_UPDATE_VERSION, version)
            .putLong(PENDING_UPDATE_DOWNLOAD_ID, downloadId)
            .apply()
    }

    @JvmStatic
    fun clearPendingDownload(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(PENDING_UPDATE_VERSION)
            .remove(PENDING_UPDATE_DOWNLOAD_ID)
            .apply()
    }

    @JvmStatic
    fun setStatus(context: Context, status: String, detail: String = "") {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(LAST_UPDATE_STATUS, status)
            .putString(LAST_UPDATE_STATUS_DETAIL, detail)
            .apply()
    }

    @JvmStatic
    fun cleanupTemporaryDownloads(context: Context) {
        val directory = File(context.cacheDir, TEMP_UPDATE_DIRECTORY)
        directory.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
        directory.delete()
    }

    private fun installedVersion(context: Context): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
            ?.takeIf(String::isNotBlank) ?: BuildConfig.VERSION_NAME
    } catch (_: Exception) {
        BuildConfig.VERSION_NAME
    }

    private fun clearPendingIfInstalled(context: Context, remote: String, current: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pending = prefs.getString(PENDING_UPDATE_VERSION, "") ?: ""
        if (pending.isNotBlank() && (remote.isBlank() || compareVersions(current, pending) >= 0)) {
            clearPendingDownload(context)
            cleanupTemporaryDownloads(context)
        }
    }

    private fun compareVersions(remote: String, current: String): Int {
        val remoteParts = versionParts(remote)
        val currentParts = versionParts(current)
        for (index in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val remotePart = remoteParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (remotePart != currentPart) return remotePart.compareTo(currentPart)
        }
        return 0
    }

    private fun versionParts(value: String): List<Int> =
        value.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
}
