package app.acloudroid.qwq.feature.photos.data

/**
 * The decision half of
 * [PhotoRepository.healUnconfirmedFingerprints], split out so it can be tested without a database.
 *
 * ## Background (ISS-069)
 *
 * An upload fingerprint with `masterId = ''` is a *claim*: "an upload was started for these bytes".
 * It is written before the Go call only to stop a concurrent scan from enqueueing the same file
 * twice -- it is never evidence that the bytes reached the cloud.
 *
 * A build that failed to withdraw the claim on failure let it outlive its `upload_queue` row. The
 * scan path then saw a fingerprint and skipped the file, so after `MAX_AUTO_RETRIES` a photo could
 * be `FAILED` in the queue *and* permanently invisible to every later scan, with no error shown
 * anywhere. The fix has two halves: withdraw on failure (in `uploadPhotoFromQueue`), and this pass,
 * which repairs installs that already have poisoned rows.
 */
object FingerprintHealing {

    /**
     * Decides which claims may be released, given every unconfirmed fingerprint and the status of
     * each known queue row (keyed by `LocalAlbumRepository.buildFingerprintKey`).
     *
     * | Queue row for the file | Verdict |
     * | --- | --- |
     * | none | withdraw -- nothing is working on it |
     * | `PENDING` / `UPLOADING` | keep -- a live attempt will settle it itself |
     * | `FAILED` | withdraw -- every retry burned, so it is not in the cloud |
     * | `SUCCESS` | keep -- belt and braces; the upload already confirmed |
     *
     * Asymmetry is deliberate. Wrongly withdrawing costs at most one wasted upload (Go's
     * `masterRecordExists()` still blocks a duplicate record); wrongly keeping strands a file
     * forever. Every ambiguity therefore resolves towards withdrawal -- except for a live attempt,
     * where withdrawing would race the very upload it is trying to protect.
     *
     * @return the subset of [unconfirmedKeys] safe to delete.
     */
    fun keysToWithdraw(
        unconfirmedKeys: Collection<String>,
        queueStatusByKey: Map<String, String>
    ): List<String> = unconfirmedKeys.filter { key ->
        when (queueStatusByKey[key]) {
            QUEUED, UPLOADING, SUCCESS -> false
            // `null` means the queue row is gone entirely (cleared by the user, or never existed
            // because the row was deleted after FAILED); FAILED needs no retry budget left.
            else -> true
        }
    }

    /**
     * Whether an upload attempt that just failed should give up its claim immediately.
     *
     * Always true today. It exists as a named concept because the two callers -- the failure branch
     * in `uploadPhotoFromQueue` and the repair pass above -- must agree, and because a future
     * "keep the claim across a retry to avoid re-hashing" optimisation would have to change this in
     * one place rather than silently diverge.
     */
    fun withdrawOnFailure(): Boolean = true

    private const val QUEUED = "PENDING"
    private const val UPLOADING = "UPLOADING"
    private const val SUCCESS = "SUCCESS"
}
