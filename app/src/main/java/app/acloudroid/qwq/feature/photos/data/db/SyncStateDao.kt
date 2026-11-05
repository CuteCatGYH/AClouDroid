package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE `key` = :key LIMIT 1")
    suspend fun getSyncState(key: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSyncState(state: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE `key` = :key")
    suspend fun clearSyncState(key: String)

    /**
     * Drops every cursor in the table (P2-018).
     *
     * `syncToken` / `continuationMarker` are bound to the account that minted them, so they must
     * not survive a logout — replaying a foreign account's token silently returns someone else's
     * (or no) records.
     */
    @Query("DELETE FROM sync_state")
    suspend fun clearAll()
}
