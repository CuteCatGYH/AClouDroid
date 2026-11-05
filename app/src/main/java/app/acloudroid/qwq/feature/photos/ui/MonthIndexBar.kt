package app.acloudroid.qwq.feature.photos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.acloudroid.qwq.feature.photos.data.MonthBucketing
import app.acloudroid.qwq.feature.photos.data.db.MonthCoverRow
import app.acloudroid.qwq.feature.photos.data.db.PhotoMonthIndexEntity
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Floating time jump control for the gallery.
 *
 * ## Two states, one control
 *
 * **Collapsed** — a month capsule stacked over the up/down month steppers, vertically centred on
 * the right edge. It reports where you are, nudges you one month at a time, and is the only entry
 * point to the jump panel. The gallery's grid is never permanently narrowed by a strip of years.
 *
 * **Expanded** — the capsule grows into a single floating card anchored where the capsule was, and
 * the steppers step aside. The card holds the years in a vertical, scrollable column on its left
 * and the selected year's twelve months on its right.
 *
 * ## Why the years live in a `LazyColumn` inside a fixed-height card
 *
 * A library can span three years or twenty. A top-level list of years (one row each, no scroller)
 * would either be clipped at the screen edge or force the card to grow until it covers the gallery.
 * The card therefore has a **fixed maximum height** and the year list scrolls *inside* it, so
 * seventeen years and three years both produce the same card.
 *
 * The month grid is NOT lazy: it is always exactly twelve cells. Nesting a lazy grid inside the
 * year `LazyColumn` is what AGENTS §6.5 forbids — here it is a plain `Column` of `Row`s, which is
 * also cheaper than a scroll container that can never scroll.
 *
 * ## Anchoring
 *
 * Every read is driven by [currentYearMonth], which the caller derives from the list's
 * `firstVisibleItemKey` — **never** from a visible index. That is the P2-016 fix in one sentence:
 * a month key is stable across list rebuilds, an integer index is not.
 *
 * @param currentYearMonth the month at the top of the viewport, or null before the first layout.
 * @param months all months in the gallery, newest first.
 * @param onJumpToMonth called with the target month; the caller resolves it to a list position.
 * @param coverRowsOf resolves one year's cover rows, or null when the user has cover previews
 *   switched off. Null is not the same as "an empty flow": it selects the plain text grid and
 *   means the process never subscribed to the cover data at all.
 */
@Composable
fun MonthIndexBar(
    currentYearMonth: Int?,
    months: List<PhotoMonthIndexEntity>,
    onJumpToMonth: (Int) -> Unit,
    modifier: Modifier = Modifier,
    coverRowsOf: ((Int) -> Flow<List<MonthCoverRow>>)? = null
) {
    if (months.isEmpty()) return

    val sections = remember(months) { MonthIndexModel.yearSections(months) }
    val currentYear = currentYearMonth?.takeIf { it > 0 }?.let { MonthBucketing.yearOf(it) }

    // `panelOpen` — is the floating card on screen? `expandedYear` — which year's months it shows.
    // The year always exists inside an open card, so the two are not independent: opening sets
    // both, and there is no "card open with no year selected" state to reason about.
    var panelOpen by remember { mutableStateOf(false) }
    var expandedYear by remember { mutableStateOf<Int?>(null) }

    val scope = rememberCoroutineScope()
    val yearListState = rememberLazyListState()

    fun closePanel() {
        panelOpen = false
        expandedYear = null
    }

    fun openPanel() {
        val year = currentYear ?: sections.firstOrNull()?.year ?: return
        expandedYear = year
        panelOpen = true
    }

    // Follow the gallery: when the user scrolls while the card is open, the highlighted year keeps
    // moving and must stay visible inside the card's year column.
    LaunchedEffect(panelOpen, currentYear, sections) {
        if (!panelOpen) return@LaunchedEffect
        val target = expandedYear ?: currentYear ?: return@LaunchedEffect
        val idx = sections.indexOfFirst { it.year == target }
        if (idx >= 0) yearListState.animateScrollToItem(idx)
    }

    // Back closes the card. There is only one level now (the card *is* the year picker and the
    // month picker), so one press is the whole retreat.
    BackHandler(enabled = panelOpen) { closePanel() }

    // Cover data is only subscribed while the card is actually showing, and only when the user has
    // the feature on. A closed card costs nothing; a disabled one never even builds the query.
    val openYear = expandedYear ?: currentYear
    val coversEnabled = coverRowsOf != null
    val coverRows: List<MonthCoverRow> = if (coversEnabled && panelOpen && openYear != null) {
        remember(openYear, panelOpen, coverRowsOf) { coverRowsOf(openYear) }
            .collectAsStateWithLifecycle(initialValue = emptyList())
            .value
    } else {
        emptyList()
    }

    // Availability always comes from the month index — it is the authority on "does this month have
    // photos", and it is present whether or not covers are on. Cover rows only ever add a picture
    // on top of that.
    //
    // Deriving availability from the cover rows when covers are off is wrong in a way that is easy
    // to miss: an empty row list reads as "every month has zero photos", so the whole grid renders
    // disabled. That was a real bug — the text grid must stay clickable.
    val monthCells = remember(coverRows, sections, openYear, coversEnabled) {
        MonthCoverModel.cellsFor(
            year = openYear ?: 0,
            availableMonths = sections.firstOrNull { it.year == openYear }?.available,
            coverRows = if (coversEnabled) coverRows else emptyList()
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd
    ) {
        // Tap-outside-to-close.
        //
        // A barrier under the card, present only while it is open. Without it the only ways out
        // were the capsule and the back key, which on a phone means reaching for the same small
        // target the user just tapped — the opposite of what a dismissing gesture should feel
        // like. Mounting it conditionally matters: a permanent full-screen click target would
        // swallow every thumbnail tap.
        if (panelOpen) {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { closePanel() }
            )
        }

        // ---- Collapsed: capsule above the month steppers, vertically centred ----
        //
        // Fades out as the card grows so the two never draw on top of each other; the card is
        // anchored on this exact spot, which is what makes it read as "the capsule got bigger".
        AnimatedVisibility(
            visible = !panelOpen,
            enter = fadeIn(tween(durationMillis = 180)),
            exit = fadeOut(tween(durationMillis = 120))
        ) {
            Column(
                modifier = Modifier.padding(end = 8.dp),
                // Right-aligned, not centred: the capsule is wider than the round stepper
                // buttons, and centring the column would push the small circles into the middle
                // of the pill instead of keeping them on the same right edge as everything else
                // attached to the screen edge.
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (currentYearMonth != null) {
                    CurrentMonthCapsule(
                        label = MonthBucketing.labelOf(currentYearMonth),
                        expanded = false,
                        onClick = { openPanel() }
                    )
                }
                MonthStepper(
                    currentYearMonth = currentYearMonth,
                    months = months,
                    onJumpToMonth = onJumpToMonth
                )
            }
        }

        // ---- Expanded: the floating jump card ----
        AnimatedVisibility(
            visible = panelOpen,
            modifier = Modifier.padding(end = 8.dp),
            enter = fadeIn(tween(durationMillis = 160)) + scaleIn(
                initialScale = 0.72f,
                // The card grows out of the right-middle, which is where the capsule was, so the
                // motion reads as "that pill opened up" rather than "a dialog appeared".
                transformOrigin = TransformOrigin(1f, 0.5f),
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(tween(durationMillis = 130)) + scaleOut(
                targetScale = 0.8f,
                transformOrigin = TransformOrigin(1f, 0.5f),
                animationSpec = tween(durationMillis = 160, easing = FastOutLinearInEasing)
            )
        ) {
            JumpCard(
                sections = sections,
                yearListState = yearListState,
                expandedYear = expandedYear ?: currentYear,
                currentYear = currentYear,
                currentYearMonth = currentYearMonth,
                monthCells = monthCells,
                coversEnabled = coversEnabled,
                onSelectYear = { year ->
                    expandedYear = year
                    onJumpToMonth(
                        sections.firstOrNull { it.year == year }?.newestYearMonth ?: return@JumpCard
                    )
                },
                onPickMonth = { yearMonth ->
                    // Collapse before jumping: the scroll animation and the card's exit animation
                    // would otherwise compete for the same frames, which reads as a stutter rather
                    // than as two deliberate actions.
                    closePanel()
                    onJumpToMonth(yearMonth)
                }
            )
        }
    }
}

/**
 * The floating year + month picker.
 *
 * ## Size contract
 *
 * The card is **bounded**, never content-driven:
 *  - width is a constant (year column + month grid + padding),
 *  - height is `heightIn(max = …)` with the year column scrolling inside it.
 *
 * That is the whole point of the design: seventeen years and three years produce the same card, so
 * it can neither be forced open by a long library nor clip years off the bottom.
 */
@Composable
private fun JumpCard(
    sections: List<YearSection>,
    yearListState: androidx.compose.foundation.lazy.LazyListState,
    expandedYear: Int?,
    currentYear: Int?,
    currentYearMonth: Int?,
    /** Twelve cells for [expandedYear], carrying both availability and (optionally) a cover. */
    monthCells: List<MonthCoverCell>,
    /** True when a cover image should be drawn; false renders the plain text grid. */
    coversEnabled: Boolean,
    onSelectYear: (Int) -> Unit,
    onPickMonth: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val section = expandedYear?.let { year -> sections.firstOrNull { it.year == year } }
        ?: sections.firstOrNull()

    // The grid width follows whichever layout is showing, so the card is not padded with dead
    // space when covers are off. `CARD_WIDTH` stays at the wider of the two so the card does not
    // resize when the setting is toggled.
    val gridWidth = if (coversEnabled) MONTH_GRID_COVER_WIDTH else MONTH_GRID_TEXT_WIDTH

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        modifier = modifier
            .width(CARD_WIDTH)
            // Hard ceiling: beyond this the year column scrolls instead of the card growing.
            .heightIn(max = CARD_MAX_HEIGHT)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = section?.year?.toString() ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "跳到月份",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Pinned to the inner content width. Without it this `Row` measures its children
            // against the card's full width and the surplus ends up distributed into the month
            // cells, which is what once made them drift away from their intended size.
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .width(YEAR_COLUMN_WIDTH + 8.dp + gridWidth)
            ) {
                // --- Year column: scrolls inside the card ---
                //
                // `LazyColumn` here is what keeps the card a fixed size. `heightIn` inside a
                // bounded parent means it takes at most the space the card has and scrolls
                // whatever does not fit.
                LazyColumn(
                    state = yearListState,
                    modifier = Modifier
                        .width(YEAR_COLUMN_WIDTH)
                        .heightIn(max = CARD_MAX_HEIGHT - HEADER_ALLOWANCE),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(sections, key = { _, s -> s.year }) { _, s ->
                        YearChip(
                            year = s.year,
                            isCurrent = s.year == currentYear,
                            isSelected = s.year == section?.year,
                            onClick = { onSelectYear(s.year) }
                        )
                    }
                }

                Column(modifier = Modifier.padding(start = 8.dp)) {
                    MonthGrid(
                        cells = monthCells,
                        coversEnabled = coversEnabled,
                        currentYearMonth = currentYearMonth,
                        onPickMonth = onPickMonth
                    )
                }
            }
        }
    }
}

/**
 * The collapsed control: one pill showing where you are.
 *
 * Tapping it opens the jump card. The scale is applied through `graphicsLayer` so the press
 * animation costs no re-measure.
 */
@Composable
private fun CurrentMonthCapsule(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container by animateColorAsState(
        targetValue = if (expanded) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        label = "capsuleColor"
    )

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = container,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/** A single year inside the card's scrolling column. */
@Composable
private fun YearChip(
    year: Int,
    isCurrent: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isCurrent) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "yearScale"
    )

    Box(
        modifier = modifier
            .scaleBy(scale)
            .background(
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = year.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isCurrent || isSelected) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                isCurrent -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center
        )
    }
}

/** Applies an already-animated scale as a draw-time transform (no re-measure per frame). */
private fun Modifier.scaleBy(scale: Float): Modifier = this.graphicsLayer {
    scaleX = scale
    scaleY = scale
}

/**
 * The 12-cell month grid.
 *
 * Two layouts behind one function, because they are the same thing at different levels of detail:
 *
 *  - **covers on** — 3 across x 4 down of 56dp thumbnails, the month number overlaid at the
 *    bottom. This is what makes \"jump to June\" a matter of recognising a picture.
 *  - **covers off** — 4 across x 3 down of plain text cells, i.e. exactly the old grid.
 *
 * Passing `cells == null` selects the text layout; the caller does that when the user has the
 * feature switched off, which is also what stops the covers being downloaded in the first place.
 *
 * Plain `Column` + `Row`s either way: a fixed grid gains nothing from being lazy, and nesting a
 * lazy layout inside the year `LazyColumn` is what AGENTS §6.5 warns about.
 */
@Composable
private fun MonthGrid(
    cells: List<MonthCoverCell>,
    coversEnabled: Boolean,
    currentYearMonth: Int?,
    onPickMonth: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!coversEnabled) {
        TextMonthGrid(
            cells = cells,
            currentYearMonth = currentYearMonth,
            onPickMonth = onPickMonth,
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier.width(MONTH_GRID_COVER_WIDTH),
        verticalArrangement = Arrangement.spacedBy(MONTH_GAP)
    ) {
        cells.chunked(COVER_COLUMNS).forEach { rowCells ->
            Row(
                modifier = Modifier.width(MONTH_GRID_COVER_WIDTH),
                horizontalArrangement = Arrangement.spacedBy(MONTH_GAP)
            ) {
                rowCells.forEach { cell ->
                    MonthCoverCellView(
                        cell = cell,
                        selected = cell.yearMonth == currentYearMonth,
                        onClick = { onPickMonth(cell.yearMonth) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** One cover cell: thumbnail, a scrim so the label stays readable, and the month number. */
@Composable
private fun MonthCoverCellView(
    cell: MonthCoverCell,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = cell !is MonthCoverCell.Unavailable
    val shape = RoundedCornerShape(12.dp)
    val context = LocalContext.current
    val container = app.acloudroid.qwq.di.rememberAppContainer()
    val imageLoader = app.acloudroid.qwq.feature.photos.data.image.ICloudImageLoaderFactory.get(context, container)

    Box(
        modifier = modifier
            .height(MONTH_COVER_CELL_HEIGHT)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (cell is MonthCoverCell.Cover) {
            // Must pass our custom imageLoader! The default Coil.imageLoader has no ICloudPhotoKeyer
            // or ICloudPhotoInterceptor, which caused all covers to fail silently.
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(
                        ICloudImageModel(
                            recordName = cell.coverId,
                            masterId = cell.coverMasterId,
                            url = cell.coverUrl,
                            fetchedAt = System.currentTimeMillis(),
                            quality = ICloudImageModel.Quality.THUMB,
                            cacheOnly = false
                        )
                    )
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Bottom scrim: without it a bright photo swallows the month number entirely.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.65f)
                        )
                    )
            )
        }

        Text(
            text = cell.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = when {
                cell is MonthCoverCell.Unavailable ->
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                cell is MonthCoverCell.Cover -> Color.White
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 4.dp)
        )
    }
}

/**
 * The pre-cover grid: 4 across x 3 down of text cells.
 *
 * Fed the same [MonthCoverCell] list as the cover layout, so "this month has no photos" stays
 * unclickable and dimmed either way. Only the picture is given up, not the availability
 * information — a user who turns covers off should not also lose the ability to tell February
 * from March.
 */
@Composable
private fun TextMonthGrid(
    cells: List<MonthCoverCell>,
    currentYearMonth: Int?,
    onPickMonth: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(MONTH_GRID_TEXT_WIDTH),
        verticalArrangement = Arrangement.spacedBy(MONTH_GAP)
    ) {
        cells.chunked(TEXT_COLUMNS).forEach { rowCells ->
            Row(
                modifier = Modifier.width(MONTH_GRID_TEXT_WIDTH),
                horizontalArrangement = Arrangement.spacedBy(MONTH_GAP)
            ) {
                rowCells.forEach { cell ->
                    MonthCell(
                        label = cell.label,
                        enabled = cell !is MonthCoverCell.Unavailable,
                        selected = cell.yearMonth == currentYearMonth,
                        onClick = { onPickMonth(cell.yearMonth) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    label: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(MONTH_CELL_HEIGHT)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = when {
                selected -> MaterialTheme.colorScheme.onPrimary
                enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            }
        )
    }
}

/** The up/down month stepper below the capsule. */
@Composable
private fun MonthStepper(
    currentYearMonth: Int?,
    months: List<PhotoMonthIndexEntity>,
    onJumpToMonth: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val newer = currentYearMonth?.let { MonthIndexModel.stepMonth(months, it, -1) }
    val older = currentYearMonth?.let { MonthIndexModel.stepMonth(months, it, 1) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StepperButton(
            icon = Icons.Default.KeyboardArrowUp,
            description = "上一月",
            enabled = newer != null,
            onClick = { newer?.let(onJumpToMonth) }
        )
        StepperButton(
            icon = Icons.Default.KeyboardArrowDown,
            description = "下一月",
            enabled = older != null,
            onClick = { older?.let(onJumpToMonth) }
        )
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(
                color = if (enabled) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                },
                shape = CircleShape
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Year column width inside the card. Four digits at `labelLarge`, plus breathing room. */
private val YEAR_COLUMN_WIDTH = 54.dp

private val MONTH_GAP = 5.dp

// ---- Covers on: 3 across x 4 down -----------------------------------------------------------------

private const val COVER_COLUMNS = 3
private const val TEXT_COLUMNS = 4

/** 56dp is big enough to recognise a scene, small enough that three fit beside the year column. */
private val MONTH_COVER_CELL_WIDTH = 56.dp
private val MONTH_COVER_CELL_HEIGHT = 56.dp

private val MONTH_GRID_COVER_WIDTH = MONTH_COVER_CELL_WIDTH * COVER_COLUMNS + MONTH_GAP * (COVER_COLUMNS - 1)

// ---- Covers off: 4 across x 3 down (the pre-feature layout) ----------------------------------------

private val MONTH_CELL_WIDTH = 42.dp
private val MONTH_CELL_HEIGHT = 36.dp

private val MONTH_GRID_TEXT_WIDTH = MONTH_CELL_WIDTH * TEXT_COLUMNS + MONTH_GAP * (TEXT_COLUMNS - 1)

/**
 * The card's width.
 *
 * Sized for the **wider** of the two grids (text, 183dp), so switching the cover preview off cannot
 * make the card change size under the user. Content-sized width would do exactly that as months
 * fill in.
 */
private val CARD_WIDTH =
    YEAR_COLUMN_WIDTH + 8.dp + maxOf(MONTH_GRID_COVER_WIDTH, MONTH_GRID_TEXT_WIDTH) + 16.dp

/**
 * The card's height ceiling.
 *
 * Generous enough to show ~8 years without scrolling, small enough that the card never covers the
 * gallery's top bar or its bottom navigation. Past this the year column scrolls.
 */
private val CARD_MAX_HEIGHT = 420.dp

/** Header row + divider + outer padding, subtracted to give the year column its own ceiling. */
private val HEADER_ALLOWANCE = 64.dp
