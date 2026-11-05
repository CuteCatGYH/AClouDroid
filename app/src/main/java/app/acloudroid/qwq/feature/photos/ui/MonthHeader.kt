package app.acloudroid.qwq.feature.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Sticky month title above each month's grid (P2-016).
 *
 * Height is fixed (a single text line plus padding) so the sticky header does not resize as the
 * months swap — a varying header height would shift the content underneath it while scrolling.
 *
 * The count is shown because it is the only place the gallery expresses "this month is still
 * filling in" while the scan runs.
 */
@Composable
fun MonthHeader(
    label: String,
    itemCount: Int,
    isComplete: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (isComplete) "$itemCount 项" else "$itemCount 项 · 扫描中",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
