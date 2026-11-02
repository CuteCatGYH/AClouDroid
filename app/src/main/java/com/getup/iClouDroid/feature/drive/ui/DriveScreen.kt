package com.getup.iClouDroid.feature.drive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.getup.iClouDroid.di.rememberAppContainer
import com.getup.iClouDroid.feature.drive.data.db.DriveItemEntity
import com.getup.iClouDroid.feature.photos.data.image.ICloudImageLoaderFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Entry point that builds the [DriveViewModel] from the app container.
 *
 * Kept separate from [DriveScreen] so the screen itself stays a pure function of its state —
 * which is what makes it reviewable without knowing how the container is wired.
 */
@Composable
fun DriveEntry(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    val container = rememberAppContainer()
    val viewModel = remember(container) {
        DriveViewModel(container.driveRepository)
    }
    DriveScreen(
        viewModel = viewModel,
        modifier = modifier,
        contentPadding = contentPadding
    )
}

/**
 * iCloud Drive browser.
 *
 * ## Layout rules that come from earlier bugs
 *
 * - **Single `LazyColumn`.** No nested lazy grid (PITFALLS §10): the photo gallery lost rows to
 *   exactly that, because two independent measure loops desynchronise during a fling.
 * - **Back is handled here, not by the system stack.** This screen is a tab inside `AppShell`,
 *   not a route, so `BackHandler` pops one folder level and only exits the app at the root
 *   (PITFALLS §20 — otherwise a side-swipe exits the app from three folders deep).
 */
@Composable
fun DriveScreen(
    viewModel: DriveViewModel,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val container = rememberAppContainer()
    val imageLoader = ICloudImageLoaderFactory.get(context, container)

    // Intercept back while inside a folder. At the root we let the event through so the shell
    // behaves normally.
    BackHandler(enabled = state.canGoUp) {
        viewModel.goUp()
    }

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        DriveHeader(
            state = state,
            onCrumbClick = viewModel::navigateTo,
            onRefresh = viewModel::refresh
        )

        if (state.isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.errorMessage?.let { message ->
            DriveErrorBanner(message = message, onDismiss = viewModel::dismissError)
        }

        when {
            state.isInitialLoading && state.items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.isRefreshing) "正在读取…" else "此文件夹为空",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.items, key = { it.drivewsid }) { item ->
                        DriveItemRow(
                            item = item,
                            imageLoader = imageLoader,
                            onClick = { viewModel.open(item) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveHeader(
    state: DriveUiState,
    onCrumbClick: (Int) -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.currentName,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        if (state.trail.size > 1) {
            // Horizontally scrollable so a deep path does not squash the names.
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(state.trail.size) { index ->
                    val crumb = state.trail[index]
                    val isLast = index == state.trail.lastIndex
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = crumb.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isLast) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(enabled = !isLast) { onCrumbClick(index) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        if (!isLast) {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "知道了",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun DriveItemRow(
    item: DriveItemEntity,
    imageLoader: coil.ImageLoader,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.isContainer, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DriveItemIcon(item = item, imageLoader = imageLoader)

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name.ifBlank { "未命名" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = subtitleFor(item)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (item.isContainer) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun DriveItemIcon(item: DriveItemEntity, imageLoader: coil.ImageLoader) {
    val boxModifier = Modifier
        .size(40.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)

    val remoteIcon = item.iconUrl
    when {
        // APP_LIBRARY ships real app icons; prefer them over the generic folder glyph, because
        // that is the only visual cue distinguishing "an app's container" from "a folder"
        // (they behave identically otherwise — REFERENCE.md §5.1).
        !remoteIcon.isNullOrBlank() -> {
            Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = remoteIcon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    imageLoader = imageLoader,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        item.isContainer -> {
            Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        else -> {
            Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Second line of a row: size and date for files, child count for folders.
 *
 * Each part is omitted when the API did not send it — `APP_LIBRARY` has no `size` and no
 * `dateModified` at all (only `dateCreated`), so a template assuming them would leave a row of
 * stray separators (the same defect class as REFERENCE.md §5.3).
 */
private fun subtitleFor(item: DriveItemEntity): String {
    val parts = mutableListOf<String>()

    if (item.type == DriveItemEntity.TYPE_FILE && item.size > 0) {
        parts += formatSize(item.size)
    }

    if (item.isContainer && item.directChildrenCount > 0) {
        parts += "${item.directChildrenCount} 项"
    } else if (item.isContainer && !item.childrenFetched) {
        // Count not yet known to be accurate — say nothing rather than "0 项".
    }

    val stamp = item.dateModified ?: item.dateCreated
    formatIsoDate(stamp)?.let { parts += it }

    return parts.joinToString(" · ")
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private val ISO_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

/**
 * Formats the API's ISO8601 timestamps for display.
 *
 * Returns null on anything unparseable instead of throwing: a malformed date must cost one
 * missing subtitle, never a crash inside a row. Note these are strings, **not** epoch millis —
 * reusing the photo date path here would render 1970 for every row.
 */
private fun formatIsoDate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val instant = Instant.parse(raw)
        ISO_FORMATTER.withZone(ZoneId.systemDefault()).format(instant)
    }.getOrNull()
}
