package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Resumable state for the full-library month scan (P2-016).
 *
 * The scan walks CloudKit's `/records/query` cursor from newest to oldest, which for a 16k-item
 * library is hundreds of round trips. That work must survive a cold start, a process kill, and a
 * switch to the background, so the cursor is persisted after every batch.
 *
 * There is exactly one row, keyed [KEY_MONTH_SCAN].
 */
@Entity(tableName = "scan_state")
data class ScanStateEntity(
    @PrimaryKey
    val key: String,
    /**
     * The CloudKit `continuationMarker` to resume from, or null to start from the newest record.
     *
     * IMPORTANT: this is only cleared on a *confirmed* 401/421 (see ISS-020). A plain session
     * rebuild does not invalidate the cursor, and clearing it there would restart the scan from
     * the top on every cold start.
     */
    val lastMarker: String? = null,
    /** True once the cursor has run out — the whole library has been walked. */
    val isComplete: Boolean = false,
    /** How many assets this scan has written. Drives the progress display. */
    val scannedCount: Int = 0,
    /**
     * Best-effort total number of items in the account, scraped from the iCloud web page at
     * login time (0 = unknown).
     *
     * CloudKit's cursor API never reports a total, so this is the only source. It is an
     * approximation: the web page counts differently from `CPLAsset` records (hidden items,
     * orphan masters, Live Photo composition). Used only as the progress denominator, and the
     * UI clamps to 99% until the scan genuinely finishes.
     */
    val estimatedTotal: Int = 0,
    val updatedAt: Long = 0
) {
    companion object {
        const val KEY_MONTH_SCAN = "month_scan"
    }
}
