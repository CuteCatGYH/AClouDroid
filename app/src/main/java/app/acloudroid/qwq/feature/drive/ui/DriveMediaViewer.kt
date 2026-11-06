package app.acloudroid.qwq.feature.drive.ui

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.acloudroid.qwq.di.rememberAppContainer
import app.acloudroid.qwq.feature.drive.data.DriveRepository
import app.acloudroid.qwq.feature.drive.data.db.DriveItemEntity
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageLoaderFactory
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "DriveMediaViewer"

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "bmp")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "avi", "mkv")

fun isMediaDriveItem(extension: String?): Boolean {
    val ext = extension?.lowercase()?.trim() ?: return false
    return ext in IMAGE_EXTENSIONS || ext in VIDEO_EXTENSIONS
}

fun isVideoDriveItem(extension: String?): Boolean {
    val ext = extension?.lowercase()?.trim() ?: return false
    return ext in VIDEO_EXTENSIONS
}

/**
 * Fullscreen in-app media viewer overlay for Drive images and videos.
 */
@Composable
fun DriveMediaViewer(
    item: DriveItemEntity,
    repository: DriveRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isVideo = isVideoDriveItem(item.extension)

    var mediaUri by remember(item.drivewsid) { mutableStateOf<Uri?>(null) }
    var isLoading by remember(item.drivewsid) { mutableStateOf(true) }
    var errorMessage by remember(item.drivewsid) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.drivewsid) {
        isLoading = true
        errorMessage = null

        // 1. Check if cached in preview or downloads
        val downloadedOutcome = repository.checkDownloadedFile(item)
        if (downloadedOutcome?.uri != null) {
            mediaUri = downloadedOutcome.uri
            isLoading = false
            return@LaunchedEffect
        }

        val cachedFile = repository.previewCache.findPreviewFile(item.drivewsid)
        if (cachedFile != null && cachedFile.length() > 0) {
            mediaUri = Uri.fromFile(cachedFile)
            isLoading = false
            return@LaunchedEffect
        }

        // 2. Otherwise fetch preview stream to sandbox cache
        val result = repository.fetchFileToPreviewCache(item)
        result.fold(
            onSuccess = { file ->
                mediaUri = Uri.fromFile(file)
                isLoading = false
            },
            onFailure = { err ->
                errorMessage = err.message ?: "抓取媒体预览失败"
                isLoading = false
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Media Content Area
        if (mediaUri != null) {
            if (isVideo) {
                DriveVideoPlayer(uri = mediaUri!!)
            } else {
                DriveImageViewer(uri = mediaUri!!, title = item.name)
            }
        }

        // Loading Indicator
        if (isLoading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(modifier = Modifier.size(12.dp))
                Text(text = "正在准备预览...", color = Color.White)
            }
        }

        // Error Message
        if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = errorMessage!!, color = Color.Red)
            }
        }

        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White
                )
            }
            Text(
                text = item.name,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun DriveImageViewer(uri: Uri, title: String) {
    val context = LocalContext.current
    val appContainer = rememberAppContainer()
    val imageLoader = remember(context, appContainer) {
        ICloudImageLoaderFactory.get(context, appContainer)
    }

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(uri)
            .crossfade(true)
            .build(),
        imageLoader = imageLoader,
        contentDescription = title,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun DriveVideoPlayer(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
