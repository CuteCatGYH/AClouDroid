package app.acloudroid.qwq.feature.drive.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity

/**
 * Maps item type/extension to web iCloud suite URL.
 */
fun resolveIWorkSuiteUrl(item: DriveItemEntity, isChinaMainland: Boolean = false): String {
    val domain = if (isChinaMainland) "www.icloud.com.cn" else "www.icloud.com"
    val ext = item.extension?.lowercase()?.trim() ?: ""
    val name = item.name.lowercase()

    return when {
        ext == "pages" || name.contains("pages") -> "https://$domain/pages/"
        ext == "numbers" || name.contains("numbers") -> "https://$domain/numbers/"
        ext == "key" || ext == "keynote" || name.contains("keynote") -> "https://$domain/keynote/"
        else -> "https://$domain/iclouddrive/"
    }
}

fun resolveIWorkSuiteTitle(item: DriveItemEntity): String {
    val ext = item.extension?.lowercase()?.trim() ?: ""
    val name = item.name.lowercase()

    return when {
        ext == "pages" || name.contains("pages") -> "iCloud Pages"
        ext == "numbers" || name.contains("numbers") -> "iCloud Numbers"
        ext == "key" || ext == "keynote" || name.contains("keynote") -> "iCloud Keynote"
        else -> "iCloud 云盘网页版"
    }
}

/**
 * In-app Web Dialog loading iCloud iWork Web editor/viewer (Pages, Numbers, Keynote) with authenticated cookies.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DriveIWorkWebDialog(
    item: DriveItemEntity,
    isChinaMainland: Boolean = false,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val targetUrl = remember(item.drivewsid, isChinaMainland) {
        resolveIWorkSuiteUrl(item, isChinaMainland)
    }
    val title = remember(item.drivewsid) { resolveIWorkSuiteTitle(item) }

    var loadingProgress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = { webViewInstance?.reload() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新页面",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(targetUrl)).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(intent) }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "在外部浏览器打开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Progress Bar
                AnimatedVisibility(visible = isLoading) {
                    LinearProgressIndicator(
                        progress = { loadingProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                    )
                }

                // WebView Container
                Box(modifier = Modifier.weight(1f)) {
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
                                    setSupportZoom(true)
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                                }

                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)

                                val desktopJsOverride = """
                                    (function() {
                                        try {
                                            var desktopUA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
                                            Object.defineProperty(navigator, 'userAgent', { get: function() { return desktopUA; } });
                                            Object.defineProperty(navigator, 'platform', { get: function() { return 'MacIntel'; } });
                                            Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; } });
                                            Object.defineProperty(navigator, 'vendor', { get: function() { return 'Google Inc.'; } });
                                        } catch (e) {}
                                    })();
                                """.trimIndent()

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadingProgress = newProgress / 100f
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        isLoading = true
                                        view?.evaluateJavascript(desktopJsOverride, null)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isLoading = false
                                        view?.evaluateJavascript(desktopJsOverride, null)
                                    }
                                }

                                val extraHeaders = mapOf(
                                    "Sec-CH-UA-Mobile" to "?0",
                                    "Sec-CH-UA-Platform" to "\"macOS\"",
                                    "Sec-CH-UA" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"131\""
                                )

                                webViewInstance = this
                                loadUrl(targetUrl, extraHeaders)
                            }
                        },
                        update = { view ->
                            webViewInstance = view
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
