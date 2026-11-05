package app.acloudroid.qwq.data.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class SessionManager(context: Context) {

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

    fun clearSession() {
        val currentRegion = isChinaMainland()
        prefs.edit().clear().apply()
        setChinaMainland(currentRegion)
        cachedSessionGeneration = null
    }

    fun getCookieString(): String? = prefs.getString(KEY_COOKIE_STR, null)

    fun isChinaMainland(): Boolean = prefs.getBoolean(KEY_IS_CHINA, true)

    fun hasShownChinaUploadNotice(): Boolean = prefs.getBoolean(KEY_HAS_SHOWN_CHINA_UPLOAD_NOTICE, false)

    fun setHasShownChinaUploadNotice(shown: Boolean = true) {
        prefs.edit().putBoolean(KEY_HAS_SHOWN_CHINA_UPLOAD_NOTICE, shown).apply()
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
    }
}
