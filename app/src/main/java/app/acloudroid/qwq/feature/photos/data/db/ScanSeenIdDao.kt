package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Scratch space for the reconciliation walk (ISS-031).
 *
 * Deliberately dumb: no Flow, no ordering, no retention policy. The scan owns the whole lifecycle —
 * it clears the table before starting, appends every page, reads it once at the end, and clears it
 * again. Nothing else may call these.
 */
@Dao
interface ScanSeenIdDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ScanSeenIdEntity>)

    /**
     * Every id that CloudKit returned during the walk, both dimensions flattened into one set.
     *
     * Collapsing `id` and `masterId` together is what implements the conservative rule downstream:
     * a local row is only deletable when *neither* of its identifiers was seen.
     */
    @Query("SELECT id FROM scan_seen_ids UNION SELECT masterId FROM scan_seen_ids")
    suspend fun getSeenIds(): List<String>

    /** Number of distinct assets seen — used as the post-scan `scannedCount`. */
    @Query("SELECT COUNT(*) FROM scan_seen_ids")
    suspend fun count(): Int

    @Query("DELETE FROM scan_seen_ids")
    suspend fun clearAll()
}
