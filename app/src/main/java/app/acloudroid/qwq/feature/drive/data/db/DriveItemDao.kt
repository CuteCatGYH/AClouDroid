package app.acloudroid.qwq.feature.drive.data.db

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DriveItemEntity)

    @Query("UPDATE drive_items SET name = :name, etag = :etag WHERE drivewsid = :drivewsid")
    suspend fun updateNameAndEtag(drivewsid: String, name: String, etag: String?)

    @Query("UPDATE drive_items SET parentDrivewsid = :targetParentDrivewsid, etag = :etag WHERE drivewsid = :drivewsid")
    suspend fun updateParentAndEtag(drivewsid: String, targetParentDrivewsid: String, etag: String?)

    /**
     * Replaces a container's listing atomically: drop the old children, insert the new ones.
     *
     * Done as one transaction because a partially-applied refresh is indistinguishable from a
     * genuine deletion once the UI subscribes to the `Flow` — the folder would visibly lose rows
     * and then get them back.
     *
     * Preserves `childrenFetched` on subfolders that already existed in the local cache. When a
     * parent folder is re-listed, its response includes subfolder nodes built by `parseFolderList`
     * with `childrenFetched = false`. Blindly replacing existing subfolder rows with these
     * un-expanded nodes would reset `childrenFetched` to `0` in the database, stripping the
     * "already fetched" mark from subtrees that are still fully cached. On the next sync pass, the
     * planner would see `childrenFetched == false` and force those subfolders to re-list as
     * `LIST_UNKNOWN` — making a single parent re-list trigger a cascading re-list of every
     * descendant container regardless of whether its `etag` changed.
     */
    @Transaction
    suspend fun replaceChildren(parentDrivewsid: String, items: List<DriveItemEntity>) {
        val existingContainers = getChildren(parentDrivewsid)
            .filter { it.isContainer }
            .associateBy { it.drivewsid }

        val merged = items.map { item ->
            val existing = existingContainers[item.drivewsid]
            if (item.isContainer && existing != null) {
                item.copy(childrenFetched = existing.childrenFetched)
            } else {
                item
            }
        }

        deleteChildren(parentDrivewsid)
        if (merged.isNotEmpty()) {
            upsertAll(merged)
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

    /**
     * Every container the sync walk is allowed to judge.
     *
     * Excludes the synthetic root row only when it has never been listed, so a first run still
     * fetches it. `ORDER BY` is absent on purpose: [DriveDeltaPlanner.orderParentsFirst] decides
     * the visit order, and having SQL guess it too would make the two disagree.
     */
    @Query("SELECT * FROM drive_items WHERE type IN ('FOLDER', 'APP_LIBRARY')")
    suspend fun containersForSync(): List<DriveItemEntity>

    /**
     * Refreshes only the container's own metadata, leaving its children untouched.
     *
     * Used by the sync walk to stamp a fresh `etag` on a container it decided not to re-list,
     * so the next pass has an up-to-date tag to compare against. Also ensures `childrenFetched`
     * remains 1 since a skipped container's listing was already complete.
     */
    @Query(
        """
        UPDATE drive_items
        SET etag = :etag,
            directChildrenCount = :childCount,
            childrenFetched = 1,
            lastSyncedAt = :now
        WHERE drivewsid = :drivewsid
        """
    )
    suspend fun updateProbeResult(drivewsid: String, etag: String?, childCount: Int, now: Long)

    /**
     * Replaces a container's children **without** marking it fetched.
     *
     * Identical to [replaceChildren] except for the `childrenFetched` stamp, which the sync walk
     * applies separately once it has confirmed the listing was complete.
     */
    @Transaction
    suspend fun replaceChildrenRaw(parentDrivewsid: String, items: List<DriveItemEntity>) {
        val existingContainers = getChildren(parentDrivewsid)
            .filter { it.isContainer }
            .associateBy { it.drivewsid }

        val merged = items.map { item ->
            val existing = existingContainers[item.drivewsid]
            if (item.isContainer && existing != null) {
                item.copy(childrenFetched = existing.childrenFetched)
            } else {
                item
            }
        }

        deleteChildren(parentDrivewsid)
        if (merged.isNotEmpty()) {
            upsertAll(merged)
        }
    }

    /** Ids of everything currently cached as a direct child of [parentDrivewsid]. */
    @Query("SELECT drivewsid FROM drive_items WHERE parentDrivewsid = :parentDrivewsid")
    suspend fun childIdsOf(parentDrivewsid: String): List<String>

    /** Root marker — inserted lazily so the UI has something to subscribe to on first launch. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRootIfMissing(root: DriveItemEntity)

    @Query("UPDATE drive_items SET name = :name WHERE drivewsid = :drivewsid")
    suspend fun updateRootName(drivewsid: String, name: String)

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
