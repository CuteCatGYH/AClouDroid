package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One asset id observed during an in-progress full-library scan.
 *
 * ## Why this table exists
 *
 * The month scan only ever *upserts*: it writes what CloudKit returns and never notices what
 * CloudKit stopped returning. That makes the local cache permanently one-way — a photo deleted on
 * another device and then purged from "Recently Deleted" produces no tombstone in
 * `/records/changes` and no entry in the expunge index, so nothing ever removes it locally
 * (ISS-031).
 *
 * Recording the ids of every page turns the walk into a real reconciliation: when it finishes, the
 * ids in this table are the authoritative "the cloud still has these" answer, and anything cached
 * locally that is absent from it can be deleted.
 *
 * ## Contract
 *
 * - It is **derived data**: always safe to drop and rebuild. Cleared at the start of every walk.
 * - Rows are only meaningful if the walk completed. A partial table must never be used to delete
 *   anything — see `MonthScanEngine`.
 * - Both `id` (the `CPLAsset.recordName`, which is also our primary key) and `masterId` are stored,
 *   because several `CPLAsset` records can share one `CPLMaster`. The reconciler keeps a row alive
 *   when *either* of its ids is still present (the conservative rule).
 */
@Entity(
    tableName = "scan_seen_ids",
    indices = [Index(value = ["masterId"])]
)
data class ScanSeenIdEntity(
    @PrimaryKey
    val id: String,
    val masterId: String,
    val scannedAt: Long = 0L
)
