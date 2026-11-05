package app.acloudroid.qwq.feature.photos.ui

import androidx.compose.foundation.lazy.LazyListState
import app.acloudroid.qwq.data.settings.GalleryViewMode
import app.acloudroid.qwq.feature.photos.data.DayBucketing
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import app.acloudroid.qwq.feature.photos.data.db.PhotoMonthIndexEntity

/**
 * Anchor representation of the top visible items in the gallery list.
 */
data class VisibleAnchor(
    val yearMonth: Int,
    val dayKey: Int? = null,
    val photoIndexInMonth: Int? = null
)

/**
 * Utility functions to calculate LazyColumn item indices across Month and Day view modes
 * to ensure smooth scroll position synchronization on view mode toggles.
 */
object GalleryViewModeSync {

    fun rowsFor(itemCount: Int, columns: Int): Int {
        if (itemCount <= 0 || columns <= 0) return 0
        return (itemCount + columns - 1) / columns
    }

    /**
     * Finds the LazyColumn item index in Month View corresponding to the target month and photo position.
     */
    fun listIndexOfMonthView(
        months: List<PhotoMonthIndexEntity>,
        columns: Int = 5,
        targetYearMonth: Int,
        targetPhotoIndexInMonth: Int = 0
    ): Int {
        var index = 0
        for (month in months) {
            if (month.yearMonth == targetYearMonth) {
                if (targetPhotoIndexInMonth <= 0) {
                    return index
                }
                val targetRow = targetPhotoIndexInMonth / columns
                val maxRow = (rowsFor(month.itemCount, columns) - 1).coerceAtLeast(0)
                val rowOffset = targetRow.coerceIn(0, maxRow)
                return index + 1 + rowOffset
            }
            index += 1 + rowsFor(month.itemCount, columns)
        }
        return -1
    }

    /**
     * Finds the LazyColumn item index in Day View corresponding to the target month and day.
     */
    fun listIndexOfDayView(
        months: List<PhotoMonthIndexEntity>,
        monthDaysMap: Map<Int, List<DayBucketing.DayGroup>>,
        columns: Int = 3,
        targetYearMonth: Int,
        targetDayKey: Int? = null
    ): Int {
        var index = 0
        for (month in months) {
            val days = monthDaysMap[month.yearMonth].orEmpty()
            if (month.yearMonth == targetYearMonth) {
                if (targetDayKey != null) {
                    var dayIndex = index
                    for (day in days) {
                        if (day.dayKey == targetDayKey) {
                            return dayIndex
                        }
                        dayIndex += 1 + rowsFor(day.photos.size, columns)
                    }
                }
                return index
            }
            for (day in days) {
                index += 1 + rowsFor(day.photos.size, columns)
            }
        }
        return -1
    }

    /**
     * Inspects the current LazyListState to determine the top visible anchor (yearMonth, dayKey, photoIndex).
     */
    fun currentVisibleAnchor(
        state: LazyListState,
        months: List<PhotoMonthIndexEntity>,
        monthPhotos: Map<Int, List<PhotoAssetEntity>>,
        monthDaysMap: Map<Int, List<DayBucketing.DayGroup>>,
        viewMode: GalleryViewMode
    ): VisibleAnchor {
        val fallbackYearMonth = months.firstOrNull()?.yearMonth ?: 0
        val layout = state.layoutInfo
        val firstVisible = layout.visibleItemsInfo.firstOrNull()
            ?: return VisibleAnchor(yearMonth = fallbackYearMonth)

        val key = firstVisible.key.toString()

        return if (viewMode == GalleryViewMode.MONTH) {
            when {
                key.startsWith("h_") -> {
                    val ym = key.removePrefix("h_").toIntOrNull() ?: fallbackYearMonth
                    VisibleAnchor(yearMonth = ym, photoIndexInMonth = 0)
                }
                key.startsWith("r_") -> {
                    val parts = key.removePrefix("r_").split('_')
                    val ym = parts.getOrNull(0)?.toIntOrNull() ?: fallbackYearMonth
                    val row = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    val photoIndex = row * 5
                    val photos = monthPhotos[ym].orEmpty()
                    val photo = photos.getOrNull(photoIndex)
                    val dayKey = if (photo != null && photo.assetDate > 0) {
                        DayBucketing.dayKeyOf(photo.assetDate)
                    } else null
                    VisibleAnchor(yearMonth = ym, dayKey = dayKey, photoIndexInMonth = photoIndex)
                }
                else -> VisibleAnchor(yearMonth = fallbackYearMonth)
            }
        } else {
            when {
                key.startsWith("dh_") -> {
                    val dayKey = key.removePrefix("dh_").toIntOrNull() ?: 0
                    val ym = if (dayKey > 0) dayKey / 100 else fallbackYearMonth
                    VisibleAnchor(yearMonth = ym, dayKey = if (dayKey > 0) dayKey else null)
                }
                key.startsWith("dr_") -> {
                    val parts = key.removePrefix("dr_").split('_')
                    val dayKey = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    val row = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    val ym = if (dayKey > 0) dayKey / 100 else fallbackYearMonth

                    val dayGroups = monthDaysMap[ym].orEmpty()
                    val targetGroup = dayGroups.find { it.dayKey == dayKey }
                    val firstPhotoInRow = targetGroup?.photos?.getOrNull(row * 3)

                    val photoIndex = if (firstPhotoInRow != null) {
                        val allPhotos = monthPhotos[ym].orEmpty()
                        val idx = allPhotos.indexOfFirst { it.id == firstPhotoInRow.id }
                        if (idx >= 0) idx else null
                    } else null

                    VisibleAnchor(
                        yearMonth = ym,
                        dayKey = if (dayKey > 0) dayKey else null,
                        photoIndexInMonth = photoIndex
                    )
                }
                else -> VisibleAnchor(yearMonth = fallbackYearMonth)
            }
        }
    }
}
