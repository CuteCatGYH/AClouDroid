package app.acloudroid.qwq.feature.photos.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.acloudroid.qwq.feature.photos.data.MonthBucketing
import app.acloudroid.qwq.feature.photos.data.ScanProgress

/**
 * Scanning indicator for the gallery (P2-016).
 *
 * ## Two modes, chosen by data availability
 *
 * CloudKit's cursor API never reports a total. The only source for a denominator is the iCloud web
 * page, scraped at login time — and that scrape can legitimately fail at any time (Apple changes
 * the copy, the user restored a session instead of logging in). So:
 *
 *  - **total known** → determinate bar, `3,847 / 16,116 项 · 2026年8月`
 *  - **total unknown** → indeterminate spinner, `已扫描 3,847 项 · 2026年8月`
 *
 * The item count and the current month are shown in both modes, because they are what actually
 * tells the user how far along the scan is. A percentage alone would be meaningless when the
 * denominator is a guess.
 *
 * The bar disappears entirely once the scan finishes — no "100%" lingering.
 */
@Composable
fun ScanProgressBar(
    progress: ScanProgress,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = progress.isScanning || progress.isCachingCovers,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // The cover pass gets its own spinner and its own sentence. Left as the normal
                    // progress line it would read as "3,847 / 3,847 项 · 100%" for several minutes,
                    // which looks exactly like a hang.
                    val indeterminate = progress.isCachingCovers || progress.estimatedTotal <= 0
                    if (indeterminate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Text(
                        text = if (progress.isCachingCovers) {
                            "正在缓存月份封面…"
                        } else {
                            progressText(progress)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                val fraction = progress.fraction
                if (fraction != null && !progress.isCachingCovers) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * `3,847 / 16,116 项 · 2026年8月` when the total is known, otherwise
 * `已扫描 3,847 项 · 2026年8月`.
 *
 * The month suffix is omitted before the first batch, when it would read as a placeholder.
 */
private fun progressText(progress: ScanProgress): String {
    val position = if (progress.estimatedTotal > 0) {
        "${formatCount(progress.scanned)} / ${formatCount(progress.estimatedTotal)} 项"
    } else {
        "已扫描 ${formatCount(progress.scanned)} 项"
    }
    val month = if (progress.currentYearMonth > 0) {
        " · ${MonthBucketing.labelOf(progress.currentYearMonth)}"
    } else {
        ""
    }
    return position + month
}

/** Thousands separators, matching how the iCloud web page presents its own counts. */
private fun formatCount(value: Int): String = "%,d".format(value)
