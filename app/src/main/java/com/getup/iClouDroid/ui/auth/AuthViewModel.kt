package com.getup.iClouDroid.ui.auth

import android.app.Application
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.getup.iClouDroid.IClouDroidApp
import com.getup.iClouDroid.data.session.SessionManager
import com.getup.iClouDroid.feature.photos.data.image.ICloudImageLoaderFactory
import com.getup.iClouDroid.feature.photos.work.PeriodicScanScheduler
import com.getup.iClouDroid.feature.photos.work.ScanNotificationHelper
import com.getup.iClouDroid.feature.photos.work.ScanScheduler
import com.getup.icloudcore.Icloudcore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface AuthUiState {
    /**
     * Cold-start placeholder while the persisted session is being read (P2-013).
     *
     * This state exists purely to keep [AuthScreen] - and therefore [ICloudWebView] - out of the
     * composition during restoration. Rendering `NotLoggedIn` first would build a WebView (tens of
     * MB of native chromium plus a renderer process) only to tear it down a moment later, which cost
     * ~1.5s of blocked main thread on a real device.
     */
    data object Checking : AuthUiState
    data class NotLoggedIn(
        val isChinaMainland: Boolean = true,
        val showSessionExpiredAlert: Boolean = false
    ) : AuthUiState
    data class Authenticating(val message: String) : AuthUiState
    data class LoggedIn(
        val dsid: String,
        val ckDatabaseUrl: String,
        val isChinaMainland: Boolean,
        val hasHsaTrust: Boolean
    ) : AuthUiState
    data class Error(val errorMessage: String, val isChinaMainland: Boolean) : AuthUiState
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionManager = SessionManager(application)

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Checking)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /** Latest non-fatal cookie probe, surfaced in the diagnostic panel before full success. */
    private val _cookieDiagnostic = MutableStateFlow<CookieValidationResult?>(null)
    val cookieDiagnostic: StateFlow<CookieValidationResult?> = _cookieDiagnostic.asStateFlow()

    /** Bound by [ICloudWebView]; lets the UI force an extraction when auto-detection misses. */
    private val _cookieProbe = CookieProbe()
    val cookieProbe: CookieProbe get() = _cookieProbe

    /**
     * Set for the duration of a [logout] teardown (P2-018).
     *
     * Tearing down the session means deleting the very cookies [ICloudWebView] is watching for.
     * The WebView does not know a logout is in progress: `onPageStarted` fires while the login
     * page reloads, and at that instant `CookieManager` still holds the *old* 19-cookie set
     * (deletion is async). The probe therefore validates successfully and calls
     * [onCookiesExtracted], which would re-persist the credentials and call `initSession` again —
     * resurrecting the session we are in the middle of destroying.
     *
     * Observed on a real logout (fire-and-forget ordering):
     * ```
     * 38.725 W/PhotosViewModel: checkZoneReady failed: 正在退出登录…   <- guard held
     * 38.857 I/ICloudAuth: Three-element cookie validation PASSED     <- guard bypassed
     * 38.859 I/ICloudAuth: Calling Go InitSession
     * 39.028 I/ICloudAuth: Logout complete
     * ```
     * After that run `shared_prefs` still contained 7 keys — the session had been written back.
     *
     * Deliberately a plain @Volatile boolean, not derived from [AuthUiState]: the UI state alone
     * cannot express "cleaning up" without inventing a visible state, and the existing
     * `is Authenticating || is LoggedIn` short-circuit in [onCookiesExtracted] is exactly the kind
     * of async state check that loses races here (see docs/PITFALLS.md §7).
     */
    @Volatile
    private var isLoggingOut: Boolean = false

    init {
        // Run session restoration in background coroutine to avoid cold-start jank (skipping frames)
        viewModelScope.launch {
            checkExistingSession()
        }
    }

    /**
     * Restores the persisted session on cold start (P2-013).
     *
     * Everything that touches EncryptedSharedPreferences, the cookie string or the diagnostics
     * logger runs off the main thread. `_uiState` starts as [AuthUiState.Checking] and only ever
     * settles into [AuthUiState.NotLoggedIn] once we actually know there is no session - so the
     * login WebView is never built speculatively.
     */
    suspend fun checkExistingSession() {
        // One hop to IO: reads the five prefs values AND runs the cookie probe, instead of five
        // separate withContext(IO) round-trips that each re-acquire the keystore-backed store.
        val snapshot = withContext(Dispatchers.IO) {
            val cookieStr = if (sessionManager.isLoggedIn()) {
                sessionManager.getCookieString() ?: ""
            } else {
                ""
            }
            SessionSnapshot(
                isLoggedIn = cookieStr.isNotBlank(),
                cookieStr = cookieStr,
                isChina = sessionManager.isChinaMainland(),
                dsid = sessionManager.getDsid() ?: "",
                ckUrl = sessionManager.getCkDatabaseUrl() ?: "",
                // Parsing + logging a 2.8KB cookie string is pure CPU/IO work; keep it off main.
                validation = if (cookieStr.isNotBlank()) {
                    CookieValidationResult.validate(cookieStr).also {
                        it.logToLogcat("restored-from-storage")
                    }
                } else null
            )
        }

        if (!snapshot.isLoggedIn) {
            _uiState.value = AuthUiState.NotLoggedIn(snapshot.isChina)
            return
        }

        val validation = snapshot.validation
            ?: CookieValidationResult.validate(snapshot.cookieStr)

        if (snapshot.dsid.isNotBlank() && snapshot.ckUrl.isNotBlank()) {
            // Fast path: route info is cached, so we can go straight to LoggedIn. This path must
            // never emit NotLoggedIn, otherwise AuthScreen would flash and build a WebView.
            _uiState.value = AuthUiState.LoggedIn(
                dsid = snapshot.dsid,
                ckDatabaseUrl = snapshot.ckUrl,
                isChinaMainland = snapshot.isChina,
                hasHsaTrust = validation.hasHsaTrust
            )
        } else {
            // Route info missing - needs a Go round-trip. initGoSession sets Authenticating first.
            initGoSession(snapshot.cookieStr, snapshot.isChina, validation.hasHsaTrust)
        }
    }

    /** Immutable holder for one IO round-trip worth of session state. */
    private data class SessionSnapshot(
        val isLoggedIn: Boolean,
        val cookieStr: String,
        val isChina: Boolean,
        val dsid: String,
        val ckUrl: String,
        val validation: CookieValidationResult?
    )

    fun setRegion(isChinaMainland: Boolean, showExpiredAlert: Boolean = false) {
        sessionManager.setChinaMainland(isChinaMainland)
        _cookieDiagnostic.value = null
        val currentState = _uiState.value
        if (currentState is AuthUiState.NotLoggedIn) {
            _uiState.value = AuthUiState.NotLoggedIn(
                isChinaMainland = isChinaMainland,
                showSessionExpiredAlert = showExpiredAlert || currentState.showSessionExpiredAlert
            )
        } else if (currentState is AuthUiState.Error) {
            _uiState.value = AuthUiState.NotLoggedIn(
                isChinaMainland = isChinaMainland,
                showSessionExpiredAlert = true
            )
        }
    }

    fun onCookiesExtracted(result: CookieValidationResult) {
        // P2-018: a logout teardown is in progress. The WebView probe can still see the old
        // cookie set during this window (deletion is async), and accepting it here would
        // re-persist the credentials and rebuild the session we are destroying.
        if (isLoggingOut) {
            Log.d(AUTH_LOG_TAG, "onCookiesExtracted ignored - logout teardown in progress")
            return
        }
        if (_uiState.value is AuthUiState.Authenticating || _uiState.value is AuthUiState.LoggedIn) {
            return
        }

        // Always rely on the explicitly stored preference as the single source of truth
        val isChina = sessionManager.isChinaMainland()

        Log.i(AUTH_LOG_TAG, "Persisting cookie set (region=${if (isChina) "cn" else "global"})")
        sessionManager.saveSession(
            cookieStr = result.cookieString,
            isChinaMainland = isChina
        )

        initGoSession(result.cookieString, isChina, result.hasHsaTrust)
    }

    /** Receives every incomplete probe so the UI can explain what is still missing. */
    fun onCookieDiagnostic(result: CookieValidationResult) {
        _cookieDiagnostic.value = result
    }

    /**
     * Manual fallback: pull cookies straight out of the live WebView and run the same
     * three-element validation. Used when auto-detection is blocked by an unknown redirect.
     */
    fun forceExtractCookies() {
        // P2-018: same guard as [onCookiesExtracted] — never capture credentials mid-teardown.
        if (isLoggingOut) {
            Log.d(AUTH_LOG_TAG, "forceExtractCookies ignored - logout teardown in progress")
            return
        }
        if (_uiState.value is AuthUiState.Authenticating || _uiState.value is AuthUiState.LoggedIn) {
            return
        }
        val probeResult = _cookieProbe.extract()
        Log.i(AUTH_LOG_TAG, "forceExtractCookies invoked, result=${probeResult?.missingKeys}")
        if (probeResult == null) {
            _cookieDiagnostic.value = null
            _uiState.value = AuthUiState.Error(
                errorMessage = "WebView 尚未初始化或已销毁，请重新加载登录页后再试。",
                isChinaMainland = sessionManager.isChinaMainland()
            )
            return
        }
        if (probeResult.isValid) {
            // Grab the library size off the landing page before the WebView goes away (P2-016).
            //
            // The scan walks CloudKit's cursor, which never reports a total, so this is the only
            // source for a progress denominator. It is fire-and-forget and degrades silently: if
            // Apple changes the page copy, or the read races the WebView teardown, nothing at all
            // happens and the progress bar simply stays indeterminate.
            _cookieProbe.readCounts { counts ->
                if (counts == null) {
                    Log.d(AUTH_LOG_TAG, "Photo count scrape unavailable - progress stays indeterminate")
                } else {
                    Log.i(AUTH_LOG_TAG, "Photo count scrape: photos=${counts.photos} videos=${counts.videos}")
                    recordPhotoCounts(counts)
                }
            }
            onCookiesExtracted(probeResult)
        } else {
            _cookieDiagnostic.value = probeResult
        }
    }

    /**
     * Persists the scraped library size so [com.getup.iClouDroid.feature.photos.data.MonthScanEngine]
     * can use it as the progress denominator.
     *
     * Deliberately tolerant of an uninitialised container: this callback can land while the WebView
     * is being torn down, and a failed count must never surface to the user.
     */
    private fun recordPhotoCounts(counts: PhotoCounts) {
        val container = runCatching {
            (getApplication<Application>() as IClouDroidApp).container
        }.getOrNull() ?: return

        viewModelScope.launch {
            runCatching {
                container.monthScanEngine.setEstimatedTotal(counts.total)
            }.onFailure {
                Log.d(AUTH_LOG_TAG, "Failed to record photo count: ${it.message}")
            }
        }
    }

    private fun initGoSession(cookieStr: String, isChinaMainland: Boolean, hasHsaTrust: Boolean) {
        _uiState.value = AuthUiState.Authenticating("正在请求 iCloud 动态路由表...")
        Log.i(AUTH_LOG_TAG, "Calling Go InitSession (isChinaMainland=$isChinaMainland, hasHsaTrust=$hasHsaTrust)")

        viewModelScope.launch {
            try {
                val jsonResult = withContext(Dispatchers.IO) {
                    Icloudcore.initSession(cookieStr, isChinaMainland)
                }
                Log.i(AUTH_LOG_TAG, "Go InitSession returned: $jsonResult")

                val json = JSONObject(jsonResult)
                val success = json.optBoolean("success", false)

                if (success) {
                    val dsid = json.optString("dsid", "未知 DSID")
                    val ckUrl = json.optString("ckDatabaseUrl", "未获取到 CloudKit 节点")

                    sessionManager.updateRouteInfo(dsid, ckUrl)

                    // Check if Go core refreshed cookies silently
                    val updatedCookies = withContext(Dispatchers.IO) {
                        if (Icloudcore.hasCookieUpdates()) {
                            Icloudcore.getCurrentCookieString()
                        } else null
                    }
                    if (!updatedCookies.isNullOrBlank()) {
                        Log.i(AUTH_LOG_TAG, "Go core returned updated cookies, persisting...")
                        sessionManager.saveSession(
                            cookieStr = updatedCookies,
                            isChinaMainland = isChinaMainland,
                            dsid = dsid,
                            ckDatabaseUrl = ckUrl
                        )
                    }

                    _uiState.value = AuthUiState.LoggedIn(
                        dsid = dsid,
                        ckDatabaseUrl = ckUrl,
                        isChinaMainland = isChinaMainland,
                        hasHsaTrust = hasHsaTrust
                    )
                } else {
                    val errMsg = json.optString("errorMessage", "iCloud 登录初始化失败，请重试")
                    Log.w(AUTH_LOG_TAG, "Go InitSession reported failure: $errMsg")
                    _uiState.value = AuthUiState.Error(errMsg, isChinaMainland)
                }
            } catch (e: Exception) {
                Log.e(AUTH_LOG_TAG, "Go InitSession threw", e)
                _uiState.value = AuthUiState.Error("系统异常: ${e.message}", isChinaMainland)
            }
        }
    }

    /**
     * Signs out and tears down **all** account-scoped local state (P2-018).
     *
     * Previously this only cleared the credential vault, the WebView cookies and WebStorage. The
     * Room photo cache and the Coil image cache survived, and both are keyed by CloudKit
     * `recordName` — identifiers that belong to a specific Apple ID. After switching account or
     * region the next session would therefore show the previous account's photos and then fail
     * every URL refresh against records it cannot see.
     *
     * Teardown order is deliberate and must not be reshuffled:
     *
     *  1. **Block the entry points** (`beginWipe`) — any in-flight `RemoteMediator` APPEND or Coil
     *     interceptor refresh is now refused, so nothing can write to the DB while we clear it.
     *     Doing this *after* clearing would let a page land on top of the empty tables and
     *     re-introduce the old rows.
     *  2. **Data layer** (Room) — the source of truth for the grid.
     *  3. **Image layer** (Coil) — bytes that were signed for the dead session.
     *  4. **Session layer** (cookies / vault) — last, so nothing above fails mid-way on a missing
     *     cookie and leaves a half-cleared state behind.
     *
     * The UI flips to [AuthUiState.NotLoggedIn] immediately so the grid disappears at once; the
     * cleanup itself continues in the background and is idempotent if it fails part-way (the user
     * can simply log out again).
     */
    fun logout() {
        // Nothing may capture or persist credentials from here on: the WebView reloads the login
        // page while `removeAllCookies` is still in flight, which otherwise re-arms the session.
        isLoggingOut = true

        val currentRegion = sessionManager.isChinaMainland()
        _cookieDiagnostic.value = null

        // The VM state flips before the async teardown: LoggedIn → NotLoggedIn disposes AppShell
        // and the photo grid, so the user never sees the wiped-but-still-composed list.
        _uiState.value = AuthUiState.NotLoggedIn(isChinaMainland = currentRegion)

        viewModelScope.launch {
            val container = (getApplication<Application>() as IClouDroidApp).container
            val repository = container.photoRepository

            // 0. Stop the background scan first (P2-016).
            //
            // The worker writes photo rows; if it kept running through the wipe it would both
            // resurrect deleted rows and leave an un-dismissable progress notification stranded
            // on the login screen. Cancelling here is part of the "block before you clean" rule
            // that beginWipe() enforces for the network paths.
            runCatching {
                ScanScheduler.cancel(getApplication<Application>())
                PeriodicScanScheduler.cancel(getApplication<Application>())
                ScanNotificationHelper.cancel(getApplication<Application>())
            }.onFailure { Log.w(AUTH_LOG_TAG, "Failed to cancel scan worker: ${it.message}") }

            // 1. Block new network work and stop the paging pipeline.
            repository.beginWipe()
            // Drive is blocked in the same phase, *before* any table is cleared. Its rows are
            // keyed by CloudKit ids belonging to the account being destroyed, so keeping them
            // across a re-login leaves a tree whose every entry fails to fetch (AGENTS §5.5).
            container.driveRepository.beginWipe()

            try {
                // 2. Room: photo rows + sync cursor.
                val dbCleared = repository.wipeAllData()
                if (!dbCleared) {
                    Log.w(AUTH_LOG_TAG, "Photo database wipe failed, continuing teardown")
                }

                val driveCleared = container.driveRepository.wipeAllData()
                if (!driveCleared) {
                    Log.w(AUTH_LOG_TAG, "Drive database wipe failed, continuing teardown")
                }

                // 3. Coil: memory + disk thumbnails keyed by foreign recordNames.
                val cachesCleared = ICloudImageLoaderFactory.clearCaches(
                    context = getApplication(),
                    container = container
                )
                if (!cachesCleared) {
                    Log.w(AUTH_LOG_TAG, "Coil cache clear failed, continuing teardown")
                }

                // 4. Session layer: WebView cookies, WebStorage, credential vault.
                clearWebViewCookies()
                sessionManager.clearSession()

                Log.i(
                    AUTH_LOG_TAG,
                    "Logout complete (region=${if (currentRegion) "cn" else "global"}) " +
                        "db=$dbCleared drive=$driveCleared coil=$cachesCleared"
                )
            } catch (e: Exception) {
                // Teardown must never crash the app; a partial wipe is recoverable by logging out
                // again, whereas a crash here would strand the UI in a broken state.
                Log.e(AUTH_LOG_TAG, "Logout teardown failed", e)
            } finally {
                // Always release the guards, otherwise the next login could never sync.
                repository.endWipe()
                container.driveRepository.endWipe()

                // Keep the credential guard up until the login page has finished reloading, so
                // the probe never reads a half-deleted cookie set. `removeAllCookies` completing
                // does not mean the page has settled; one extra frame at minimum is needed.
                delay(COOKIE_CAPTURE_GRACE_MS)
                isLoggingOut = false
                Log.d(AUTH_LOG_TAG, "Credential capture re-enabled")
            }
        }
    }

    /**
     * Removes every WebView cookie and waits for the removal to actually complete.
     *
     * `removeAllCookies(null)` is asynchronous and passing `null` only discards the completion
     * callback — it does **not** make the call synchronous. The old implementation followed it
     * with `flush()` immediately, which could persist the *pre-deletion* cookie jar and leave the
     * credentials intact across a process restart.
     *
     * Bridging the callback into a suspend point fixes that, and it also has to run on the main
     * thread: the callback is only guaranteed to be delivered on the thread that owns the
     * WebView's looper.
     */
    private suspend fun clearWebViewCookies() = withContext(Dispatchers.Main) {
        try {
            val cookieManager = CookieManager.getInstance()
            suspendCancellableCoroutine<Unit> { cont ->
                cookieManager.removeAllCookies {
                    if (cont.isActive) cont.resume(Unit) { _, _, _ -> }
                }
            }
            cookieManager.flush()
            WebStorage.getInstance().deleteAllData()
            Log.i(AUTH_LOG_TAG, "WebView cookies and WebStorage cleared")
        } catch (e: Exception) {
            // WebView may never have been initialised (e.g. a fresh install that restored a
            // session without ever building the login page). Nothing to clear in that case.
            Log.w(AUTH_LOG_TAG, "WebView cookie clear skipped: ${e.message}")
        }
    }

    companion object {
        /**
         * How long [isLoggingOut] stays armed after the teardown finishes (P2-018).
         *
         * `removeAllCookies`'s callback fires when deletion is accepted, but the login page the
         * WebView reloaded into is still settling and will emit further `onPageStarted` /
         * `onPageFinished` events. Those fire the cookie probe, and a probe that lands in the
         * gap between "old cookies deleted" and "page fully reloaded" can still surface a
         * partially-populated set. A short grace window keeps the guard up across it.
         *
         * 800ms was chosen empirically: the observed login-page reload completes its probes
         * within ~1s of the logout tap, and the cost of being late is only that a genuinely
         * fast manual re-login waits an extra fraction of a second.
         */
        private const val COOKIE_CAPTURE_GRACE_MS = 800L
    }
}
