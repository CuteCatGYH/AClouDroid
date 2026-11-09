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
     * Whether these bytes are **confirmed** present in the cloud.
     *
     * Only `masterId != ''` counts. A placeholder row (written before the upload starts, to stop a
     * concurrent scan from enqueueing the same file twice) must not answer yes: the upload may
     * still fail, and treating a claim as proof is exactly how a file ends up stuck forever after
     * three failed attempts in a bad network (ISS-069).
     */
    @Query("SELECT EXISTS(SELECT 1 FROM uploaded_fingerprints WHERE contentKey = :key AND masterId != '')")
    suspend fun isConfirmed(key: String): Boolean

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

    /**
     * Withdraws a placeholder that never became a real upload.
     *
     * `masterId = ''` in the `WHERE` is the safety catch: a confirmed row is never removed, no
     * matter which caller makes a mistake. Only "claimed but never proved" may be released.
     */
    @Query("DELETE FROM uploaded_fingerprints WHERE contentKey = :key AND masterId = ''")
    suspend fun withdrawUnconfirmed(key: String)

    @Query("SELECT COUNT(*) FROM uploaded_fingerprints")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM uploaded_fingerprints WHERE masterId = ''")
    suspend fun countUnconfirmed(): Int

    /**
     * Claims only. Used by
     * [app.acloudroid.qwq.feature.photos.data.PhotoRepository.healUnconfirmedFingerprints] to find
     * rows whose owning upload may never have finished. Normally empty.
     */
    @Query("SELECT * FROM uploaded_fingerprints WHERE masterId = ''")
    suspend fun getAllUnconfirmed(): List<UploadedFingerprintEntity>

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
