package app.acloudroid.qwq.feature.photos.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    val key: String,
    val syncToken: String? = null,
    val continuationMarker: String? = null,
    val hasMore: Boolean = false,
    val lastSyncTime: Long = System.currentTimeMillis()
)
