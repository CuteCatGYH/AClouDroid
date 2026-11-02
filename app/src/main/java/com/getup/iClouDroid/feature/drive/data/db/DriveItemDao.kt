package com.getup.iClouDroid.feature.drive.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DriveItemDao {

    /**
     * Direct children of a container, ordered the way a file manager lists them:
     * folders first, then by name, case-insensitively.
     *
     * The ordering is done in SQL rather than in the UI so the `Flow` emission order is stable —
     * a Compose `LazyColumn` keyed on `drivewsid` would otherwise reorder itself on rebuild.
     */
    @Query(
        """
        SELECT * FROM drive_items
        WHERE parentDrivewsid = :parentDrivewsid
        ORDER BY
            CASE type WHEN 'FOLDER' THEN 0 WHEN 'APP_LIBRARY' THEN 0 ELSE 1 END,
            name COLLATE NOCASE ASC,
            drivewsid ASC
        """
    )
    fun observeChildren(parentDrivewsid: String): Flow<List<DriveItemEntity>>

    @Query(
        """
        SELECT * FROM drive_items
        WHERE parentDrivewsid = :parentDrivewsid
        ORDER BY
            CASE type WHEN 'FOLDER' THEN 0 WHEN 'APP_LIBRARY' THEN 0 ELSE 1 END,
            name COLLATE NOCASE ASC,
            drivewsid ASC
        """
    )
    suspend fun getChildren(parentDrivewsid: String): List<DriveItemEntity>

    @Query("SELECT * FROM drive_items WHERE drivewsid = :drivewsid LIMIT 1")
    suspend fun getById(drivewsid: String): DriveItemEntity?

    @Query("SELECT * FROM drive_items WHERE drivewsid = :drivewsid LIMIT 1")
    fun observeById(drivewsid: String): Flow<DriveItemEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DriveItemEntity>)

    /**
     * Replaces a container's listing atomically: drop the old children, insert the new ones.
     *
     * Done as one transaction because a partially-applied refresh is indistinguishable from a
     * genuine deletion once the UI subscribes to the `Flow` — the folder would visibly lose rows
     * and then get them back.
     */
    @Transaction
    suspend fun replaceChildren(parentDrivewsid: String, items: List<DriveItemEntity>) {
        deleteChildren(parentDrivewsid)
        if (items.isNotEmpty()) {
            upsertAll(items)
        }
        markChildrenFetched(parentDrivewsid)
    }

    @Query("DELETE FROM drive_items WHERE parentDrivewsid = :parentDrivewsid")
    suspend fun deleteChildren(parentDrivewsid: String)

    @Query("UPDATE drive_items SET childrenFetched = 1, lastSyncedAt = :now WHERE drivewsid = :drivewsid")
    suspend fun markChildrenFetched(drivewsid: String, now: Long = System.currentTimeMillis())

    /** Every known container. D3 walks these to decide which subtrees need re-listing. */
    @Query("SELECT * FROM drive_items WHERE type IN ('FOLDER', 'APP_LIBRARY')")
    suspend fun getAllContainers(): List<DriveItemEntity>

    /** Root marker — inserted lazily so the UI has something to subscribe to on first launch. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRootIfMissing(root: DriveItemEntity)

    @Query("SELECT COUNT(*) FROM drive_items")
    suspend fun count(): Int

    /** Descendants of a container, used when a subtree is removed or an account is wiped. */
    @Query("SELECT drivewsid FROM drive_items WHERE parentDrivewsid = :parentDrivewsid")
    suspend fun childIds(parentDrivewsid: String): List<String>

    @Query("DELETE FROM drive_items WHERE drivewsid IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM drive_items")
    suspend fun clearAll()
}
