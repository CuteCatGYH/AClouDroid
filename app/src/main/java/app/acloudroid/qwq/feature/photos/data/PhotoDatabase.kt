package app.acloudroid.qwq.feature.photos.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import app.acloudroid.qwq.feature.photos.data.db.PhotoDao
import app.acloudroid.qwq.feature.photos.data.db.PhotoMonthIndexDao
import app.acloudroid.qwq.feature.photos.data.db.PhotoMonthIndexEntity
import app.acloudroid.qwq.feature.photos.data.db.PurgedIdDao
import app.acloudroid.qwq.feature.photos.data.db.PurgedIdEntity
import app.acloudroid.qwq.feature.photos.data.db.ScanSeenIdDao
import app.acloudroid.qwq.feature.photos.data.db.ScanSeenIdEntity
import app.acloudroid.qwq.feature.photos.data.db.ScanStateDao
import app.acloudroid.qwq.feature.photos.data.db.ScanStateEntity
import app.acloudroid.qwq.feature.photos.data.db.SyncStateDao
import app.acloudroid.qwq.feature.photos.data.db.SyncStateEntity
import app.acloudroid.qwq.feature.photos.data.db.UploadQueueDao
import app.acloudroid.qwq.feature.photos.data.db.UploadQueueEntity

/**
 * Photos-only database.
 *
 * Deliberately separate from any future feature database (drive, notes): bundling every feature's
 * tables into one Room database forces them to share a schema version, so adding a Drive table
 * would mean bumping (and, with a destructive fallback, wiping) the photo cache too.
 */
@Database(
    entities = [
        PhotoAssetEntity::class,
        SyncStateEntity::class,
        PhotoMonthIndexEntity::class,
        ScanStateEntity::class,
        ScanSeenIdEntity::class,
        PurgedIdEntity::class,
        UploadQueueEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class PhotoDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun photoMonthIndexDao(): PhotoMonthIndexDao
    abstract fun scanStateDao(): ScanStateDao
    abstract fun scanSeenIdDao(): ScanSeenIdDao
    abstract fun purgedIdDao(): PurgedIdDao
    abstract fun uploadQueueDao(): UploadQueueDao

    companion object {
        /**
         * v3 → v4 (P2-016): month sections.
         *
         * Adds `photo_assets.yearMonth` plus the month index and scan-state tables. Deliberately
         * DDL-only: computing `yearMonth` for existing rows means a local-timezone conversion
         * (Joda/`java.util.Calendar`), which SQLite cannot do in an `ALTER`. Existing rows are
         * inserted with the `0` default and backfilled by [MonthScanEngine] on first run, so the
         * migration itself stays a fast, fixed-cost operation.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE photo_assets ADD COLUMN yearMonth INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `photo_month_index` (
                        `yearMonth` INTEGER NOT NULL,
                        `itemCount` INTEGER NOT NULL,
                        `firstAssetDate` INTEGER NOT NULL,
                        `lastAssetDate` INTEGER NOT NULL,
                        `isComplete` INTEGER NOT NULL,
                        PRIMARY KEY(`yearMonth`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scan_state` (
                        `key` TEXT NOT NULL,
                        `lastMarker` TEXT,
                        `isComplete` INTEGER NOT NULL,
                        `scannedCount` INTEGER NOT NULL,
                        `estimatedTotal` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_photo_assets_yearMonth_assetDate_id` " +
                        "ON `photo_assets` (`yearMonth`, `assetDate`, `id`)"
                )
            }
        }

        /**
         * v4 → v5: cached EXIF/GPS for the info panel.
         *
         * Additive and DDL-only: the metadata is filled lazily per asset when the user opens the
         * info card, so there is nothing to backfill here. `exifJson` stays NULL for every existing
         * row ("never fetched"), which is exactly the correct state.
         *
         * `exifFetchedAt` needs an explicit `DEFAULT 0` because the column is NOT NULL and existing
         * rows have no value to derive it from.
         *
         * NOTE: do not replace this with `fallbackToDestructiveMigration()`. Since v4 a schema bump
         * must be a real migration — a destructive fallback would wipe the photo cache and force a
         * full re-scan of the account (hundreds of CloudKit round trips) for what is one new column
         * (AGENTS.md §12).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photo_assets ADD COLUMN exifJson TEXT")
                db.execSQL(
                    "ALTER TABLE photo_assets ADD COLUMN exifFetchedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v5 → v6: reconciliation scratch space (ISS-031).
         *
         * Purely additive. `scan_seen_ids` is derived data that the scan clears before each walk
         * and repopulates as it goes, so an empty table is the correct starting state and there is
         * nothing to backfill.
         *
         * Consequence worth stating: on the first walk after this upgrade, the pre-v6 cached rows
         * are subject to reconciliation for the first time. Rows whose ids CloudKit still returns
         * are untouched; ones deleted and expunged long ago are finally dropped.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scan_seen_ids` (
                        `id` TEXT NOT NULL,
                        `masterId` TEXT NOT NULL,
                        `scannedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_seen_ids_masterId` " +
                        "ON `scan_seen_ids` (`masterId`)"
                )
            }
        }

        /**
         * v6 → v7: persistent "CloudKit says this is gone" memory (ISS-031).
         *
         * Replaces an in-memory set that was lost on every cold start — precisely when a stale
         * `syncToken` replay is most likely to re-deliver a purged record as a create.
         * Starts empty; there is nothing to backfill.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `purged_ids` (
                        `id` TEXT NOT NULL,
                        `purgedAt` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v7 → v8: month cover asset for the gallery's jump card.
         *
         * Additive and DDL-only. The column is nullable with no default and nothing is backfilled:
         * the correct value depends on `photo_assets` (the newest asset per month), and computing
         * it here would mean a correlated subquery over a 15k-row table inside the migration's
         * transaction. A NULL cover simply renders as a text cell until the next scan fills it in,
         * which is a graceful state rather than a broken one.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photo_month_index ADD COLUMN coverAssetId TEXT")
            }
        }

        /**
         * v8 → v9: background upload queue table (Phase 3).
         *
         * Purely additive. Starts empty.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `upload_queue` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `mediaStoreUri` TEXT NOT NULL,
                        `filename` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `fileSize` INTEGER NOT NULL,
                        `localSha256` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `retryCount` INTEGER NOT NULL DEFAULT 0,
                        `lastError` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_mediaStoreUri` ON `upload_queue` (`mediaStoreUri`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_localSha256` ON `upload_queue` (`localSha256`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_queue_status` ON `upload_queue` (`status`)")
            }
        }

        /**
         * v9 → v10: Motion Photo metadata columns for upload_queue (Phase 3.2).
         *
         * Additive columns for Live Photo / Motion Photo backup offset tracking.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_queue ADD COLUMN isMotionPhoto INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE upload_queue ADD COLUMN videoOffset INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE upload_queue ADD COLUMN videoSize INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: PhotoDatabase? = null

        /**
         * Kept as a convenience entry point for [app.acloudroid.qwq.di.AppContainer]; the
         * container is the only thing that should call it.
         *
         * NOTE: `fallbackToDestructiveMigration()` was removed alongside v4. Wiping the photo
         * cache on every schema bump meant a full re-scan of the account (hundreds of CloudKit
         * round trips) for something as routine as adding a column, so from v4 onwards a real
         * migration must be written. See AGENTS.md §12.
         */
        fun getInstance(context: Context): PhotoDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PhotoDatabase::class.java,
                    "icloud_photos.db"
                )
                    .addMigrations(
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
