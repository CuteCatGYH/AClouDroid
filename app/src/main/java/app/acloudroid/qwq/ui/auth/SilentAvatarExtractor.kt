package app.acloudroid.qwq.ui.auth

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import app.acloudroid.qwq.data.session.SessionManager

object SilentAvatarExtractor {

    private const val TAG = "SilentAvatarExtractor"

    @Volatile
    private var isExtracting = false

    /**
     * Triggers a silent, invisible background WebView load of the iCloud home page
     * to scrape the user's avatar image URL if the cache is expired (>30 days) or missing.
     */
    fun extractIfDue(context: Context, sessionManager: SessionManager, force: Boolean = false) {
        if (isExtracting) return
        if (!force && !sessionManager.isAvatarExpired(context)) {
            Log.d(TAG, "Avatar cache is fresh (<= 30 days old). Skipping silent extraction.")
            return
        }

        if (!sessionManager.isLoggedIn()) return

        isExtracting = true
        Log.i(TAG, "Starting silent background avatar extraction (force=$force)...")

        Handler(Looper.getMainLooper()).post {
            try {
                performSilentExtraction(context.applicationContext, sessionManager)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize silent WebView for avatar extraction: ${e.message}")
                isExtracting = false
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun performSilentExtraction(appContext: Context, sessionManager: SessionManager) {
        val webView = WebView(appContext)
        val handler = Handler(Looper.getMainLooper())

        val cleanupRunnable = Runnable {
            try {
                webView.stopLoading()
                webView.destroy()
            } catch (_: Exception) {}
            isExtracting = false
            Log.d(TAG, "Silent avatar WebView cleaned up.")
        }

        // Safety timeout: destroy after 15 seconds if page or JS hangs
        handler.postDelayed(cleanupRunnable, 15_000L)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val targetUrl = if (sessionManager.isChinaMainland()) "https://www.icloud.com.cn" else "https://www.icloud.com"

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Give DOM elements 2 seconds to settle and render cards
                handler.postDelayed({
                    val scrapeJs = """
                        (function () {
                          try {
                            var altImgs = document.querySelectorAll('img[alt*="个人资料"], img[alt*="profile"], img[alt*="Profile"]');
                            for (var a = 0; a < altImgs.length; a++) {
                              var s = altImgs[a].src || '';
                              if (s && s.startsWith('http') && !s.includes('cvws') && !s.includes('icloud-content')) {
                                return s;
                              }
                            }
                            var selectors = [
                              '[class*="user-avatar"] img',
                              '[class*="profile-picture"] img',
                              '[class*="account-menu"] img',
                              '[data-test-id*="account"] img',
                              '[data-test-id*="avatar"] img',
                              '[class*="account-card"] img',
                              'ui-avatar img',
                              '[class*="avatar"] img'
                            ];
                            for (var i = 0; i < selectors.length; i++) {
                              var el = document.querySelector(selectors[i]);
                              if (el && el.src) {
                                var url = el.src;
                                if (!url.includes('cvws') && !url.includes('icloud-content') && !url.includes('svg') && !url.includes('icon')) {
                                  return url;
                                }
                              }
                            }
                            return null;
                          } catch (e) { return null; }
                        })()
                    """.trimIndent()

                    webView.evaluateJavascript(scrapeJs) { raw ->
                        val cleanUrl = parseJsStringResult(raw)
                        if (!cleanUrl.isNullOrBlank()) {
                            Log.i(TAG, "Successfully scraped avatar URL in background: $cleanUrl")
                            sessionManager.saveUserAvatarUrl(cleanUrl, appContext)
                        } else {
                            Log.d(TAG, "No avatar img src found during silent extraction")
                        }
                        handler.removeCallbacks(cleanupRunnable)
                        cleanupRunnable.run()
                    }
                }, 2_000L)
            }
        }

        webView.loadUrl(targetUrl)
    }
}
