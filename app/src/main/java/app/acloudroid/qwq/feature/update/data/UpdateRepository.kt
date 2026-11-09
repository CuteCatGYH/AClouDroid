package app.acloudroid.qwq.feature.update.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.acloudroid.qwq.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles checking for application updates via GitHub Releases API and launching browser.
 */
class UpdateRepository(
    private val appSettings: AppSettings,
    private val appContext: Context
) {
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    /**
     * Checks for a new GitHub release.
     *
     * @param currentVersion Current version string (e.g., "0.1")
     * @param force If true, bypasses the 24-hour rate limiting check (used for manual check)
     */
    suspend fun checkLatestReleaseIfDue(
        currentVersion: String,
        force: Boolean = false
    ): Result<ReleaseInfo?> {
        val lastCheck = appSettings.lastUpdateCheckTimestamp.value
        val now = System.currentTimeMillis()

        if (!force && now - lastCheck < CHECK_INTERVAL_MS) {
            // Checked within 24 hours: do not hit GitHub API automatically again
            val currentState = _updateState.value
            return if (currentState is UpdateState.UpdateAvailable) {
                Result.success(currentState.releaseInfo)
            } else {
                Result.success(null)
            }
        }

        _updateState.value = UpdateState.Checking

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(GITHUB_RELEASE_API)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", "iClouDroid-App")
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    val json: JSONObject? = if (jsonStr.startsWith("[")) {
                        val jsonArray = org.json.JSONArray(jsonStr)
                        var found: JSONObject? = null
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.optJSONObject(i) ?: continue
                            if (!obj.optBoolean("draft", false)) {
                                found = obj
                                break
                            }
                        }
                        found
                    } else if (jsonStr.startsWith("{")) {
                        JSONObject(jsonStr)
                    } else {
                        null
                    }

                    if (json != null) {
                        val tagName = json.optString("tag_name", "")
                        val releaseNotes = json.optString("body", "暂无更新日志")
                        val htmlUrl = json.optString("html_url", LATEST_RELEASE_PAGE_URL)
                        val publishedAt = json.optString("published_at", "")

                        val cleanVersionName = tagName.removePrefix("v").removePrefix("V")

                        appSettings.setLastUpdateCheckTimestamp(now)

                        if (isNewerVersion(tagName, currentVersion)) {
                            val releaseInfo = ReleaseInfo(
                                tagName = tagName,
                                versionName = cleanVersionName,
                                releaseNotes = releaseNotes,
                                releaseUrl = if (htmlUrl.isNotBlank()) htmlUrl else LATEST_RELEASE_PAGE_URL,
                                publishedAt = publishedAt
                            )
                            _updateState.value = UpdateState.UpdateAvailable(releaseInfo)
                            Result.success(releaseInfo)
                        } else {
                            _updateState.value = UpdateState.UpToDate(currentVersion)
                            Result.success(null)
                        }
                    } else {
                        appSettings.setLastUpdateCheckTimestamp(now)
                        _updateState.value = UpdateState.UpToDate(currentVersion)
                        Result.success(null)
                    }
                } else if (conn.responseCode == HttpURLConnection.HTTP_FORBIDDEN || conn.responseCode == 429) {
                    val msg = "GitHub API 请求过于频繁，请稍后再试"
                    _updateState.value = UpdateState.Error(msg)
                    Result.failure(Exception(msg))
                } else {
                    val msg = "检查更新失败 (HTTP ${conn.responseCode})"
                    _updateState.value = UpdateState.Error(msg)
                    Result.failure(Exception(msg))
                }
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "网络异常，无法连接更新服务器"
                _updateState.value = UpdateState.Error(msg)
                Result.failure(e)
            }
        }
    }

    /**
     * Dismisses the active update prompt for the current session.
     */
    fun dismissUpdatePrompt() {
        if (_updateState.value is UpdateState.UpdateAvailable) {
            _updateState.value = UpdateState.Idle
        }
    }

    /**
     * Opens the GitHub Releases page in the system browser.
     */
    fun openReleasePage(context: Context = appContext, url: String? = null) {
        val targetUrl = if (!url.isNullOrBlank()) url else LATEST_RELEASE_PAGE_URL
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Ignore failure if no browser is available
        }
    }

    companion object {
        const val GITHUB_RELEASE_API = "https://api.github.com/repos/CuteCatGYH/AClouDroid/releases"
        const val LATEST_RELEASE_PAGE_URL = "https://github.com/CuteCatGYH/AClouDroid/releases/latest"
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 Hours

        /**
         * Pure function to compare semantic version strings.
         *
         * e.g., "v0.2" > "0.1" -> true
         *       "v0.1.1" > "0.1" -> true
         *       "0.1" > "0.1" -> false
         */
        fun isNewerVersion(latestTag: String, currentVersionName: String): Boolean {
            val cleanLatest = latestTag.trim()
                .removePrefix("v").removePrefix("V")
                .substringBefore("-") // Ignore build metadata like -debug
            val cleanCurrent = currentVersionName.trim()
                .removePrefix("v").removePrefix("V")
                .substringBefore("-")

            if (cleanLatest.isBlank() || cleanCurrent.isBlank()) return false

            val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

            if (latestParts.isEmpty() || currentParts.isEmpty()) return false

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }

            return false
        }
    }
}
