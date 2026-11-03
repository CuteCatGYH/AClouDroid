package com.getup.iClouDroid.feature.drive.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.getup.iClouDroid.feature.drive.data.db.DriveItemDao
import com.getup.iClouDroid.feature.drive.data.db.DriveItemEntity
import com.getup.iClouDroid.feature.drive.data.db.DriveUploadDao
import com.getup.iClouDroid.feature.drive.data.db.DriveUploadEntity

/**
 * Drive-only database (`icloud_drive.db`).
 *
 * Separate from [com.getup.iClouDroid.feature.photos.data.PhotoDatabase] for the reason stated
 * there: a shared database forces a shared schema version, so any Drive table change would need
 * a photo migration (or, with a destructive fallback, wipe the whole photo cache and trigger a
 * full re-scan of the account).
 *
 * `fallbackToDestructiveMigration()` is deliberately **not** used. When v2 arrives it gets a real
 * `Migration`, matching the rule PhotoDatabase settled on at its v4.
 */
@Database(
    entities = [
        DriveItemEntity::class,
        DriveUploadEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class DriveDatabase : RoomDatabase() {

    abstract fun driveItemDao(): DriveItemDao
    abstract fun driveUploadDao(): DriveUploadDao

    companion object {
        @Volatile
        private var INSTANCE: DriveDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `drive_upload_queue` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `localPath` TEXT NOT NULL,
                        `targetFolderDrivewsId` TEXT NOT NULL,
                        `targetFolderPath` TEXT NOT NULL,
                        `filename` TEXT NOT NULL,
                        `filesize` INTEGER NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `bytesUploaded` INTEGER NOT NULL,
                        `errorMessage` TEXT,
                        `documentId` TEXT,
                        `retryCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_drive_upload_queue_status` ON `drive_upload_queue` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_drive_upload_queue_createdAt` ON `drive_upload_queue` (`createdAt`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `drive_upload_queue` ADD COLUMN `speedText` TEXT DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): DriveDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DriveDatabase::class.java,
                    "icloud_drive.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
