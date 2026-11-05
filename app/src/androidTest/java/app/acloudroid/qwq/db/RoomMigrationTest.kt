package app.acloudroid.qwq.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.acloudroid.qwq.feature.photos.data.PhotoDatabase
import app.acloudroid.qwq.feature.photos.data.db.UploadQueueEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    @Test
    fun testMigration8To9() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PhotoDatabase::class.java)
            .addMigrations(PhotoDatabase.MIGRATION_8_9)
            .build()

        assertNotNull(db.uploadQueueDao())

        val entity = UploadQueueEntity(
            mediaStoreUri = "content://media/external/images/media/100",
            filename = "test.jpg",
            mimeType = "image/jpeg",
            fileSize = 1024,
            localSha256 = "dummy_sha256",
            status = "PENDING",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val id = db.uploadQueueDao().insert(entity)
        assertEquals(1L, id)

        val pending = db.uploadQueueDao().getPendingTasks()
        assertEquals(1, pending.size)
        assertEquals("test.jpg", pending[0].filename)

        db.close()
    }
}
