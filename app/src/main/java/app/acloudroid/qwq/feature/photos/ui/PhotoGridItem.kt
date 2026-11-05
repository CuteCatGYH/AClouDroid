package app.acloudroid.qwq.feature.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import app.acloudroid.qwq.di.rememberAppContainer
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageLoaderFactory
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageModel
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import java.util.Locale

@Composable
fun PhotoGridItem(
    photo: PhotoAssetEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Reports this cell's layout coordinates, so the viewer can open from exactly where the
     * thumbnail is and close back into it.
     */
    onBoundsChanged: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {}
) {
    val context = LocalContext.current
    val container = rememberAppContainer()
    val imageLoader = ICloudImageLoaderFactory.get(context, container)
    val model = androidx.compose.runtime.remember(photo) {
        ICloudImageModel.fromEntity(photo, ICloudImageModel.Quality.THUMB)
    }
    val imageRequest = androidx.compose.runtime.remember(model, context) {
        ImageRequest.Builder(context)
            .data(model)
            .crossfade(true)
            .build()
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .onGloballyPositioned { coords ->
                onBoundsChanged(coords)
            }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = imageRequest,
            imageLoader = imageLoader,
            contentDescription = photo.filename,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Video Duration Indicator
        if (photo.mediaType == "VIDEO" || photo.duration > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "视频",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = formatDuration(photo.duration),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Live Photo Badge
        if (photo.mediaType == "LIVE_PHOTO" || (photo.mediaType != "VIDEO" && !photo.videoComplUrl.isNullOrBlank())) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "LIVE",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Favorite Indicator
        if (photo.isFavorite) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = "收藏",
                tint = Color(0xFFFF5252),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(16.dp)
            )
        }
    }
}

private fun formatDuration(durationSeconds: Long): String {
    if (durationSeconds <= 0) return "0:00"
    val minutes = durationSeconds / 60
    val seconds = durationSeconds % 60
    val secStr = if (seconds < 10) "0$seconds" else seconds.toString()
    return "$minutes:$secStr"
}
