package app.acloudroid.qwq.feature.photos.data

import android.util.Log
import app.acloudroid.qwq.data.session.SessionManager
import com.getup.icloudcore.Icloudcore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * "大文件上传": a second, device-style Apple ID login used only to upload files the 云上贵州 web
 * gateway rejects (> 1 GiB, ISS-057). The Go core sends those originals through native MMCS.
 *
 * The Go side keeps the credentials in process memory only; this class persists them
 * (encrypted, via [SessionManager]) and re-installs them on every cold start through
 * [ensureRestored]. The password passes through [beginLogin] and is never stored.
 *
 * Device fingerprints come from an HTTP provider ([SessionManager.getNativeAnisetteUrl]):
 * SideStore's public server by default (needs the user's consent, see [acceptPublicDisclaimer]),
 * or the user's own Mac / Windows PC running scripts/anisette_server.
 */
class NativeUploadRepository(private val sessionManager: SessionManager) {

    data class Status(
        val loggedIn: Boolean,
        val appleId: String? = null,
        val anisetteUrl: String = SessionManager.DEFAULT_ANISETTE_URL,
        val publicConsent: Boolean = false
    ) {
        val usesPublicServer: Boolean get() = SessionManager.isPublicAnisetteUrl(anisetteUrl)
    }

    /** Result of a login step. */
    sealed interface LoginStep {
        data class Done(val appleId: String) : LoginStep
        data class NeedCode(val sms: Boolean) : LoginStep
        data class Failed(val errorCode: String, val message: String, val stillNeedsCode: Boolean = false, val sms: Boolean = false) : LoginStep
    }

    private val _status = MutableStateFlow(Status(loggedIn = false))
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    private var restored = false

    /**
     * Installs the stored credentials into the Go core. Cheap and idempotent; must run before
     * any upload that may be over 1 GiB (the Go core decides the route).
     */
    fun ensureRestored() {
        if (restored) return
        synchronized(this) {
            if (restored) return
            val creds = sessionManager.getNativeCredentials().orEmpty()
            val url = sessionManager.getNativeAnisetteUrl()
            val json = runCatching { JSONObject(Icloudcore.nativeConfigure(creds, url)) }.getOrNull()
            if (creds.isNotEmpty() && json?.optBoolean("success") != true) {
                // Unreadable blob: drop it rather than fail every large upload on it.
                Log.w(TAG, "stored native credentials rejected by Go core; clearing")
                sessionManager.saveNativeCredentials(null)
            }
            _status.value = statusOf(
                loggedIn = json?.optBoolean("configured") == true,
                appleId = json?.optString("appleId")?.takeIf { it.isNotBlank() }
            )
            restored = true
        }
    }

    private fun statusOf(loggedIn: Boolean, appleId: String?) = Status(
        loggedIn = loggedIn,
        appleId = appleId,
        anisetteUrl = sessionManager.getNativeAnisetteUrl(),
        publicConsent = sessionManager.hasPublicAnisetteConsent()
    )

    /** Re-reads the Go core's view (e.g. after a logout elsewhere cleared it). */
    fun refreshStatus() {
        ensureRestored()
        val json = runCatching { JSONObject(Icloudcore.nativeStatus()) }.getOrNull() ?: return
        _status.value = statusOf(
            loggedIn = json.optBoolean("configured"),
            appleId = json.optString("appleId").takeIf { it.isNotBlank() }
        )
    }

    /** The user ticked "I understand" under the public-server disclaimer. */
    fun acceptPublicDisclaimer(accepted: Boolean) {
        sessionManager.setPublicAnisetteConsent(accepted)
        _status.value = _status.value.copy(publicConsent = accepted)
    }

    suspend fun setAnisetteUrl(url: String) = withContext(Dispatchers.IO) {
        sessionManager.setNativeAnisetteUrl(url)
        restored = false
        ensureRestored()
    }

    suspend fun beginLogin(appleId: String, password: String): LoginStep = withContext(Dispatchers.IO) {
        ensureRestored()
        val url = sessionManager.getNativeAnisetteUrl()
        if (SessionManager.isPublicAnisetteUrl(url) && !sessionManager.hasPublicAnisetteConsent()) {
            // The dialog disables the button; this guards any other caller.
            return@withContext LoginStep.Failed("CONSENT_REQUIRED", "public fingerprint server not accepted")
        }
        parseLogin(Icloudcore.nativeLoginBegin(url, appleId, password))
    }

    suspend fun submitCode(code: String): LoginStep = withContext(Dispatchers.IO) {
        parseLogin(Icloudcore.nativeLoginSubmitCode(code))
    }

    suspend fun useSms(): LoginStep = withContext(Dispatchers.IO) {
        parseLogin(Icloudcore.nativeLoginUseSMS())
    }

    fun cancelLogin() {
        Icloudcore.nativeLoginCancel()
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        Icloudcore.nativeLogout()
        sessionManager.saveNativeCredentials(null)
        _status.value = statusOf(loggedIn = false, appleId = null)
    }

    /** Opens the Photos zone natively without uploading: proves provider + login both work. */
    suspend fun check(): Result<Unit> = withContext(Dispatchers.IO) {
        ensureRestored()
        val json = runCatching { JSONObject(Icloudcore.nativeCheck()) }.getOrElse {
            return@withContext Result.failure(it)
        }
        if (json.optBoolean("success")) Result.success(Unit)
        else Result.failure(Exception("${json.optString("errorCode")}: ${json.optString("errorMessage")}"))
    }

    private fun parseLogin(raw: String): LoginStep {
        val json = runCatching { JSONObject(raw) }.getOrElse {
            return LoginStep.Failed("PARSE", it.message ?: "bad reply")
        }
        val state = json.optString("state")
        if (!json.optBoolean("success")) {
            val code = json.optString("errorCode", "UNKNOWN")
            Log.w(TAG, "native login step failed: $code")
            return LoginStep.Failed(
                errorCode = code,
                message = json.optString("errorMessage"),
                stillNeedsCode = state == "NEED_CODE",
                sms = json.optBoolean("sms")
            )
        }
        return when (state) {
            "DONE" -> {
                val creds = json.optString("credentials")
                sessionManager.saveNativeCredentials(creds)
                val appleId = json.optString("appleId")
                _status.value = statusOf(loggedIn = true, appleId = appleId)
                restored = true
                Log.i(TAG, "native login done")
                LoginStep.Done(appleId)
            }
            else -> LoginStep.NeedCode(sms = json.optBoolean("sms"))
        }
    }

    companion object {
        private const val TAG = "NativeUpload"

        /** Above this, a 云上贵州 upload goes native (mirrors Go's MainlandChinaSingleFileLimitBytes). */
        const val WEB_SINGLE_FILE_LIMIT_BYTES = 1024L * 1024 * 1024
    }
}
