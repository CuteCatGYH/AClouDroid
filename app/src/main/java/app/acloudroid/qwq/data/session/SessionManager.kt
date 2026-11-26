package app.acloudroid.qwq.data.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SessionManager(context: Context) {

    /** Shared mutex across all feature repositories to serialize Go core session initialization. */
    val sessionMutex = kotlinx.coroutines.sync.Mutex()

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

            EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.values()[0],
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to standard SharedPreferences if KeyStore fails on custom ROMs/emulators
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun saveSession(
        cookieStr: String,
        isChinaMainland: Boolean,
        dsid: String? = null,
        ckDatabaseUrl: String? = null
    ) {
        prefs.edit().apply {
            putString(KEY_COOKIE_STR, cookieStr)
            putBoolean(KEY_IS_CHINA, isChinaMainland)
            putString(KEY_DSID, dsid)
            putString(KEY_CK_DB_URL, ckDatabaseUrl)
            putBoolean(KEY_IS_LOGGED_IN, true)
            apply()
        }
    }

    fun setChinaMainland(isChinaMainland: Boolean) {
        prefs.edit().putBoolean(KEY_IS_CHINA, isChinaMainland).apply()
    }

    fun updateRouteInfo(dsid: String?, ckDatabaseUrl: String?) {
        prefs.edit().apply {
            putString(KEY_DSID, dsid)
            putString(KEY_CK_DB_URL, ckDatabaseUrl)
            apply()
        }
    }

    fun clearSession(context: Context? = null) {
        val currentRegion = isChinaMainland()
        val anisetteUrl = getNativeAnisetteUrl()
        prefs.edit().clear().apply()
        setChinaMainland(currentRegion)
        // A device setting, not an account credential: keep it so a re-login does not have to
        // retype it. The native credentials themselves are gone with the clear() above.
        setNativeAnisetteUrl(anisetteUrl)
        // ...and the Go core's in-memory copy must go too, or a large upload queued for the new
        // account would still be sent with the old Apple ID (AGENTS §5.5).
        runCatching { com.getup.icloudcore.Icloudcore.nativeLogout() }
        cachedSessionGeneration = null
        if (context != null) {
            clearAvatarCache(context)
        }
    }

    /**
     * Native ("大文件上传") login blob returned by the Go core. Holds dsid + mmeAuthToken, never the
     * password. Lives in this encrypted store so [clearSession] wipes it with the web session:
     * it belongs to one Apple ID (AGENTS §5.5).
     */
    fun getNativeCredentials(): String? = prefs.getString(KEY_NATIVE_CREDENTIALS, null)

    fun saveNativeCredentials(json: String?) {
        prefs.edit().apply {
            if (json.isNullOrBlank()) remove(KEY_NATIVE_CREDENTIALS) else putString(KEY_NATIVE_CREDENTIALS, json)
            apply()
        }
    }

    /** Whether the user accepted the public-anisette disclaimer. Wiped with the session on purpose. */
    fun hasPublicAnisetteConsent(): Boolean = prefs.getBoolean(KEY_NATIVE_PUBLIC_CONSENT, false)

    fun setPublicAnisetteConsent(accepted: Boolean) {
        prefs.edit().putBoolean(KEY_NATIVE_PUBLIC_CONSENT, accepted).apply()
    }

    /** Where device fingerprints come from (public server by default, or scripts/anisette_server). */
    fun getNativeAnisetteUrl(): String =
        prefs.getString(KEY_NATIVE_ANISETTE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ANISETTE_URL

    fun setNativeAnisetteUrl(url: String) {
        prefs.edit().putString(KEY_NATIVE_ANISETTE_URL, url.trim()).apply()
    }

    fun getCookieString(): String? = prefs.getString(KEY_COOKIE_STR, null)

    @Volatile
    private var invalidatedTokens: Set<String> = emptySet()

    /**
     * Marks a specific `X-APPLE-WEBAUTH-TOKEN` value as dead (rejected by server with AUTH_EXPIRED / 401).
     * Once marked, [isTokenInvalidated] will return true for this token value, preventing
     * [app.acloudroid.qwq.ui.auth.CookieValidationResult.validate] from treating it as valid.
     */
    fun invalidateToken(tokenVal: String?) {
        if (!tokenVal.isNullOrBlank()) {
            val clean = tokenVal.trim().trim('"')
            if (clean.isNotBlank()) {
                invalidatedTokens = invalidatedTokens + clean
                android.util.Log.i("SessionManager", "Invalidated expired token (len=${clean.length})")
            }
        }
    }

    /**
     * Checks if a given `X-APPLE-WEBAUTH-TOKEN` value is known to be dead/rejected by Apple server.
     */
    fun isTokenInvalidated(tokenVal: String?): Boolean {
        if (tokenVal.isNullOrBlank()) return false
        val clean = tokenVal.trim().trim('"')
        return invalidatedTokens.contains(clean)
    }

    /**
     * Extracts the raw `X-APPLE-WEBAUTH-TOKEN` value from a cookie string.
     */
    fun extractAuthTokenValue(cookieStr: String?): String? {
        if (cookieStr.isNullOrBlank()) return null
        return cookieStr.split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("X-APPLE-WEBAUTH-TOKEN=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
    }

    /**
     * Attempts to read live cookies from Android's system [android.webkit.CookieManager] for the current region.
     *
     * If [android.webkit.CookieManager] contains valid credentials (`isValid == true`), this automatically
     * updates [SessionManager] if the stored cookie set differed, and returns the fresh cookie string.
     * Returns null if no fresh valid cookie string could be retrieved.
     */
    fun trySyncFreshCookiesFromWeb(): String? {
        val targetUrl = if (isChinaMainland()) "https://www.icloud.com.cn" else "https://www.icloud.com"
        val rawWebCookie = try {
            android.webkit.CookieManager.getInstance().getCookie(targetUrl)
        } catch (e: Exception) {
            android.util.Log.w("SessionManager", "Failed to query CookieManager: ${e.message}")
            null
        }

        if (rawWebCookie.isNullOrBlank()) return null

        val validation = app.acloudroid.qwq.ui.auth.CookieValidationResult.validate(rawWebCookie, this)
        if (!validation.isValid) return null

        val currentStored = getCookieString()
        if (currentStored != rawWebCookie) {
            android.util.Log.i(
                "SessionManager",
                "Auto-synced fresh cookies from WebView CookieManager (region=${if (isChinaMainland()) "cn" else "global"})"
            )
            saveSession(rawWebCookie, isChinaMainland())
        }

        return rawWebCookie
    }

    fun isChinaMainland(): Boolean = prefs.getBoolean(KEY_IS_CHINA, true)

    fun hasShownChinaUploadNotice(): Boolean = prefs.getBoolean(KEY_HAS_SHOWN_CHINA_UPLOAD_NOTICE, false)

    fun setHasShownChinaUploadNotice(shown: Boolean = true) {
        prefs.edit().putBoolean(KEY_HAS_SHOWN_CHINA_UPLOAD_NOTICE, shown).apply()
    }

    fun saveUserAvatarUrl(url: String?, context: Context) {
        if (url.isNullOrBlank()) return
        val clean = url.trim()

        prefs.edit().apply {
            putString(KEY_USER_AVATAR_URL, clean)
            putLong(KEY_AVATAR_LAST_UPDATED, System.currentTimeMillis())
            apply()
        }

        downloadAndCacheAvatarImage(context, clean)
    }

    fun getUserAvatarUrl(): String? = prefs.getString(KEY_USER_AVATAR_URL, null)

    fun getAvatarFile(context: Context): java.io.File {
        return java.io.File(context.cacheDir, "user_avatar_cache.png")
    }

    fun isAvatarExpired(context: Context, maxAgeMs: Long = 30L * 24 * 3600 * 1000): Boolean {
        val lastUpdated = prefs.getLong(KEY_AVATAR_LAST_UPDATED, 0L)
        val file = getAvatarFile(context)
        if (!file.exists() || file.length() <= 500) return true
        return (System.currentTimeMillis() - lastUpdated) > maxAgeMs
    }

    fun downloadAndCacheAvatarImage(context: Context, imageUrl: String) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val targetFile = getAvatarFile(context)
                val tempFile = java.io.File(context.cacheDir, "user_avatar_cache.tmp")

                val cookieStr = getCookieString()
                val isChina = isChinaMainland()
                val origin = if (isChina) "https://www.icloud.com.cn" else "https://www.icloud.com"

                val reqBuilder = okhttp3.Request.Builder()
                    .url(imageUrl)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                    .header("Origin", origin)
                    .header("Referer", "$origin/")

                if (!cookieStr.isNullOrBlank()) {
                    reqBuilder.header("Cookie", cookieStr)
                }

                val client = okhttp3.OkHttpClient()
                val resp = client.newCall(reqBuilder.build()).execute()
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.size > 200) {
                        val isImage = (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) ||
                                      (bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) ||
                                      (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte())
                        if (isImage) {
                            tempFile.writeBytes(bytes)
                            if (targetFile.exists()) targetFile.delete()
                            tempFile.renameTo(targetFile)
                            android.util.Log.i("SessionManager", "Successfully downloaded avatar image to local cache (${targetFile.length()} bytes)")
                        } else {
                            android.util.Log.w("SessionManager", "Avatar response was not an image (head=${bytes.take(8).joinToString("") { "%02x".format(it) }})")
                        }
                    }
                } else {
                    android.util.Log.w("SessionManager", "Avatar download HTTP error: ${resp.code}")
                }
            } catch (e: Exception) {
                android.util.Log.w("SessionManager", "Failed to cache avatar image locally: ${e.message}")
            }
        }
    }

    fun getLocalAvatarPath(context: Context): String? {
        val file = getAvatarFile(context)
        return if (file.exists() && file.length() > 500) file.absolutePath else null
    }

    fun clearAvatarCache(context: Context) {
        prefs.edit().remove(KEY_USER_AVATAR_URL).remove(KEY_AVATAR_LAST_UPDATED).apply()
        val file = getAvatarFile(context)
        if (file.exists()) file.delete()
    }

    fun getDsid(): String? = prefs.getString(KEY_DSID, null)

    fun getCkDatabaseUrl(): String? = prefs.getString(KEY_CK_DB_URL, null)

    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_IS_LOGGED_IN, false) && !getCookieString().isNullOrBlank()

    @Volatile
    private var cachedSessionGeneration: Int? = null

    /**
     * Monotonic counter of the Go core session generation.
     *
     * Every rebuild of the Go-side [Session] mints new CDN signatures, which silently
     * invalidates every previously issued `cvws.icloud-content.com` download URL. Rows stamped
     * with an older generation are therefore known-stale and must be refreshed before use.
     */
    fun getSessionGeneration(): Int {
        val cached = cachedSessionGeneration
        if (cached != null) return cached
        val gen = prefs.getInt(KEY_SESSION_GENERATION, 0)
        cachedSessionGeneration = gen
        return gen
    }

    /** Increments and persists the session generation. Returns the new value. */
    fun bumpSessionGeneration(): Int {
        val next = getSessionGeneration() + 1
        prefs.edit().putInt(KEY_SESSION_GENERATION, next).apply()
        cachedSessionGeneration = next
        return next
    }

    companion object {
        private const val PREFS_NAME = "acloudroid_encrypted_session"
        private const val KEY_COOKIE_STR = "cookie_string"
        private const val KEY_IS_CHINA = "is_china_mainland"
        private const val KEY_DSID = "dsid"
        private const val KEY_CK_DB_URL = "ck_database_url"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_SESSION_GENERATION = "session_generation"
        private const val KEY_HAS_SHOWN_CHINA_UPLOAD_NOTICE = "has_shown_china_upload_notice"
        private const val KEY_USER_AVATAR_URL = "user_avatar_url"
        private const val KEY_AVATAR_LAST_UPDATED = "avatar_last_updated"
        private const val KEY_NATIVE_CREDENTIALS = "native_credentials"
        private const val KEY_NATIVE_ANISETTE_URL = "native_anisette_url"

        private const val KEY_NATIVE_PUBLIC_CONSENT = "native_public_anisette_consent"

        /**
         * Default fingerprint provider: SideStore's public anisette server, verified on
         * 2026-11-25 for native login + a 1.38 GiB upload. Users can point the App at their own
         * Mac / Windows PC instead (scripts/anisette_server).
         */
        const val DEFAULT_ANISETTE_URL = "https://new.ani.sidestore.io/"

        /** Third-party servers: using one needs the user's explicit consent (disclaimer). */
        private val PUBLIC_ANISETTE_HOSTS = setOf(
            "new.ani.sidestore.io",
            "ani.sidestore.io",
            "ani.sidestore.zip",
            "ani.sidestore.app",
            "ani.jkcoxson.com",
            "mac-ani.jkcoxson.com",
            "ani.npeg.us"
        )

        /** True when [url] points at a known public (shared, third-party) anisette server. */
        fun isPublicAnisetteUrl(url: String): Boolean {
            val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase() ?: return false
            return host in PUBLIC_ANISETTE_HOSTS
        }
    }
}
