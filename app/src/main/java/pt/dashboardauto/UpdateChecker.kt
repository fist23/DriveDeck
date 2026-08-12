package pt.dashboardauto

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object UpdateChecker {
    private const val repository = "fist23/DriveDeck"
    private const val latestReleaseUrl = "https://api.github.com/repos/$repository/releases/latest"
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
                connection = (URL(latestReleaseUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "DriveDeck/${BuildConfig.VERSION_NAME}")
                }
                if (connection.responseCode !in 200..299) {
                    result(null)
                    return@execute
                }
                val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
                    result(null)
                    return@execute
                }
                val remoteVersion = release.optString("tag_name").removePrefix("v").trim()
                if (remoteVersion.isBlank() || compareVersions(remoteVersion, BuildConfig.VERSION_NAME) <= 0) {
                    result(null)
                    return@execute
                }
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
                result(UpdateInfo(remoteVersion, release.optString("body"), release.optString("html_url"), apkUrl))
            } catch (_: Exception) {
                result(null)
            } finally {
                connection?.disconnect()
            }
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
