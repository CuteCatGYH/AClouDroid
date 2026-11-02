package com.getup.iClouDroid.feature.drive.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.getup.iClouDroid.feature.drive.data.db.DriveItemDao
import com.getup.iClouDroid.feature.drive.data.db.DriveItemEntity

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
    entities = [DriveItemEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DriveDatabase : RoomDatabase() {

    abstract fun driveItemDao(): DriveItemDao

    companion object {
        @Volatile
        private var INSTANCE: DriveDatabase? = null

        fun getInstance(context: Context): DriveDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DriveDatabase::class.java,
                    "icloud_drive.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
