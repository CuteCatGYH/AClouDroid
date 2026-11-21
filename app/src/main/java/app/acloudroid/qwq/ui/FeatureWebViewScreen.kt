package app.acloudroid.qwq.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.acloudroid.qwq.di.appContainer

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FeatureWebViewScreen(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            val sessionManager = context.appContainer().sessionManager

            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        sessionManager.trySyncFreshCookiesFromWeb()

                        val scrapeJs = """
                            (function () {
                              try {
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
                                for (var s = 0; s < selectors.length; s++) {
                                  var el = document.querySelector(selectors[s]);
                                  if (el && el.src) {
                                    var src = el.src;
                                    if (!src.includes('cvws') && !src.includes('icloud-content') && !src.includes('svg') && !src.includes('icon')) {
                                      return src;
                                    }
                                  }
                                }
                                var bgContainers = document.querySelectorAll('[class*="avatar"], [class*="profile"], [class*="account"]');
                                for (var b = 0; b < bgContainers.length; b++) {
                                  var style = window.getComputedStyle(bgContainers[b]);
                                  var bg = style ? style.backgroundImage : '';
                                  if (bg && bg.includes('url(')) {
                                    var m = bg.match(/url\(["']?(.*?)["']?\)/);
                                    if (m && m[1] && !m[1].includes('cvws') && !m[1].includes('icloud-content') && !m[1].includes('svg') && !m[1].includes('icon')) {
                                      return m[1];
                                    }
                                  }
                                }
                                var allImgs = Array.from(document.querySelectorAll('img'));
                                for (var i = 0; i < allImgs.length; i++) {
                                  var url = allImgs[i].src || '';
                                  if (url && url.startsWith('http') && !url.includes('cvws') && !url.includes('icloud-content') && !url.includes('svg') && !url.includes('icon') && !url.includes('logo')) {
                                    if (url.includes('avatar') || url.includes('profile') || url.includes('blob:') || url.includes('data:image')) {
                                      return url;
                                    }
                                  }
                                }
                                return null;
                              } catch (e) { return null; }
                            })()
                        """.trimIndent()

                        val diagJs = """
                            (function () {
                              try {
                                var res = [];
                                var imgs = document.querySelectorAll('img');
                                for (var i = 0; i < imgs.length; i++) {
                                  var p = imgs[i].parentElement ? (imgs[i].parentElement.className || imgs[i].parentElement.tagName) : '';
                                  res.push({i: i, src: imgs[i].src, alt: imgs[i].alt, cls: imgs[i].className, p: p, w: imgs[i].width, h: imgs[i].height});
                                }
                                var els = document.querySelectorAll('*');
                                for (var j = 0; j < els.length; j++) {
                                  var st = window.getComputedStyle(els[j]);
                                  if (st && st.backgroundImage && st.backgroundImage !== 'none' && st.backgroundImage.includes('url(')) {
                                    res.push({bg: st.backgroundImage, tag: els[j].tagName, cls: els[j].className});
                                  }
                                }
                                return JSON.stringify(res);
                              } catch (e) { return e.toString(); }
                            })()
                        """.trimIndent()

                        view?.postDelayed({
                            view.evaluateJavascript(diagJs) { raw ->
                                android.util.Log.i("DOM_DIAGNOSTIC", "DOM_DIAGNOSTIC=$raw")
                            }
                        }, 3000L)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        sessionManager.trySyncFreshCookiesFromWeb()
                        return false
                    }
                }
                webChromeClient = WebChromeClient()

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                loadUrl(url)
            }
        },
        update = { webView ->
            // Prevent reloading the same URL when recomposing
            if (webView.url != url && webView.originalUrl != url) {
                webView.loadUrl(url)
            }
        }
    )
}
