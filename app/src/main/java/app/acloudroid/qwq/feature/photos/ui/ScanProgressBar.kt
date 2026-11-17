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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.acloudroid.qwq.R
import app.acloudroid.qwq.feature.photos.data.MonthBucketing
import app.acloudroid.qwq.feature.photos.data.ScanProgress

/**
 * Scanning indicator for the gallery (P2-016).
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
                            stringResource(R.string.scan_progress_caching)
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

@Composable
private fun progressText(progress: ScanProgress): String {
    val context = LocalContext.current
    val position = if (progress.estimatedTotal > 0) {
        stringResource(R.string.scan_progress_items, formatCount(progress.scanned), formatCount(progress.estimatedTotal))
    } else {
        stringResource(R.string.scan_progress_scanned, formatCount(progress.scanned))
    }
    val month = if (progress.currentYearMonth > 0) {
        " · ${MonthBucketing.labelOf(context, progress.currentYearMonth)}"
    } else {
        ""
    }
    return position + month
}

private fun formatCount(value: Int): String = "%,d".format(value)
