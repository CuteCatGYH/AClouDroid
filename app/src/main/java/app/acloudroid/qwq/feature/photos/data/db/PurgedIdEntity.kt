package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An identity the app has been told is gone, remembered across process restarts.
 *
 * ## Why persistence is required
 *
 * The change feed is replayed from a `syncToken`. If the app is not run for a while, the token goes
 * stale and the next `/records/changes` can legitimately re-deliver records as *creates* — including
 * ones we already purged (a trashed `CPLAsset` is not a tombstone, so it looks like an ordinary
 * create). An in-memory guard only survives until the next cold start, which is exactly when the
 * stale-token replay happens.
 *
 * ## Semantics
 *
 * - Membership means "CloudKit has confirmed this id no longer belongs in the gallery".
 * - Entries are **never** used to delete anything on their own; they only veto an upsert. Being
 *   wrong therefore costs a reappearing photo (annoying) rather than data loss (unacceptable).
 * - Restoring a photo from "Recently Deleted" re-creates it with the same id, which would be vetoed
 *   incorrectly. That is handled by [expiresAt]: an entry stops vetoing after
 *   [PurgedIdDao.RETENTION_MS], and any later walk reconciles the truth anyway.
 */
@Entity(tableName = "purged_ids")
data class PurgedIdEntity(
    @PrimaryKey
    val id: String,
    /** Epoch millis when the id was recorded. Used for FIFO trimming and expiry. */
    val purgedAt: Long,
    /** Where the confirmation came from, for diagnostics: `tombstone` / `expunge` / `isDeleted`. */
    val source: String = ""
)
