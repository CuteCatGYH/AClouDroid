package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data access for [UploadedFingerprintEntity].
 *
 * Note what is *absent*: there is no `deleteById`, no `clearCompleted`, nothing row-scoped. The
 * whole point of this table is that the backup queue's "clear" buttons cannot reach it. Adding a
 * per-row delete later would quietly reintroduce the re-upload bug this table was created to fix,
 * so the only deletion available is [clearAll], reserved for account teardown.
 */
@Dao
interface UploadedFingerprintDao {

    @Query("SELECT EXISTS(SELECT 1 FROM uploaded_fingerprints WHERE contentKey = :key)")
    suspend fun exists(key: String): Boolean

    @Query("SELECT * FROM uploaded_fingerprints WHERE contentKey = :key LIMIT 1")
    suspend fun find(key: String): UploadedFingerprintEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: UploadedFingerprintEntity)

    /**
     * Backfills the identifiers that are only known once the upload succeeded.
     *
     * Kept separate from [insert] so the pre-upload placeholder row can be written without them.
     */
    @Query(
        "UPDATE uploaded_fingerprints SET masterId = :masterId, localSha256 = :sha256 " +
            "WHERE contentKey = :key"
    )
    suspend fun markConfirmed(key: String, masterId: String, sha256: String?)

    @Query("SELECT COUNT(*) FROM uploaded_fingerprints")
    suspend fun count(): Int

    /**
     * Account teardown only -- called from
     * [app.acloudroid.qwq.feature.photos.data.PhotoRepository.wipeAllData].
     *
     * Must run on logout: a `masterId` is a CloudKit record name owned by one Apple ID, so keeping
     * rows across an account switch would make the next account treat its own photos as already
     * backed up (AGENTS.md §5.5).
     */
    @Query("DELETE FROM uploaded_fingerprints")
    suspend fun clearAll()
}
