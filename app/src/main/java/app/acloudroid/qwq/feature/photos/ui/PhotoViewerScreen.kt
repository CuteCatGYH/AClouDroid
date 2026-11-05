package app.acloudroid.qwq.feature.photos.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.acloudroid.qwq.BuildConfig
import app.acloudroid.qwq.feature.photos.data.PhotoRepository
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import app.acloudroid.qwq.ui.HideSystemBars
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen photo/video viewer.
 *
 * ## Layout
 *
 * Mirrors a native gallery rather than a toolbar-heavy Material screen: black background, back
 * arrow top-left, date/time top-right, the image filling the middle, one row of icon actions at the
 * bottom. Chrome fades so the photo is what the user looks at.
 *
 * ## Full screen and immersive mode
 *
 * The viewer hides the status and navigation bars via [HideSystemBars]. On top of that, tapping the
 * photo toggles "immersive": no back arrow, no date, no action row — just the picture. Tapping again
 * brings the chrome back. This is the two-level model every gallery uses: first tap = clean image,
 * second tap = controls.
 *
 * ## Gestures
 *
 *  - **Pinch** to zoom (about the gesture centroid, clamped so no gap appears).
 *  - **Double-tap** to toggle between fit and ~2.5x, zooming about the tapped point.
 *  - **Drag down** from fit to dismiss: the surface follows the finger, fades the backdrop and
 *    shrinks slightly; release past a distance/velocity threshold closes the viewer.
 *  - **Drag while zoomed** pans the image instead, so the dismiss gesture never fights paging.
 *
 * Paging is disabled while the image is zoomed (`userScrollEnabled`), otherwise a horizontal swipe
 * meant for panning would turn the page.
 */
@Composable
fun PhotoViewerScreen(
    photos: List<PhotoAssetEntity>,
    initialIndex: Int,
    repository: PhotoRepository,
    downloadState: DownloadState,
    onDismiss: () -> Unit,
    onDownload: (PhotoAssetEntity) -> Unit,
    onShare: (PhotoAssetEntity) -> Unit = {},
    isSharing: Boolean = false,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Bounds of the thumbnail this viewer was opened from, in window coordinates.
     *
     * Drives the "from where it came" transition. Null when the thumbnail is no longer laid out
     * (e.g. the grid scrolled away) — the viewer then simply fades, rather than animating to a
     * stale rectangle and looking like a glitch.
     */
    anchorBounds: Rect? = null,
    boundsTracker: ThumbnailBoundsTracker? = null,
    /**
     * The open/close animation state, owned by the caller.
     *
     * Hoisted because the grid behind the viewer fades out on the same progress. If the viewer
     * ran its own copy the two layers would animate independently and the crossfade would tear.
     */
    transition: ViewerTransitionState = rememberViewerTransitionState(),
    /**
     * Reports when the viewer is visually alone on screen ([true]) and when the grid is about to
     * come back ([false]).
     *
     * Deliberately edge-triggered from the animation, not the state: the host's top bar and the
     * shell's bottom navigation must disappear only once the grid has finished fading out, and
     * come back before it starts fading in. Firing on the state change made the list jump.
     */
    onTransitionSettled: (visible: Boolean) -> Unit = {}
) {
    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, photos.lastIndex),
        pageCount = { photos.size }
    )

    var showInfo by remember { mutableStateOf(false) }

    // Two independent visibility axes:
    //  - chromeVisible: does the back arrow / date / action row exist?
    //  - immersive:     user asked for "just the photo".
    // Tapping toggles the first; `immersive` is the same thing with the system bars also implied.
    var chromeVisible by remember { mutableStateOf(true) }
    var immersive by remember { mutableStateOf(false) }

    // Drag-to-dismiss progress, 0..1.
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    /**
     * The page the current horizontal gesture started on.
     *
     * Captured when the drag is recognised, before any preview scrolling. The settle target is
     * computed from this rather than from `pagerState.currentPage`, which rounds to the nearest
     * page and would therefore double-count the preview movement (jumping two pages, or pointing
     * back at the page just left and sticking between the two).
     */
    var dragStartPage by remember { mutableIntStateOf(0) }

    // Drives manual pager scrolling for horizontal drags that the page's detector owns.
    val pagerScope = rememberCoroutineScope()
    val scope = rememberCoroutineScope()

    /**
     * The single coroutine currently applying preview scroll. Replaced per drag callback and
     * cancelled when the gesture ends.
     *
     * Keeping ONE handle (rather than a `launch` per frame) is what stops the preview from running
     * on after the finger lifts: leftover scroll steps would otherwise still be in flight when the
     * settle animation starts, and the pager would end up a page away from the intended target.
     */
    var previewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    /** Applies one preview scroll step, cancelling any step still in flight. */
    fun previewScrollBy(delta: Float) {
        previewJob?.cancel()
        previewJob = pagerScope.launch { pagerState.scrollBy(delta) }
    }

    // Commit distance for drag-to-dismiss: ~22% of the screen height. Expressed in dp and converted
    // with the composition's density so it matches what the user perceives as "I dragged it far".
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val dismissThresholdPx = remember(configuration.screenHeightDp, density) {
        with(density) { (configuration.screenHeightDp * 0.22f).dp.toPx() }
    }

    // Screen width in px. One page is exactly one screen wide, so this is also the "how far is one
    // page" unit used by the horizontal settle decision.
    val screenWidthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.toPx() }
    }

    // Swipe-up threshold for the details page. Deliberately shorter than the dismiss distance:
    // "go look at the details" is a cheap, reversible action, whereas dismissing ends the session.
    val detailsThresholdPx = remember(configuration.screenHeightDp, density) {
        with(density) { (configuration.screenHeightDp * 0.10f).dp.toPx() }
    }

    // Only the settled page may hold a video decoder, and only the current page's zoom may block
    // paging.
    var pageZoom by remember { mutableFloatStateOf(0f) }

    // ---- Open / close transition ------------------------------------------------------------
    //
    // The viewer grows out of the tapped thumbnail and shrinks back into it. `transition.progress`
    // runs 0..1 and the content layer is drawn inside the interpolated rectangle. The state itself
    // is owned by the caller (see the parameter doc) so the grid behind can fade on the same value.
    val transitionScope = rememberCoroutineScope()

    val initialWindowWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    val initialWindowHeight = with(density) { configuration.screenHeightDp.dp.toPx() }
    var windowSize by remember {
        mutableStateOf(Size(initialWindowWidth, initialWindowHeight))
    }

    val currentPage = pagerState.settledPage.coerceIn(0, photos.lastIndex)
    val current = photos[currentPage]

    val currentPhotoId = current.id
    val currentAnchor = remember(currentPhotoId, anchorBounds, boundsTracker) {
        currentPhotoId.let { boundsTracker?.boundsOf(it) } ?: anchorBounds
    }

    LaunchedEffect(currentAnchor) {
        transition.setAnchor(currentAnchor)
    }

    // Open: the host chrome (top bar + bottom navigation) must go away BEFORE the first frame of
    // the motion, not after it.
    //
    // Doing it at the end looked wrong in a way that is easy to describe: the grid starts fading
    // and growing, and only once the photo has landed does the navigation bar suddenly vanish —
    // the shell visibly re-lays-out *after* the animation, which reads as a second, unrelated
    // event. Hiding it first also makes the motion read as "this photo took over the screen"
    // rather than "a photo appeared inside a tab".
    //
    // This is safe because the grid's own geometry is *not* paddled by the host: the reserved
    // navigation height lives in `AppShell` and is passed down as a `PaddingValues`, and that value
    // only changes the empty space below the list — removing it cannot move the thumbnail the
    // animation is flying out of.
    LaunchedEffect(Unit) {
        onTransitionSettled(true)
        transition.animateOpen()
    }

    // On teardown the viewer may still be mid-close. Restore the host chrome unconditionally —
    // `onTransitionSettled(false)` is normally sent at the start of the close, but a process-level
    // recreation (configuration change) must not leave the app with no top bar and no nav bar.
    DisposableEffect(Unit) {
        onDispose { onTransitionSettled(false) }
    }

    // Every way out of the viewer goes through this, so the closing animation is never skipped.
    val dismissWithAnimation: () -> Unit = {
        transitionScope.launch {
            // Bring the host chrome back BEFORE the grid starts fading in, so the grid lands in a
            // correctly-padded frame instead of re-laying-out mid-fade.
            onTransitionSettled(false)
            transition.animateClose { onDismiss() }
        }
    }

    HideSystemBars(immersive = !chromeVisible)

    // Report the settled page upward. Driven by `settledPage` rather than the animated `currentPage`
    // so a fling through ten photos produces one update, not ten.
    LaunchedEffect(pagerState, photos.size) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page in photos.indices) {
                    onIndexChange(page)
                    showInfo = false
                    // A new photo starts unzoomed; make sure paging is unlocked again.
                    pageZoom = 0f
                }
            }
    }

    // Hardware / gesture back.
    //
    // Predictive back is wired first: while the user drags from the screen edge the viewer shrinks
    // in step with the finger, and only commits (or springs back) on release. When a sub-surface is
    // open (the info card, or hidden chrome) the predictive gesture is disabled and the plain
    // `BackHandler` below handles the step-by-step retreat instead — animating the whole viewer away
    // while the user is just trying to close a card would be wrong.
    val predictiveBackEnabled = !showInfo && chromeVisible

    ViewerPredictiveBack(
        isEnabled = predictiveBackEnabled,
        onProgress = { p ->
            // Follow the finger exactly; no animation is running during the gesture.
            transition.livePreview(p)
        },
        onCancel = {
            val currentP = transition.effectiveProgress
            transitionScope.launch {
                transition.snapTo(currentP)
                transition.animateOpen()
            }
        },
        onCommit = {
            val currentP = transition.effectiveProgress
            onTransitionSettled(false)
            transitionScope.launch {
                transition.snapTo(currentP)
                transition.animateClose { onDismiss() }
            }
        }
    )

    BackHandler(enabled = !predictiveBackEnabled) {
        when {
            showInfo -> showInfo = false
            !chromeVisible -> {
                chromeVisible = true
                immersive = false
            }
            // Reached only when predictive back is disabled for some other reason; still animate.
            else -> dismissWithAnimation()
        }
    }

    // The viewer's outer box paints NOTHING during the transition, on purpose.
    //
    // Any opaque backdrop placed here would sit above the grid and hide its fade entirely — the
    // photo would zoom over a black plate, which is exactly the "flash to black" this transition
    // exists to avoid. The dimming is therefore split in three layers, each with its own job:
    //
    //  1. the grid dims ITSELF (see `PhotosScreen`: scale 1 - 0.06p, alpha 1 - 0.88p), so the
    //     background behind the photo is the real photo collection, visibly receding;
    //  2. the photo's content fades and grows on `progress`;
    //  3. the backdrop below ramps 0.55 -> 0.92 on `progress`, i.e. it only becomes dark enough to
    //     read as "a gallery" *after* the grid has already been faded out.
    //
    // Splitting it this way means the grid is never covered by *our* paint while it is still the
    // subject: whatever the user sees during the motion is the actual gallery, not a flat rectangle.
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { windowSize = Size(it.width.toFloat(), it.height.toFloat()) }
            // Without this the taps would keep reaching the grid underneath.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
            .graphicsLayer {
                // Whole viewer follows the vertical drag.
                translationY = dragY
            }
    ) {
        // The full-screen backdrop. This is the layer that actually darkens the screen.
        //
        // It ramps from `PHOTO_SCRIM_MAX` (0.55) to `PHOTO_SCRIM_SETTLED` (0.92) as the motion
        // lands — see `ViewerTransitionState.photoScrimAlpha`, which derives both from the same
        // `progress` the grid fades on. It deliberately never reaches full opacity mid-motion: an
        // opaque plate behind a small thumbnail reads as a black flash, and it would also hide the
        // grid that is still the visible background at that point.
        //
        // During a drag-to-dismiss the factor flips: the viewer's own opacity follows the drag, so
        // the grid is revealed again as the photo is pulled away.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Color.Black.copy(
                        alpha = transition.photoScrimAlpha() *
                            dismissBackgroundAlpha(dragProgress)
                    )
                )
        )

        // NOTE: there is deliberately no full-screen vertical-gesture overlay here.
        //
        // Two earlier attempts placed one as a sibling of the pager — first after it, then before
        // it with a `zIndex`. Both were wrong, in opposite directions:
        //
        //  - Sibling *after* the pager: the pager received the pointer first and kept it, so taps
        //    and pinches never reached the page content.
        //  - Sibling *before* the pager: this overlay received the pointer first and its drag
        //    detector claimed it, so the pager never saw a horizontal drag and paging died.
        //
        // A sibling overlay cannot win: whichever one is on top starves the other. The vertical
        // drag detector therefore lives *inside* each page (see `PageGestures` below), where it
        // shares one pointer stream with the page content and coexists with the pager's own
        // horizontal handling.
        //
        // ---- Content layer: the part that grows out of / shrinks back into the thumbnail ----
        //
        // The rectangle is applied as a *draw-time* transform (graphicsLayer), not as layout.
        //
        // It used to be `Modifier.offset { }` + `Modifier.size(width, height)`, which changes the
        // pager's measured size on every animation frame: 60 measures per second of a full-screen
        // pager, each one invalidating every page inside it. That is what made the motion feel
        // stuttery. A graphicsLayer translation plus a scale is applied when the layer is drawn,
        // so the pager keeps one fixed full-window layout for the whole animation and only its
        // two floats change per frame.
        //
        // Because the scale is about the layer's centre by default, the pivot has to be moved to
        // the top-left and the target rectangle's top-left used as the translation, otherwise the
        // photo would appear to fall diagonally towards the middle of the screen.
        val contentRect = transition.contentRect(windowSize)
        val progress = transition.effectiveProgress
        val fitScaleX = if (windowSize.width > 0f) contentRect.width / windowSize.width else 1f
        val fitScaleY = if (windowSize.height > 0f) contentRect.height / windowSize.height else 1f

        HorizontalPager(
            state = pagerState,
            key = { index -> photos[index].id },
            // Lock paging while the image is zoomed so a horizontal swipe pans the picture
            // instead of turning the page.
            //
            // NOTE: the vertical / horizontal / zoom decision is actually made inside the page's
            // own pointer handler (see `ImageViewerPage`), which consumes the gesture before the
            // pager can react. This flag is a second line of defence, and also stops a fling left
            // over from a previous gesture from carrying into a zoomed page.
            userScrollEnabled = pageZoom == 0f && dragProgress == 0f,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Chrome and gestures must not react while the photo is still a thumbnail.
                    alpha = progress
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    translationX = contentRect.left
                    translationY = contentRect.top
                    scaleX = fitScaleX
                    scaleY = fitScaleY
                    // Round the card off while it is small, square it off as it fills the screen.
                    clip = true
                    shape = RoundedCornerShape(
                        with(density) { transition.cornerRadiusPx(CARD_CORNER_PX).toDp() }
                    )
                    // Drag-to-dismiss shrinks the whole surface, on top of the open/close scale.
                    val d = dismissScale(dragProgress)
                    scaleX *= d
                    scaleY *= d
                }
        ) { page ->
            val photo = photos[page]
            val isActive = page == pagerState.settledPage

            if (isVideo(photo)) {
                VideoViewerPage(
                    photo = photo,
                    repository = repository,
                    isActive = isActive
                )
            } else {
                ImageViewerPage(
                    photo = photo,
                    repository = repository,
                    isActive = isActive,
                    onZoomChanged = { z -> if (page == pagerState.settledPage) pageZoom = z },
                    onToggleChrome = {
                        if (page == pagerState.settledPage) {
                            chromeVisible = !chromeVisible
                            immersive = !chromeVisible
                            if (!chromeVisible) showInfo = false
                        }
                    },
                    // Vertical drag: the page's detector owns the pointer, so it reports the
                    // travel and we decide what it means.
                    onVerticalDrag = { travel ->
                        if (page != pagerState.settledPage) return@ImageViewerPage
                        if (travel > 0f) {
                            dragY = travel
                            dragProgress = dismissProgress(travel, dismissThresholdPx)
                        }
                    },
                    onVerticalDragEnd = { travel ->
                        if (page != pagerState.settledPage) return@ImageViewerPage
                        when {
                            travel > dismissThresholdPx -> dismissWithAnimation()
                            travel < -detailsThresholdPx -> showInfo = true
                            else -> Unit
                        }
                        // Snap back either way; the exit paths animate themselves.
                        dragY = 0f
                        dragProgress = 0f
                    },
                    // Horizontal drag.
                    //
                    // The page's pointer handler consumes the DOWN, so the pager never sees these
                    // events and cannot page on its own. Two earlier attempts at bridging that
                    // gap were wrong:
                    //
                    //  1. `scrollBy` launched per frame: dozens of overlapping coroutines racing
                    //     each other, so the settle animation and the last few scroll steps fought
                    //     and the pager could stop between two pages.
                    //  2. Settling to `currentPage`: `currentPage` is the page *most visible*, so a
                    //     drag of less than half a screen snapped straight back — the "needs a
                    //     second swipe" the user saw.
                    //
                    // Now a drag only tracks a preview offset, and the release decides once, from
                    // the total distance, which single page to land on.
                    onHorizontalDrag = { drag ->
                        if (page != pagerState.settledPage) return@ImageViewerPage
                        // Live preview: the photo must follow the finger 1:1.
                        //
                        // `drag.x` is negative for a leftward swipe (finger moves toward smaller
                        // x). `scrollBy` moves the CONTENT, whose direction is the opposite sign:
                        // a positive argument shifts content right, which is what visually happens
                        // when a finger swipes left. Hence the negation: left swipe -> content
                        // moves right -> the next photo slides in from the right.
                        //
                        // Getting this wrong made the preview slide the opposite way from the
                        // finger, then snap to the correct page — the "thunderbolt jump" the user
                        // reported.
                        //
                        // These scrolls are APPLIED SYNCHRONOUSLY via `scrollBy` on a scope that
                        // is cancelled before settling, rather than being queued: a `launch` per
                        // frame leaves a backlog that keeps running after the finger lifts, and
                        // the settle animation then starts from wherever that backlog happens to
                        // have left the pager. That race is what makes a swipe land a page off.
                        previewScrollBy(-drag.x)
                    },
                    onHorizontalDragStart = {
                        if (page != pagerState.settledPage) return@ImageViewerPage
                        // Captured BEFORE the preview scroll moves anything. Settling is computed
                        // from this, so it cannot be corrupted by how far the finger dragged.
                        dragStartPage = pagerState.currentPage
                    },
                    onHorizontalDragEnd = { totalDragX, velocityMagnitude ->
                        if (page != pagerState.settledPage) return@ImageViewerPage
                        val pages = pagesFromSwipe(
                            totalDragX = totalDragX,
                            velocityMagnitude = velocityMagnitude,
                            screenWidthPx = screenWidthPx
                        )
                        // Settle relative to the page the gesture STARTED on, never relative to
                        // `currentPage`.
                        //
                        // `currentPage` rounds to the nearest page, and the preview scroll has
                        // already moved the offset away from it. Building the target on that
                        // rounded value double-counts the movement: a drag past the halfway point
                        // made `currentPage` advance by one, so adding `pages` jumped two. In the
                        // other direction it pointed back at the page the preview had just left,
                        // and the pager visibly stuck between the two (the reported screenshot).
                        //
                        // `dragStartPage` is captured when the gesture begins, so this arithmetic
                        // is independent of how far the preview happened to scroll.
                        val target = (dragStartPage + pages).coerceIn(0, photos.lastIndex)
                        // Debug-only: the whole decision in one line. Kept because tracking a
                        // swipe failure without it means guessing at sign conventions — which is
                        // exactly how several rounds of this bug were misdiagnosed.
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                TAG_VIEWER,
                                "swipe start=$dragStartPage pages=$pages target=$target " +
                                    "dragX=$totalDragX v=$velocityMagnitude"
                            )
                        }
                        // Stop any preview scroll still in flight before settling, so the
                        // animation starts from the offset the finger actually left behind.
                        previewJob?.cancel()
                        previewJob = null
                        pagerScope.launch {
                            pagerState.animateScrollToPage(target)
                        }
                    }
                )
            }
        }

        // ---- Top chrome ----
        AnimatedVisibility(
            // Chrome fades in only once the photo has landed, and disappears immediately on the way
            // out: showing the back arrow over a shrinking thumbnail would give the motion away.
            visible = chromeVisible && transition.isSettledOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = dismissWithAnimation) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(end = 16.dp)
                ) {
                    Text(
                        text = viewerDate(current.assetDate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Text(
                        text = viewerTime(current.assetDate),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // ---- Bottom chrome ----
        AnimatedVisibility(
            visible = chromeVisible && transition.isSettledOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ViewerAction(
                    label = if (isSharing) "准备中" else "分享",
                    icon = {
                        if (isSharing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "分享",
                                tint = Color.White
                            )
                        }
                    },
                    enabled = !isSharing,
                    onClick = { onShare(current) }
                )
                ViewerAction(
                    label = "信息",
                    icon = { Icon(Icons.Default.Info, contentDescription = null, tint = Color.White) },
                    onClick = { showInfo = !showInfo }
                )
                ViewerAction(
                    label = downloadLabel(downloadState),
                    icon = {
                        when (downloadState) {
                            is DownloadState.Downloading -> CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            is DownloadState.Success -> Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50)
                            )
                            else -> Icon(
                                Icons.Default.ArrowDownward,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    },
                    enabled = downloadState !is DownloadState.Downloading &&
                        downloadState !is DownloadState.Success,
                    onClick = { onDownload(current) }
                )
            }
        }

        // ---- Details page ----
        //
        // Reached two ways, both of which should feel like the same destination: the "i" action
        // and a swipe up from the photo. It slides in from the bottom (matching the upward gesture)
        // and leaves the same way.
        AnimatedVisibility(
            visible = showInfo,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PhotoInfoCard(
                photo = current,
                repository = repository,
                onDismiss = { showInfo = false },
                // Deliberately NOT fillMaxSize: the card sizes itself to its content and only
                // caps at a fraction of the screen. A full-screen sheet buried the photo under a
                // large empty block.
                //
                // NOTE: no alpha/translation parallax here. An earlier version faded the card in
                // proportionally to `upwardDrag`, but that value is reset to 0 the moment the
                // finger lifts — so the card settled at alpha 0.5 and looked permanently
                // see-through. `AnimatedVisibility` already handles the entrance.
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ViewerAction(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) { icon() }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** True for anything that should play rather than display. Mirrors the grid's badge rule. */
private fun isVideo(photo: PhotoAssetEntity): Boolean =
    photo.mediaType == "VIDEO" ||
        photo.filename.endsWith(".mp4", ignoreCase = true) ||
        photo.filename.endsWith(".mov", ignoreCase = true)

private fun downloadLabel(state: DownloadState): String = when (state) {
    is DownloadState.Downloading -> "下载中"
    is DownloadState.Success -> "已保存"
    else -> "下载"
}

/**
 * Decides how many pages a finished horizontal swipe should advance, in -1 / 0 / +1.
 *
 * Pure so the rule can be unit tested.
 *
 * ## Sign convention (measured, not assumed)
 *
 * A leftward swipe — finger moving towards smaller x — produces a NEGATIVE `totalDragX`. This was
 * verified on a device by logging `calculatePan().x` per frame during a left swipe: every frame
 * reported about -11.5. Both parameters now describe the finger's direction of travel:
 *
 *   negative = swiped left  = next photo
 *   positive = swiped right = previous photo
 *
 * An earlier version assumed the opposite (that `calculatePan` reported *content* movement, whose
 * sign is flipped) and every swipe went backwards as a result.
 *
 * ## Why direction comes from distance
 *
 * The velocity at release is unreliable in both magnitude and sign: a finger that has flicked and
 * then settles can decelerate to nothing, or briefly rebound the other way. So the gesture's own
 * direction of travel is authoritative; velocity only decides whether the swipe was decisive
 * enough to skip the distance requirement.
 */
internal fun pagesFromSwipe(
    totalDragX: Float,
    velocityMagnitude: Float,
    screenWidthPx: Float
): Int {
    val width = screenWidthPx.coerceAtLeast(1f)
    val speed = abs(velocityMagnitude)

    // Distance decides the DIRECTION; velocity only decides HOW decisive the gesture was.
    //
    // `totalDragX < 0` means the finger moved left (measured on device), which is the
    // "next photo" gesture.
    val direction = when {
        totalDragX < 0f -> 1
        totalDragX > 0f -> -1
        else -> 0
    }
    if (direction == 0) return 0

    return when {
        // A decisive flick commits regardless of distance.
        speed > FLING_MIN_VELOCITY_PX_PER_SEC -> direction
        abs(totalDragX) >= width * PAGE_COMMIT_FRACTION -> direction
        else -> 0
    }
}

private fun viewerDate(timestamp: Long): String {
    if (timestamp <= 0) return "未知日期"
    return SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(timestamp))
}

private fun viewerTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

/**
 * Fraction of the screen width a horizontal drag must cover to change page.
 *
 * A quarter, not a half. Half means the user has to drag most of the way across before anything
 * happens, which reads as "the swipe did not work" and makes them swipe again. A quarter matches
 * the feel of a system gallery: a deliberate flick moves one photo.
 */
private const val PAGE_COMMIT_FRACTION = 0.25f

/**
 * Speed (px/s) above which a horizontal swipe counts as a page change.
 *
 * 600 rather than a higher value: on a real device a comfortable flick was regularly measuring
 * under 800, so the threshold was rejecting swipes the user considered deliberate. Combined with
 * the bounce rejection in [pagesFromSwipe], a lower bar does not make the gesture twitchy — stray
 * or reversing motion is discarded by direction, not by magnitude.
 */
private const val FLING_MIN_VELOCITY_PX_PER_SEC = 600f

/** Corner radius (px) the photo has while expanded; it shrinks to 0 as it collapses to a cell. */
private const val CARD_CORNER_PX = 24f

/** Logcat tag for the viewer's own diagnostics (debug builds only). */
private const val TAG_VIEWER = "PhotoViewer"


