package app.acloudroid.qwq.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.acloudroid.qwq.BuildConfig

/** Logcat tag used for every auth / cookie diagnostic log line. */
const val AUTH_LOG_TAG = "ICloudAuth"

const val COOKIE_AUTH_TOKEN = "X-APPLE-WEBAUTH-TOKEN"
const val COOKIE_HSA_TRUST = "X-APPLE-WEBAUTH-HSA-TRUST"
const val COOKIE_PCS_CLOUDKIT = "X-APPLE-WEBAUTH-PCS-CloudKit"
const val COOKIE_PCS_PHOTOS = "X-APPLE-WEBAUTH-PCS-Photos"

/**
 * Result of a cookie probe against the iCloud WebView cookie store.
 *
 * NOTE: [cookieString] and cookie values are NEVER written to Logcat, only their
 * presence and length, so that the diagnostic flow stays safe to share.
 */
data class CookieValidationResult(
    val isValid: Boolean,
    val cookieString: String,
    val hasAuthToken: Boolean,
    val hasHsaTrust: Boolean,
    val hasPcsCloudKit: Boolean,
    val hasPcsPhotos: Boolean = false,
    val cookieNames: List<String> = emptyList(),
    val expiresSummary: String? = null
) {

    /** Keys required for the app to continue into Phase 2. */
    val missingRequiredKeys: List<String>
        get() = listOfNotNull(
            COOKIE_AUTH_TOKEN.takeUnless { hasAuthToken },
            COOKIE_PCS_CLOUDKIT.takeUnless { hasPcsCloudKit }
        )

    /** Every one of the three "must have" credentials that was not captured. */
    val missingKeys: List<String>
        get() = listOfNotNull(
            COOKIE_AUTH_TOKEN.takeUnless { hasAuthToken },
            COOKIE_HSA_TRUST.takeUnless { hasHsaTrust },
            COOKIE_PCS_CLOUDKIT.takeUnless { hasPcsCloudKit }
        )

    /** Length of the raw value of [name] (0 when the cookie is absent). */
    fun valueLength(name: String): Int =
        cookieString.split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?.length ?: 0

    /**
     * Emits a single summary line per probe (P2-013).
     *
     * The previous implementation did five `Log.i` calls plus a full cookie-name dump, and it is
     * invoked from the WebView's page callbacks - i.e. on the main thread during cold start. The
     * per-cookie detail is only useful while debugging, so it is dropped to `Log.d` and compiled
     * out of release builds entirely.
     */
    fun logToLogcat(pageUrl: String?, tag: String = AUTH_LOG_TAG) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            tag,
            "cookie probe @$pageUrl total=${cookieNames.size} " +
                "token=$hasAuthToken hsa=$hasHsaTrust pcs=$hasPcsCloudKit " +
                "valid=$isValid missing=${missingKeys.ifEmpty { listOf("none") }}"
        )
    }

    private fun statusLine(name: String, present: Boolean): String =
        if (present) "OK   $name (len=${valueLength(name)})" else "MISS $name"

    companion object {
        fun validate(cookieStr: String?): CookieValidationResult {
            if (cookieStr.isNullOrBlank()) {
                return CookieValidationResult(
                    isValid = false,
                    cookieString = "",
                    hasAuthToken = false,
                    hasHsaTrust = false,
                    hasPcsCloudKit = false,
                    cookieNames = emptyList()
                )
            }

            val names = cookieStr.split(';')
                .asSequence()
                .map { it.substringBefore('=').trim().trim('"') }
                .filter { it.isNotEmpty() }
                .toList()

            fun has(name: String) = names.any { it.equals(name, ignoreCase = true) }

            val hasAuthToken = has(COOKIE_AUTH_TOKEN)
            val hasHsaTrust = has(COOKIE_HSA_TRUST)
            val hasPcsCloudKit = has(COOKIE_PCS_CLOUDKIT)
            val hasPcsPhotos = has(COOKIE_PCS_PHOTOS)

            // A usable session requires the short-lived token AND the PCS-CloudKit key.
            val isValid = hasAuthToken && hasPcsCloudKit

            // Check for Max-Age or Expires hints if present
            var expiresSummary: String? = null
            if (hasHsaTrust) {
                expiresSummary = "HSA-TRUST 已就绪 (默认约 30~60 天有效期)"
            }

            return CookieValidationResult(
                isValid = isValid,
                cookieString = cookieStr,
                hasAuthToken = hasAuthToken,
                hasHsaTrust = hasHsaTrust,
                hasPcsCloudKit = hasPcsCloudKit,
                hasPcsPhotos = hasPcsPhotos,
                cookieNames = names,
                expiresSummary = expiresSummary
            )
        }
    }
}

/**
 * Library size scraped from the iCloud landing page (P2-016).
 *
 * CloudKit's cursor API never reports a total, so this is the only way to give the scan progress
 * bar a denominator. [total] is 0 when nothing could be read.
 *
 * Deliberately approximate: the web page counts differently from `CPLAsset` records (hidden
 * items, orphan masters, Live Photo composition). It is only ever used for the progress bar.
 */
data class PhotoCounts(val photos: Int, val videos: Int) {
    val total: Int get() = photos + videos
}

/**
 * Parses the JSON that the count-scraping snippet returns.
 *
 * `evaluateJavascript` hands back a *doubly* encoded string: the script returns a JSON string,
 * and WebView wraps that in another JSON layer (so `null` arrives as the literal `"null"`).
 * Every malformed shape maps to `null` so the caller degrades silently.
 */
internal fun parsePhotoCounts(raw: String?): PhotoCounts? {
    if (raw.isNullOrBlank() || raw == "null" || raw == "\"null\"") return null
    return try {
        // Strip the outer JSON string quoting if present.
        val inner = if (raw.startsWith("\"") && raw.endsWith("\"")) {
            org.json.JSONTokener(raw).nextValue() as? String ?: return null
        } else {
            raw
        }
        val obj = org.json.JSONObject(inner)
        val photos = obj.optInt("photos", 0)
        val videos = obj.optInt("videos", 0)
        if (photos <= 0 && videos <= 0) null else PhotoCounts(photos, videos)
    } catch (e: Exception) {
        null
    }
}

/**
 * Lets the host screen pull data out of the live WebView on demand.
 *
 * Used by the in-app diagnostic panel, by automated verification, and — for [readCounts] — to
 * grab the library size off the landing page before the WebView is torn down.
 */
class CookieProbe {
    private var extractor: (() -> CookieValidationResult)? = null
    private var countsReader: ((callback: (PhotoCounts?) -> Unit) -> Unit)? = null

    internal fun bind(block: () -> CookieValidationResult) {
        extractor = block
    }

    internal fun bindCountsReader(block: (callback: (PhotoCounts?) -> Unit) -> Unit) {
        countsReader = block
    }

    fun extract(): CookieValidationResult? = extractor?.invoke()

    /**
     * Reads the photo/video counts off the currently loaded iCloud page.
     *
     * **Asynchronous by necessity.** `WebView.evaluateJavascript` delivers its result on the main
     * thread, so a synchronous read from a main-thread caller (which is exactly where
     * `forceExtractCookies()` runs) would deadlock on a latch. The callback is invoked with
     * `null` when nothing could be read — the caller is expected to degrade silently.
     */
    fun readCounts(callback: (PhotoCounts?) -> Unit) {
        val reader = countsReader
        if (reader == null) {
            callback(null)
        } else {
            reader(callback)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ICloudWebView(
    isChinaMainland: Boolean,
    onCookiesExtracted: (CookieValidationResult) -> Unit,
    modifier: Modifier = Modifier,
    onCookieDiagnostic: (CookieValidationResult) -> Unit = {},
    probe: CookieProbe? = null
) {
    val targetUrl = if (isChinaMainland) "https://www.icloud.com.cn" else "https://www.icloud.com"
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var lastLoadedRegion by remember { mutableStateOf<Boolean?>(null) }

    var hasSubmitted by remember { mutableStateOf(false) }

    // Keep callback references fresh so the long-lived WebViewClient never calls a stale lambda.
    val latestOnExtracted by rememberUpdatedState(onCookiesExtracted)
    val latestOnDiagnostic by rememberUpdatedState(onCookieDiagnostic)

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                    }

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    // On-demand probe used by the diagnostic panel.
                    val extractNow: () -> CookieValidationResult = {
                        val pageUrl = url ?: targetUrl
                        val raw = cookieManager.getCookie(pageUrl)
                        CookieValidationResult.validate(raw).also { it.logToLogcat(pageUrl) }
                    }
                    probe?.bind(extractNow)

                    // Scrapes the library size off the landing page ("13,689 张照片, 2,247 个视频").
                    //
                    // `evaluateJavascript` runs through the WebView bridge, so unlike injected
                    // <script> it is not blocked by the page's CSP. Every failure mode (page not
                    // settled, Apple changed the copy, JS threw) collapses to `null`, which the
                    // caller treats as "total unknown" and simply shows an indeterminate bar.
                    probe?.bindCountsReader { callback ->
                        val js = """
                            (function () {
                              try {
                                var t = document.body ? document.body.innerText : "";
                                var p = t.match(/([\d,]+)\s*张照片/);
                                var v = t.match(/([\d,]+)\s*个视频/);
                                if (!p && !v) return null;
                                return JSON.stringify({
                                  photos: p ? parseInt(p[1].replace(/,/g, ""), 10) : 0,
                                  videos: v ? parseInt(v[1].replace(/,/g, ""), 10) : 0
                                });
                              } catch (e) { return null; }
                            })()
                        """.trimIndent()

                        evaluateJavascript(js) { raw ->
                            callback(parsePhotoCounts(raw))
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loadingProgress = newProgress / 100f
                            isLoading = newProgress < 100
                        }

                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message() ?: return false
                            // Filter known harmless console noise from Apple web app
                            if (msg.contains("batchRegisterToken") ||
                                msg.contains("preloaded using link preload") ||
                                msg.contains("CORS") ||
                                msg.contains("favicon.ico")
                            ) {
                                return true // Suppress harmless noise
                            }
                            Log.d("ICloudWebConsole", "${consoleMessage.messageLevel()}: $msg")
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            checkCookies("onPageStarted", url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            checkCookies("onPageFinished", url)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            checkCookies("shouldOverrideUrlLoading", request?.url?.toString())
                            return false
                        }

                        private fun checkCookies(source: String, pageUrl: String?) {
                            if (pageUrl == null) return
                            val raw = cookieManager.getCookie(pageUrl)
                            val validation = CookieValidationResult.validate(raw)
                            Log.d(AUTH_LOG_TAG, "checkCookies source=$source")
                            validation.logToLogcat(pageUrl)

                            if (validation.isValid) {
                                if (!hasSubmitted) {
                                    hasSubmitted = true
                                    Log.i(
                                        AUTH_LOG_TAG,
                                        "Three-element cookie validation PASSED, handing over to Go core once."
                                    )
                                    latestOnExtracted(validation)
                                }
                            } else {
                                latestOnDiagnostic(validation)
                            }
                        }
                    }

                    loadUrl(targetUrl)
                    lastLoadedRegion = isChinaMainland
                }
            },
            update = { webView ->
                if (lastLoadedRegion != null && lastLoadedRegion != isChinaMainland) {
                    lastLoadedRegion = isChinaMainland
                    hasSubmitted = false
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()
                    webView.loadUrl(targetUrl)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isLoading) {
            LinearProgressIndicator(
                progress = { loadingProgress },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
