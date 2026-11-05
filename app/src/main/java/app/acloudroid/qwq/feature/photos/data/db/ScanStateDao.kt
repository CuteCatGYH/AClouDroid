package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for the resumable month scan (P2-016).
 */
@Dao
interface ScanStateDao {

    @Query("SELECT * FROM scan_state WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): ScanStateEntity?

    @Query("SELECT * FROM scan_state WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<ScanStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(state: ScanStateEntity)

    @Query("DELETE FROM scan_state WHERE `key` = :key")
    suspend fun clear(key: String)

    /**
     * Drops every scan row (P2-018).
     *
     * The cursor is bound to the account that produced it exactly like `sync_state` is — replaying
     * a foreign account's marker silently returns someone else's records, or none.
     */
    @Query("DELETE FROM scan_state")
    suspend fun clearAll()
}
