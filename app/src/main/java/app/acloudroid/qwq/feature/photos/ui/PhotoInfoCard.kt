package app.acloudroid.qwq.feature.photos.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.acloudroid.qwq.feature.photos.data.PhotoExif
import app.acloudroid.qwq.feature.photos.data.PhotoExifParser
import app.acloudroid.qwq.feature.photos.data.PhotoRepository
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Metadata card for the info panel.
 *
 * ## Layout
 *
 * A rounded sheet rather than a Material `ModalBottomSheet`, for two reasons: `ModalBottomSheet`
 * is a `Dialog` under the hood (so the viewer could not share composition with the grid), and a
 * plain surface can be offset-animated by its parent without stealing the back gesture.
 *
 * The information is laid out the way a native gallery does it: a big date/time header, then rows
 * of `icon + primary value + muted secondary detail`, grouped by concern (camera / exposure /
 * location / file).
 *
 * ## Missing data is expected, not exceptional
 *
 * Measured on a real library (docs/REFERENCE.md §2A): only about a third of records carry a
 * `{GPS}` block, and imports or screenshots may have no metadata at all. So the card **renders
 * whatever exists** and omits the rest:
 *
 *  - a row whose every field is absent is not rendered at all (no "Unknown" placeholders);
 *  - a group with no surviving rows disappears entirely (no empty "位置" section);
 *  - when there is no metadata at all, the file section still renders, so the card is never blank.
 */
@Composable
fun PhotoInfoCard(
    photo: PhotoAssetEntity,
    repository: PhotoRepository,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Ceiling for the card, so a photo with a very long metadata list scrolls instead of taking
    // over the screen. Short cards stay short.
    val maxCardHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f

    // Drag-to-dismiss for the card itself: a downward drag on the header closes it, mirroring the
    // viewer's own pull-down gesture. The sheet had no way out other than the small X before.
    var dragDown by remember { mutableFloatStateOf(0f) }

    // `photo.exifJson` is already the cache: null = never fetched, "" = fetched/found nothing.
    var exifJson by remember(photo.id) { mutableStateOf(photo.exifJson) }
    var loading by remember(photo.id) { mutableStateOf(false) }
    var error by remember(photo.id) { mutableStateOf<String?>(null) }

    // Reset the drag offset whenever a different photo is shown, so the card never opens already
    // translated off-screen.
    LaunchedEffect(photo.id) {
        dragDown = 0f
    }

    LaunchedEffect(photo.id) {
        if (exifJson != null) return@LaunchedEffect
        loading = true
        error = null
        val result = repository.fetchMetadata(photo.masterId)
        result.onSuccess { exifJson = it }
            .onFailure { error = it.message ?: "获取照片信息失败" }
        loading = false
    }

    val exif = remember(exifJson) { PhotoExifParser.parse(exifJson) }

    // Height follows the content: an opaque card hugging its rows, not a full-screen sheet.
    // The earlier version filled the screen, which put a large block of background between the
    // photo and the information actually wanted.
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        // Fully opaque. A translucent surface let the photo bleed through and made the small text
        // hard to read against bright images.
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxCardHeight)
            .graphicsLayer { translationY = dragDown }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 24.dp)
        ) {
            // ---- Header: date + time, then close ----
            //
            // Also the drag handle: pulling down anywhere on the header dismisses the card, which
            // is the gesture people try first on a sheet. The X button remains for discoverability.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var local = 0f
                        detectVerticalDragGestures(
                            onDragStart = { local = 0f },
                            onDragEnd = {
                                // A short drag is a nudge, not a dismissal.
                                if (local > CARD_DISMISS_PX) onDismiss() else dragDown = 0f
                            },
                            onDragCancel = { dragDown = 0f }
                        ) { _, amount ->
                            // Only downward drags move the card; upward would imply "expand",
                            // which this sheet does not support.
                            local = (local + amount).coerceAtLeast(0f)
                            dragDown = local
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = infoDate(photo.assetDate),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = infoTime(photo.assetDate),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                loading -> LoadingSkeleton()
                error != null -> ErrorRow(
                    message = error!!,
                    onRetry = {
                        scope.launch {
                            loading = true
                            error = null
                            repository.fetchMetadata(photo.masterId, force = true)
                                .onSuccess { exifJson = it }
                                .onFailure { error = it.message }
                            loading = false
                        }
                    }
                )
                else -> MetadataBody(photo = photo, exif = exif, context = context)
            }
        }
    }
}

/**
 * Renders the sections that have content.
 *
 * Each helper returns `null` when there is nothing to show, and the section is skipped. That is the
 * whole mechanism behind "render what exists" — there is no branch that produces a placeholder row.
 */
@Composable
private fun MetadataBody(photo: PhotoAssetEntity, exif: PhotoExif?, context: Context) {
    // ---- Camera ----
    val cameraPrimary = listOfNotNull(exif?.cameraMake, exif?.cameraModel)
        .joinToString(" ")
        .trim()
        .ifBlank { null }
    if (cameraPrimary != null || exif?.lensModel != null) {
        InfoRow(
            icon = Icons.Default.CameraAlt,
            primary = cameraPrimary ?: exif?.lensModel.orEmpty(),
            // Lens is secondary detail — shown under the body when both exist, alone otherwise.
            secondary = if (cameraPrimary != null) exif?.lensModel else null
        )
    }

    // ---- Exposure: joined into one row, as a gallery does ("1/739s · f/1.85 · 2.2mm") ----
    val exposureParts = buildList {
        PhotoInfoFormat.shutter(exif?.exposureTime)?.let { add(it) }
        PhotoInfoFormat.aperture(exif?.fNumber)?.let { add(it) }
        PhotoInfoFormat.focalLength(exif?.focalLength, exif?.focalLength35)?.let { add(it) }
    }
    val exposureSecondary = listOfNotNull(
        PhotoInfoFormat.iso(exif?.iso),
        PhotoInfoFormat.exposureBias(exif?.exposureBias)?.takeIf { it != "0 EV" }
    ).joinToString(" · ").ifBlank { null }

    if (exposureParts.isNotEmpty() || exposureSecondary != null) {
        InfoRow(
            icon = Icons.Default.Tune,
            primary = exposureParts.joinToString(" · ").ifBlank { exposureSecondary.orEmpty() },
            secondary = if (exposureParts.isNotEmpty()) exposureSecondary else null
        )
    }

    // ---- Location: the entire group vanishes when there is no fix ----
    exif?.gps?.let { gps ->
        LocationRow(
            coordinates = PhotoInfoFormat.coordinates(gps.latitude, gps.longitude),
            detail = listOfNotNull(
                PhotoInfoFormat.altitude(gps.altitude),
                PhotoInfoFormat.direction(gps.imgDirection)
            ).joinToString(" · ").ifBlank { null },
            onCopy = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(
                    ClipData.newPlainText(
                        "坐标",
                        "${gps.latitude}, ${gps.longitude}"
                    )
                )
                Toast.makeText(context, "坐标已复制", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ---- File: always rendered, so the card is never empty ----
    val dimensions = if (photo.width > 0 && photo.height > 0) {
        "${photo.width} × ${photo.height}"
    } else {
        null
    }
    // UTI first, colour model as the fallback label, then dimensions. Any of them may be absent.
    val formatBits = listOfNotNull(
        photo.itemType.ifBlank { exif?.colorModel.orEmpty() }.ifBlank { null },
        dimensions
    ).joinToString(" · ").ifBlank { null }

    val sizeText = if (photo.size > 0) Formatter.formatFileSize(context, photo.size) else null

    InfoRow(
        icon = Icons.Default.Photo,
        primary = photo.filename,
        secondary = formatBits,
        trailing = sizeText
    )

    // Explain a short card rather than leaving the user wondering whether it failed to load.
    //
    // The test is "is there anything a camera would have written?", NOT `hasMetadata`. A screenshot
    // carries a plist with only a `{PNG}` block: `hasMetadata` is true, yet there is no camera, no
    // exposure and no location, so the card renders the file row alone. Verified on device with
    // IMG_2122.PNG (166-byte payload, hasMetadata=true, zero camera fields).
    if (!hasCameraInfo(exif)) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "这张照片没有附带拍摄信息（可能是截图或由其他应用导入）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 40.dp, end = 16.dp, top = 4.dp)
        )
    }
}

/**
 * True when the record carries at least one field only a camera would produce.
 *
 * File properties (dimensions, colour model) deliberately do not count — every asset has those,
 * including screenshots.
 */
private fun hasCameraInfo(exif: PhotoExif?): Boolean {
    if (exif == null) return false
    return !exif.cameraMake.isNullOrBlank() ||
        !exif.cameraModel.isNullOrBlank() ||
        !exif.lensModel.isNullOrBlank() ||
        exif.fNumber != null ||
        exif.exposureTime != null ||
        exif.iso != null ||
        exif.focalLength != null ||
        exif.gps != null
}

/** One `icon + primary/secondary` row. */
@Composable
private fun InfoRow(
    icon: ImageVector,
    primary: String,
    secondary: String? = null,
    trailing: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (!secondary.isNullOrBlank()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!trailing.isNullOrBlank()) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Location row is tappable: copies the coordinates. */
@Composable
private fun LocationRow(coordinates: String, detail: String?, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = coordinates,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = detail?.let { "$it · 点击复制坐标" } ?: "点击复制坐标",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Placeholder while metadata is in flight — bars, not a spinner, so the card does not jump. */
@Composable
private fun LoadingSkeleton() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        RoundedCornerShape(8.dp)
                    )
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "正在读取拍摄信息…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

/**
 * Downward travel (px) on the card header before release dismisses it.
 *
 * Small on purpose: the card is a peek panel, so getting rid of it should feel immediate. It only
 * exists to reject the single stray pixel of movement in a tap.
 */
private const val CARD_DISMISS_PX = 120f

private fun infoDate(timestamp: Long): String {
    if (timestamp <= 0) return "未知日期"
    return SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(timestamp))
}

private fun infoTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
