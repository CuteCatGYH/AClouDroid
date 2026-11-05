package app.acloudroid.qwq.feature.photos.ui

import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import app.acloudroid.qwq.di.rememberAppContainer
import app.acloudroid.qwq.feature.photos.data.PhotoRepository
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageLoaderFactory
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageModel
import app.acloudroid.qwq.feature.photos.data.image.ImageLoadFailure
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * One image page of the full-screen viewer, with a two-layer progressive reveal.
 *
 * ## Why two layers
 *
 * The old viewer showed a spinner until a full-size image arrived. That reads as "broken" on a
 * slow link, and the thumbnail the user just tapped was already decoded and sitting in Coil's
 * memory cache. So:
 *
 *  - **Base layer** asks for the *same* `THUMB` cache key the grid populated, with
 *    [ICloudImageModel.cacheOnly] set and both cache policies read-only. It hits memory almost
 *    always, costing no network at all, and paints immediately.
 *  - **Sharp layer** requests MEDIUM (or ORIGINAL after an explicit zoom) and is faded in once it
 *    resolves, over the base image. Nothing is ever shown as a spinner over an empty screen.
 *
 * ## What must NOT regress
 *
 * This page carries two behaviours that were expensive to get right:
 *
 *  1. **HDR degrade chain (ISS-023).** Apple's high-bit-depth originals (`16-bit PNG`, `10-bit
 *     HEVC`) fail to decode on Android entirely. On a decode failure — as opposed to a dead URL —
 *     the request drops to MEDIUM. The state flip is deferred by one frame (`withFrameNanos`),
 *     because mutating it inline from `onError` does not rebuild the AsyncImage: the callback runs
 *     while Coil is still finishing the failing request, and the new model would be swallowed.
 *     `isDegrading` covers that gap so the error panel is never painted in between.
 *
 *  2. **Dead-URL recovery (P2-004 / P2-011).** A 403/410 means the CDN signature aged out; that is
 *     worth exactly one re-mint. A decoder failure returns the same broken bytes however many new
 *     URLs are minted, so it must NOT burn a lookup (that was P2-011).
 */
@Composable
fun ImageViewerPage(
    photo: PhotoAssetEntity,
    repository: PhotoRepository,
    /** True for the primary rendition (current page), false for neighbours that only pre-warm. */
    isActive: Boolean,
    /**
     * Reports how much the page wants to own horizontal drags.
     *
     * 0 = none (the pager may page), 1 = fully (the image is zoomed and the user is inspecting
     * it). Without this, a horizontal swipe while zoomed would turn the page instead of panning
     * the picture — the single most jarring thing a gallery viewer can do.
     */
    onZoomChanged: (Float) -> Unit = {},
    /** Tap on the image: toggles the viewer chrome (immersive mode). */
    onToggleChrome: () -> Unit = {},
    /** Horizontal drag reported while the image still is not sure whether it is a vertical one. */
    onHorizontalDrag: (drag: Offset) -> Unit = {},

    /** Horizontal drag started: fires once, before any [onHorizontalDrag]. */
    onHorizontalDragStart: () -> Unit = {},
    /** Vertical drag progress: positive = downward (dismiss), negative = upward (details). */
    onVerticalDrag: (travel: Float) -> Unit = {},
    /** Vertical drag released at [travel]. */
    onVerticalDragEnd: (travel: Float) -> Unit = {},
    /**
     * Horizontal drag released.
     *
     * @param totalDragX total horizontal travel of the gesture in px; negative = leftward.
     * @param velocityMagnitude peak horizontal speed of the gesture in px/s, non-negative.
     *   Direction is NOT encoded here — [totalDragX] decides it. Keeping velocity directionless
     *   removes the conflict between `calculatePan` and `VelocityTracker`, which report opposite
     *   signs.
     */
    onHorizontalDragEnd: (totalDragX: Float, velocityMagnitude: Float) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = rememberAppContainer()
    val imageLoader = remember { ICloudImageLoaderFactory.get(context, container) }

    var reloadToken by remember(photo.id) { mutableIntStateOf(0) }
    var retryCount by remember(photo.id) { mutableIntStateOf(0) }
    var loadFailed by remember(photo.id) { mutableStateOf(false) }
    var isUrlIssue by remember(photo.id) { mutableStateOf(false) }
    var degradedToMedium by remember(photo.id) { mutableStateOf(false) }
    var isDegrading by remember(photo.id) { mutableStateOf(false) }

    // Live Photo state and playback
    val isLivePhoto = photo.mediaType == "LIVE_PHOTO" || (photo.mediaType != "VIDEO" && !photo.videoComplUrl.isNullOrBlank())
    var liveVideoUrl by remember(photo.id) { mutableStateOf(photo.videoComplUrl) }
    var isLivePlaying by remember(photo.id) { mutableStateOf(false) }

    val livePlayer = remember(liveVideoUrl, isLivePlaying, isActive) {
        val url = liveVideoUrl
        if (!isLivePhoto || !isLivePlaying || !isActive || url.isNullOrBlank()) null
        else {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            isLivePlaying = false
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "Live Photo playback error: ${error.message}")
                        val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                        if (code == 403 || code == 410) {
                            scope.launch {
                                val refreshed = repository.refreshUrlsForMaster(photo.masterId, force = true).getOrNull()
                                if (refreshed != null && !refreshed.videoComplUrl.isNullOrBlank()) {
                                    liveVideoUrl = refreshed.videoComplUrl
                                } else {
                                    isLivePlaying = false
                                }
                            }
                        } else {
                            isLivePlaying = false
                        }
                    }
                })
            }
        }
    }

    DisposableEffect(livePlayer) {
        onDispose { livePlayer?.release() }
    }

    // Base layer is ready once it has painted something. Until then the screen stays black rather
    // than flashing a spinner or a white frame during the hero transition.
    var baseReady by remember(photo.id) { mutableStateOf(false) }

    val quality = if (degradedToMedium) {
        ICloudImageModel.Quality.MEDIUM
    } else {
        ICloudImageModel.Quality.ORIGINAL
    }

    val baseModel = remember(photo.id) { ICloudImageModel.cacheOnlyThumb(photo) }
    val sharpModel = remember(photo.id, reloadToken, quality) {
        ICloudImageModel.fromEntity(photo, quality)
    }

    // Fade the sharp layer in over the base. 220ms is long enough to read as a blend, short enough
    // that it never feels like waiting.
    var sharpVisible by remember(photo.id, reloadToken) { mutableStateOf(false) }
    val sharpAlpha by animateFloatAsState(
        targetValue = if (sharpVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "sharpFade"
    )

    // Pinch/drag state. Reset per photo so swiping to the next image never inherits a zoom.
    val zoom = remember(photo.id) { ZoomState() }

    // Publish the current zoom to the pager ancestor: while the image is zoomed, a horizontal drag
    // belongs to the image (panning to its edge), not to page navigation.
    LaunchedEffect(photo.id, zoom) {
        snapshotFlow { zoom.isZoomedIn }.collect { onZoomChanged(if (it) 1f else 0f) }
    }


    Box(
        modifier = Modifier
            .fillMaxSize()
            // ## One pointer stream for every gesture on the photo
            //
            // `detectTransformGestures` consumes the pointer as soon as it sees movement, so a
            // vertical drag never reached a detector placed on an ancestor. That is why an
            // ancestor-based "swipe to exit" worked fine while zoom was broken, and stopped
            // working once zoom was fixed: whichever detector sits further out loses the pointer.
            //
            // The fix is to keep the gestures in the SAME detector that owns the pointer. The
            // callback reports pan as well as zoom, so a one-finger vertical drag arrives here as
            // `pan.y` with `zoom == 1` — exactly the signal needed to route it to the exit/detail
            // callbacks.
            // `zoom.isZoomedIn` is deliberately NOT a key.
            //
            // It used to be, with the value snapshotted into a local `atRest`. That caused the
            // two reported symptoms, both of which are the same bug:
            //
            //  1. A pinch zooms the image -> `isZoomedIn` flips true -> this `pointerInput` key
            //     changes -> Compose cancels and restarts the detector MID-GESTURE, while the
            //     fingers are still down. The remaining move events go nowhere, so the pinch
            //     "sticks" part-way.
            //  2. The restart also re-runs the body with a stale `atRest`, and any subsequent
            //     events are interpreted as a fresh gesture — which can undo the zoom.
            //
            // The zoom state is read live from `zoom` inside the loop instead, so a gesture is
            // never torn down half-way.
            .pointerInput(photo.id) {
                awaitEachGesture {
                    // Consume the DOWN so no ancestor (the pager) can claim this gesture.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    // Velocity is tracked alongside the existing axis logic rather than inside
                    // it: the tracker only ever *reads* positions, so it cannot change how a
                    // gesture is classified, and the release decision keeps working exactly as
                    // before when velocity is not a factor.
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                    var totalPan = Offset.Zero
                    // A second finger means pinch: the gesture is never a pager drag.
                    var pinchDetected = false
                    var verticalDecided = false
                    var horizontalMoved = false
                    var verticalTravel = 0f
                    // Whether the image was at rest when the fingers went down.
                    //
                    // Sampled once, at gesture start, and then held for the life of the gesture:
                    // re-reading it every frame would let a drag that started while zoomed turn
                    // into a dismiss gesture the moment the user pinched back out mid-drag.
                    //
                    // Read from `zoom` directly rather than from any state piped through the
                    // parent: that value arrives via `snapshotFlow`, so it lags by at least a frame
                    // and a quick pinch-then-drag would see a stale "not zoomed" answer.
                    val startedAtRest = !zoom.isZoomedIn

                    // Peak horizontal speed (magnitude only) seen during the gesture.
                    //
                    // Direction is NOT tracked here — `totalPan.x` already decides that at release.
                    // Keeping only the magnitude sidesteps the single most error-prone part of this
                    // whole viewer: `calculatePan` and `VelocityTracker` report opposite signs, and
                    // reconciling them has been the cause of several rounds of wrong-direction bugs.
                    //
                    // Velocity is the *peak*, not the value at release. A real finger almost always
                    // decelerates — and often rebounds slightly — before the UP event, so the
                    // instantaneous velocity can be tiny or even point the wrong way. Taking the max
                    // means a genuine fast swipe is never lost to the finger settling at the end.
                    var peakSpeed = 0f

                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) pinchDetected = true

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        // Feed the tracker with this frame's pointer position. Only the primary
                        // pointer matters: a second finger means pinch, whose velocity is
                        // meaningless for paging.
                        event.changes.firstOrNull()?.let { change ->
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                        }

                        // Sample the running velocity and keep only its magnitude.
                        //
                        // `VelocityTracker` reports finger movement, whose sign we deliberately
                        // discard: direction is `totalPan.x`'s job, and reconciling the two sign
                        // conventions (they are opposite) is what has caused every wrong-direction
                        // regression so far.
                        if (horizontalMoved) {
                            val speed = abs(velocityTracker.calculateVelocity().x)
                            if (speed > peakSpeed) peakSpeed = speed
                        }

                        // --- Pinch: always a zoom, never navigation. ---
                        if (pinchDetected) {
                            if (zoomChange != 1f) {
                                zoom.applyGesture(
                                    centroid = event.calculateCentroid(useCurrent = false),
                                    pan = panChange,
                                    zoomFactor = zoomChange
                                )
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        // Once zoomed (from this gesture or a previous one) a one-finger drag is
                        // how you reach the edges of the picture. It must never dismiss or page.
                        if (!startedAtRest) {
                            if (panChange != Offset.Zero) {
                                zoom.applyGesture(
                                    centroid = event.calculateCentroid(useCurrent = false),
                                    pan = panChange,
                                    zoomFactor = 1f
                                )
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        totalPan += panChange

                        // Decide the axis once, then commit. A vertical drag must not also pan the
                        // image, and a horizontal one must reach the pager.
                        if (!verticalDecided && !horizontalMoved &&
                            abs(totalPan.y) > abs(totalPan.x) * VERTICAL_DOMINANCE &&
                            abs(totalPan.y) > TOUCH_SLOP
                        ) {
                            verticalDecided = true
                        } else if (!verticalDecided && !horizontalMoved &&
                            abs(totalPan.x) > abs(totalPan.y) * VERTICAL_DOMINANCE &&
                            abs(totalPan.x) > TOUCH_SLOP
                        ) {
                            horizontalMoved = true
                            // Fire once, the moment the axis is decided: the caller uses this to
                            // remember which page the gesture began on.
                            onHorizontalDragStart()
                        }

                        when {
                            verticalDecided -> {
                                verticalTravel = totalPan.y
                                event.changes.forEach { it.consume() }
                                onVerticalDrag(verticalTravel)
                            }
                            horizontalMoved -> {
                                event.changes.forEach { it.consume() }
                                onHorizontalDrag(panChange)
                            }
                            // No axis chosen yet: hold the movement back rather than panning the
                            // image, so a tap stays a tap.
                            else -> Unit
                        }
                    } while (event.changes.any { it.pressed })

                    // Released: commit or snap back.
                    when {
                        verticalDecided -> onVerticalDragEnd(verticalTravel)
                        horizontalMoved -> {
                            // Magnitude only. Direction is exactly `totalPan.x`'s sign, and
                            // passing both risks the two sign conventions disagreeing.
                            onHorizontalDragEnd(totalPan.x, peakSpeed)
                        }
                        else -> Unit
                    }
                }
            }
            .pointerInput(photo.id, isLivePhoto, liveVideoUrl) {
                // Double-tap zooms about the tapped point. `detectTapGestures` is a separate
                // pointerInput so it does not compete with the transform detector above.
                detectTapGestures(
                    onDoubleTap = { point ->
                        // Animated rather than applied instantly: a snap to 1.8x reads as a jump
                        // cut, while a short spring makes the gesture feel like it "did" something.
                        scope.launch { zoom.animateToggle(point) }
                    },
                    onTap = { onToggleChrome() },
                    onLongPress = {
                        if (isLivePhoto) {
                            if (liveVideoUrl.isNullOrBlank()) {
                                scope.launch {
                                    val refreshed = repository.refreshUrlsForMaster(photo.masterId, force = true).getOrNull()
                                    if (!refreshed?.videoComplUrl.isNullOrBlank()) {
                                        liveVideoUrl = refreshed?.videoComplUrl
                                        isLivePlaying = true
                                    }
                                }
                            } else {
                                isLivePlaying = true
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // ## Where the zoom transform must live
        //
        // The transform is applied to the OUTER box, not to the images. Applying it to each image
        // scaled the bitmap inside its own layout bounds — but those bounds never grow, so the
        // enlarged image was simply clipped back to the original rectangle. On screen that reads
        // as "the picture zooms behind its own frame": the visible area stayed the same size and
        // only the content inside it changed.
        //
        // Putting the transform on the container lets the scaled content draw outside the original
        // bounds and be clipped by the viewer (which fills the screen) instead.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offsetX
                    translationY = zoom.offsetY
                }
                .onSizeChanged { size ->
                    // The image is drawn with `ContentScale.Fit`, so the drawn content is the
                    // container scaled down to the image's aspect ratio. That is what the pan clamp
                    // needs.
                    val containerSize = androidx.compose.ui.geometry.Size(
                        size.width.toFloat(),
                        size.height.toFloat()
                    )
                    zoom.onContainerSized(containerSize, fittedSize(containerSize, photo))
                }
        ) {
        // ---- Base layer: cached thumbnail, read-only, no network, no URL refresh ----
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(baseModel)
                .memoryCachePolicy(CachePolicy.READ_ONLY)
                .diskCachePolicy(CachePolicy.READ_ONLY)
                .networkCachePolicy(CachePolicy.DISABLED)
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onSuccess = { baseReady = true },
            // No loading/error slots for the base layer: it is decoration. A miss is silent and
            // the sharp layer covers for it.
            modifier = Modifier.fillMaxSize()
        )

        // ---- Sharp layer: the real image ----
        // Kept out of the tree until the sharp request is worth showing, and always drawn *above*
        // the base so the fade uncovers detail rather than hiding it.
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(sharpModel)
                // Bound the decode to a sane resolution; 12288x8192 drone shots would otherwise
                // need ~400MB for a single bitmap.
                .size(2048)
                .build(),
            imageLoader = imageLoader,
            contentDescription = photo.filename,
            contentScale = ContentScale.Fit,
            onSuccess = { sharpVisible = true },
            onError = { state ->
                val throwable = state.result.throwable
                val httpCode = ImageLoadFailure.httpCodeOf(throwable)
                isUrlIssue = ImageLoadFailure.isExpiredUrl(throwable)

                when {
                    isUrlIssue && retryCount < MAX_IMAGE_RETRY -> {
                        retryCount++
                        Log.i(
                            TAG,
                            "image url expired (HTTP $httpCode), re-minting master=${photo.masterId}, " +
                                "retry=$retryCount"
                        )
                        scope.launch {
                            // force = true: a deliberate single-asset action must not be vetoed by
                            // the grid-thumbnail dedup window (P2-011).
                            val refreshed = repository.refreshUrlsForMaster(photo.masterId, force = true)
                            if (refreshed.isSuccess) {
                                reloadToken++
                            } else {
                                loadFailed = true
                            }
                        }
                    }

                    // Not a URL problem -> almost certainly the high-bit-depth decode failure.
                    // Drop to the medium rendition and try once more (ISS-023).
                    !isUrlIssue && quality == ICloudImageModel.Quality.ORIGINAL -> {
                        Log.w(
                            TAG,
                            "original decode failed master=${photo.masterId} " +
                                "error=${throwable.message}, degrading to MEDIUM"
                        )
                        isDegrading = true
                        scope.launch {
                            // Deferred by one frame on purpose: see this function's header.
                            withFrameNanos { }
                            degradedToMedium = true
                        }
                    }

                    else -> {
                        Log.w(
                            TAG,
                            "image decode failed master=${photo.masterId} " +
                                "quality=$quality error=${throwable.message}"
                        )
                        isDegrading = false
                        loadFailed = true
                    }
                }
            },
            // No spinner slot. The base layer is the loading state.
            loading = {},
            error = {
                // Only surface a failure when there is nothing behind it either — otherwise the
                // (possibly blurry) base image is a better answer than an error card.
                if (!baseReady && !isDegrading) {
                    ViewerErrorPanel(
                        message = when {
                            isUrlIssue -> "原图加载失败（链接已失效）"
                            degradedToMedium -> "该图片为 HDR 高动态范围格式，暂不支持预览\n可点击下方按钮下载原图"
                            else -> "原图无法解码（该格式暂不支持）"
                        }
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = sharpAlpha }
        )

        if (isLivePlaying && livePlayer != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = livePlayer
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (!baseReady && !sharpVisible && !loadFailed) {
            // Nothing at all to show yet: stay black instead of a spinner. This is the frame or
            // two after the hero transition starts, before the cached bitmap is painted.
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }
        } // end transform container

        if (isLivePhoto) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 16.dp, start = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable {
                        if (liveVideoUrl.isNullOrBlank()) {
                            scope.launch {
                                val refreshed = repository.refreshUrlsForMaster(photo.masterId, force = true).getOrNull()
                                if (!refreshed?.videoComplUrl.isNullOrBlank()) {
                                    liveVideoUrl = refreshed?.videoComplUrl
                                    isLivePlaying = !isLivePlaying
                                }
                            }
                        } else {
                            isLivePlaying = !isLivePlaying
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Live Photo",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (isLivePlaying) "LIVE 播放中" else "LIVE (点击/长按播放)",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Video page.
 *
 * The player is created only while [isActive] and released the moment the page scrolls out of the
 * window. Holding several `ExoPlayer`s alive exhausts hardware decoders quickly (3–4 instances is
 * enough to break `MediaCodec` on an emulator), so this is not an optimisation — it is required.
 *
 * URL re-minting (P2-002) and the human-readable failure states (P2-007) are preserved as-is.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoViewerPage(
    photo: PhotoAssetEntity,
    repository: PhotoRepository,
    isActive: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val initialUrl = remember(photo.id) {
        photo.originalUrl ?: photo.medUrl ?: photo.thumbUrl
    }

    var currentUrl by remember(photo.id) { mutableStateOf(initialUrl) }
    var retryCount by remember(photo.id) { mutableIntStateOf(0) }
    var playerError by remember(photo.id) { mutableStateOf<String?>(null) }
    var lastPosition by remember(photo.id) { mutableLongStateOf(0L) }

    if (currentUrl.isNullOrBlank()) {
        ViewerErrorPanel(message = "无法播放：无有效视频播放链接")
        return
    }

    if (!isActive) {
        // Not the focused page: keep a static thumbnail instead of a decoder.
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(ICloudImageModel.cacheOnlyThumb(photo))
                .memoryCachePolicy(CachePolicy.READ_ONLY)
                .diskCachePolicy(CachePolicy.READ_ONLY)
                .networkCachePolicy(CachePolicy.DISABLED)
                .build(),
            imageLoader = ICloudImageLoaderFactory.get(context, rememberAppContainer()),
            contentDescription = photo.filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    fun handleExpiredUrl(code: Int) {
        if (retryCount >= MAX_VIDEO_RETRY) {
            playerError = "视频链接已过期 (HTTP $code)，多次刷新仍失败，请稍后重试"
            return
        }
        retryCount++
        Log.i(
            TAG,
            "video url expired (HTTP $code), refreshing master=${photo.masterId}, retry=$retryCount"
        )
        scope.launch {
            val refreshed = repository.refreshUrlsForMaster(photo.masterId).getOrNull()
            val newUrl = refreshed?.originalUrl ?: refreshed?.medUrl ?: refreshed?.thumbUrl
            if (!newUrl.isNullOrBlank() && newUrl != currentUrl) {
                currentUrl = newUrl
                playerError = null
            } else {
                playerError = "视频链接刷新失败 (HTTP $code)"
            }
        }
    }

    val exoPlayer = remember(currentUrl, isActive) {
        val url = currentUrl ?: return@remember null
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            if (lastPosition > 0) seekTo(lastPosition)
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    lastPosition = currentPosition
                    val code =
                        (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode

                    when {
                        code == 410 || code == 403 -> handleExpiredUrl(code)
                        code == 404 -> playerError = "视频已从云端删除"
                        code != null -> playerError = "网络错误 (HTTP $code)"
                        else -> playerError = "播放失败：${error.errorCodeName}"
                    }
                }
            })
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer?.release() }
    }

    if (playerError != null) {
        ViewerErrorPanel(
            title = "在线播放失败",
            message = playerError ?: "",
            actionLabel = "重试"
        ) {
            retryCount = 0
            playerError = null
            scope.launch {
                val refreshed = repository.refreshUrlsForMaster(photo.masterId).getOrNull()
                val newUrl = refreshed?.originalUrl ?: refreshed?.medUrl ?: refreshed?.thumbUrl
                if (!newUrl.isNullOrBlank()) {
                    currentUrl = newUrl
                } else {
                    playerError = "视频链接刷新失败，请检查网络后重试"
                }
            }
        }
    } else {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Shared error surface for both page types, so failures look the same everywhere. */
@Composable
fun ViewerErrorPanel(
    message: String,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = Color(0xFFFF5252),
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.LightGray,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

private const val TAG = "PhotoViewerPages"
private const val MAX_IMAGE_RETRY = 2
private const val MAX_VIDEO_RETRY = 2

/** Travel (px) before a one-finger drag is classified as vertical rather than a tap. */
private const val TOUCH_SLOP = 24f

/**
 * How much more vertical than horizontal a drag must be before it counts as a vertical drag.
 *
 * A small bias rather than a strict `>` test: a finger almost never moves on exactly one axis, and
 * requiring pure vertical input would make the gesture feel unresponsive.
 */
private const val VERTICAL_DOMINANCE = 1.2f

/**
 * Size the image actually occupies inside [container] under `ContentScale.Fit`.
 *
 * This is what the pan clamp needs: the movement limit of a zoomed image depends on the *drawn*
 * size, not on the viewport. Aspect ratio comes from the entity's recorded dimensions; when those
 * are missing (Go reports 0 for some orphans) we assume the container shape, which degrades
 * gracefully to "no extra pan range" rather than producing a wrong one.
 */
private fun fittedSize(
    container: androidx.compose.ui.geometry.Size,
    photo: PhotoAssetEntity
): androidx.compose.ui.geometry.Size {
    if (container.width <= 0f || container.height <= 0f) return container
    if (photo.width <= 0 || photo.height <= 0) return container

    val imageAspect = photo.width.toFloat() / photo.height.toFloat()
    val containerAspect = container.width / container.height
    return if (imageAspect >= containerAspect) {
        // Image is relatively wider: it fills the width.
        androidx.compose.ui.geometry.Size(container.width, container.width / imageAspect)
    } else {
        androidx.compose.ui.geometry.Size(container.height * imageAspect, container.height)
    }
}
