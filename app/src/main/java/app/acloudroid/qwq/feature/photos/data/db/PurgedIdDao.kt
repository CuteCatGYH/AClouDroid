package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Persistence for "CloudKit told us this id is gone" (ISS-031).
 *
 * See [PurgedIdEntity] for why this must survive a process restart.
 */
@Dao
interface PurgedIdDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<PurgedIdEntity>)

    /** Only the ids we might actually veto — everything younger than [RETENTION_MS]. */
    @Query("SELECT id FROM purged_ids WHERE purgedAt >= :notBefore")
    suspend fun getActiveIds(notBefore: Long): List<String>

    @Query("DELETE FROM purged_ids WHERE purgedAt < :notBefore")
    suspend fun deleteOlderThan(notBefore: Long): Int

    /** FIFO trim: keeps the table bounded no matter how long the device stays in use. */
    @Query(
        """
        DELETE FROM purged_ids WHERE id IN (
            SELECT id FROM purged_ids ORDER BY purgedAt ASC LIMIT :count
        )
        """
    )
    suspend fun deleteOldest(count: Int): Int

    @Query("SELECT COUNT(*) FROM purged_ids")
    suspend fun count(): Int

    @Query("DELETE FROM purged_ids")
    suspend fun clearAll()

    companion object {
        /**
         * How long an entry keeps vetoing re-inserts.
         *
         * A photo restored from "Recently Deleted" comes back with the same `recordName`, so a
         * permanent veto would hide it forever. Seven days is comfortably longer than any realistic
         * stale-token replay window while still letting a restore surface on its own.
         */
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        /** Hard cap on rows, so the table cannot grow without bound. */
        const val MAX_ROWS = 4000
    }
}
