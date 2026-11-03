package com.getup.iClouDroid.feature.drive.data

import com.getup.iClouDroid.feature.drive.data.db.DriveUploadEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The sweep itself lives in DriveRepository and needs a Context, so this pinpoints the *rule* it
 * implements: a cached copy survives only while a QUEUED/UPLOADING row still points at it.
 *
 * Getting this backwards is expensive in both directions — deleting a live file breaks an upload
 * that is about to stream, and keeping every failed one is how 5.3 GB of rejected large files
 * piled up on the device.
 */
class DriveUploadCacheSweepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun plan(files: List<File>, livePaths: Set<String>): List<File> =
        files.filter { it.isFile && it.absolutePath !in livePaths }

    @Test
    fun deletesCopiesWithNoLiveRow() {
        val live = tmp.newFile("live.bin")
        val orphanA = tmp.newFile("orphan_a.bin")
        val orphanB = tmp.newFile("orphan_b.bin")

        val toDelete = plan(listOf(live, orphanA, orphanB), setOf(live.absolutePath))

        assertEquals(2, toDelete.size)
        assertFalse(toDelete.contains(live))
        assertTrue(toDelete.contains(orphanA))
        assertTrue(toDelete.contains(orphanB))
    }

    @Test
    fun keepsEveryFileWhenAllAreLive() {
        val a = tmp.newFile("a.bin")
        val b = tmp.newFile("b.bin")

        val toDelete = plan(listOf(a, b), setOf(a.absolutePath, b.absolutePath))

        assertTrue("an in-flight upload must never lose its staged copy", toDelete.isEmpty())
    }

    @Test
    fun deletesEverythingWhenNoRowsReferenceThem() {
        val a = tmp.newFile("a.bin")
        val b = tmp.newFile("b.bin")

        val toDelete = plan(listOf(a, b), emptySet())

        assertEquals(2, toDelete.size)
    }

    /** Only QUEUED/UPLOADING rows protect a file; FAILED/SUCCESS rows must not. */
    @Test
    fun onlyPendingRowsCountAsLive() {
        val dao = FakeDao()
        val pending = DriveUploadEntity(
            localPath = "/cache/drive_uploads/pending.bin",
            targetFolderDrivewsId = "FOLDER::root",
            targetFolderPath = "/",
            filename = "pending.bin",
            filesize = 1L,
            mimeType = "application/octet-stream"
        )
        dao.rows += pending
        dao.rows += pending.copy(id = 2, localPath = "/cache/drive_uploads/failed.bin", status = "FAILED")
        dao.rows += pending.copy(id = 3, localPath = "/cache/drive_uploads/done.bin", status = "SUCCESS")

        val live = dao.rows
            .filter { it.status == "QUEUED" || it.status == "UPLOADING" }
            .map { it.localPath }
            .toSet()

        assertEquals(setOf("/cache/drive_uploads/pending.bin"), live)
    }

    private class FakeDao {
        val rows = mutableListOf<DriveUploadEntity>()
    }
}
