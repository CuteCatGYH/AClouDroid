package app.acloudroid.qwq.feature.update.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.acloudroid.qwq.feature.update.data.ReleaseInfo

/**
 * Dialog displaying update notes and action button to open GitHub release page in browser.
 */
@Composable
fun UpdateDialog(
    releaseInfo: ReleaseInfo,
    onDismiss: () -> Unit,
    onConfirmUpdate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    onConfirmUpdate()
                    onDismiss()
                }
            ) {
                Text("前往 GitHub 更新")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("暂不更新")
            }
        },
        title = {
            Text(
                text = "发现新版本 ${releaseInfo.tagName}",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (releaseInfo.publishedAt.isNotBlank()) {
                    Text(
                        text = "发布时间: ${releaseInfo.publishedAt.take(10)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = "更新说明：",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = releaseInfo.releaseNotes.ifBlank { "暂无详细更新说明" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}
