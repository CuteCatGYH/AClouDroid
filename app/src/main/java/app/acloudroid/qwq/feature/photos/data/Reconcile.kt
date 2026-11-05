package app.acloudroid.qwq.feature.photos.data

import app.acloudroid.qwq.feature.photos.data.db.PhotoIdentity

/**
 * Why a row survived one complete CloudKit walk (ISS-031).
 *
 * Every branch is named, because "why did this row survive?" is the first question anyone asks
 * when a deleted photo refuses to disappear.
 */
internal enum class KeepReason {
    /** The asset id itself came back from CloudKit. */
    SEEN,

    /**
     * The asset id did **not** come back, but its `CPLMaster` did — some other `CPLAsset` record
     * still points at the same underlying media. Conservative rule: keep it (see [Reconcile]).
     */
    MASTER_ALIVE,

    /** CloudKit never returned either id, so the asset is genuinely gone from the account. */
    NONE
}

internal data class ReconcileDecision(
    val toDelete: List<String>,
    val keptByMaster: Int,
    val keptBySeen: Int
) {
    val examined: Int get() = toDelete.size + keptByMaster + keptBySeen
}

/**
 * Pure reconciliation rules, separated from Room and CloudKit so they can be unit tested directly.
 *
 * ## Why a full diff at all
 *
 * CloudKit reports deletions in exactly one of two ways, and neither covers every case:
 *
 * | How the photo left the library | What we hear about it |
 * | --- | --- |
 * | moved to "Recently Deleted" | **nothing** — the record still exists, only `isDeleted` flips |
 * | expunged after 30 days | a tombstone in `/records/changes`, or an entry in the expunge index |
 *
 * The gap between the two — plus any tombstone missed while the app was not running long enough for
 * its `syncToken` to stay usable — leaves a row cached forever, because the incremental path never
 * says "this is gone". Comparing the whole library against everything CloudKit still returns is the
 * only statement that can positively prove absence.
 *
 * ## Conservative rule (chosen deliberately)
 *
 * Several `CPLAsset` records can share one `CPLMaster` (edits, re-imports, server-side remaps).
 * When that happens the app cannot tell which asset the user is looking at, so it must not guess:
 *
 * > **If the `masterId` is still present in the walk, every local row for that master is kept.**
 *
 * A duplicate tile the user can ignore is a far cheaper mistake than deleting a real photo. The
 * visible cost is that "looks duplicated" states survive reconciliation and must be solved in the
 * UI layer (show one item per master), not by deleting rows here.
 *
 * ## Safety
 *
 * The caller must only invoke this after a walk CloudKit itself declared finished. A partial id set
 * would make almost every cached row look deleted — see `MonthScanEngine`.
 */
internal object Reconcile {

    /**
     * Decides which local rows to drop.
     *
     * @param local every row currently cached (id + masterId).
     * @param seenIds every id CloudKit returned during the walk, `CPLAsset.recordName` **and**
     *   `CPLMaster.recordName` flattened into one set.
     */
    fun decide(local: List<PhotoIdentity>, seenIds: Set<String>): ReconcileDecision {
        val toDelete = ArrayList<String>()
        var keptByMaster = 0
        var keptBySeen = 0

        for (row in local) {
            when {
                row.id in seenIds -> keptBySeen++
                // Conservative rule: the master is still referenced, so the media exists. Keep.
                row.masterId.isNotBlank() && row.masterId in seenIds -> keptByMaster++
                else -> toDelete.add(row.id)
            }
        }

        return ReconcileDecision(
            toDelete = toDelete,
            keptByMaster = keptByMaster,
            keptBySeen = keptBySeen
        )
    }

    /**
     * Second line of defence for a walk that reported success but is not actually complete.
     *
     * Two real failure shapes this catches:
     *
     * 1. CloudKit answering `200` with an empty page — the walk then "completes" immediately.
     * 2. A **resumed** walk whose earlier pages were lost from `scan_seen_ids`, leaving `seen` as a
     *    suffix of the library. Measured: this purged 700 legitimate photos before the gate existed.
     *
     * The relationship relied on is simple and holds for any genuinely complete walk:
     * every cached row contributes at least one id (`id` or `masterId`) to `seen`, so a complete
     * walk always yields `seenIdCount >= localCount`. Anything less is proof the id set is partial,
     * regardless of why.
     *
     * Note this is deliberately strict (not a percentage): the earlier ratio-based version still
     * allowed a half-lost `seen` set through, because half the library is above 50% of the rows.
     */
    fun isSafeToApply(localCount: Int, seenIdCount: Int): Boolean {
        if (localCount <= 0) return true
        return seenIdCount >= localCount
    }
}
