package app.acloudroid.qwq.feature.drive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming rules for the "user asked for another copy" path.
 *
 * `DriveRepository.enqueueUpload` renames instead of overwriting when the same file was already
 * uploaded, because CloudDocs answers a same-named file in the same folder with
 * `409 UNIQUE_FIELD_FAILURE` (see the mapping in `DriveRepository`'s error branch).
 *
 * The collision scan itself needs a database, so these tests pin down the part that decides what
 * the candidate names *are*: extension splitting and the `(n)` numbering. That is where the
 * cross-platform bugs live — `report (1).pdf` opens correctly while `report.pdf (1)` does not.
 */
class DriveUploadRenameTest {

    /** The suffix goes before the extension, and numbering starts at 2. */
    @Test
    fun candidateKeepsExtensionLast() {
        assertEquals(
            listOf("report (2).pdf", "report (3).pdf", "report (4).pdf"),
            candidates("report.pdf", from = 2..4)
        )
    }

    /** A multi-dot name only splits on the final dot, matching the download naming rules. */
    @Test
    fun multiDotNameSplitsOnLastDot() {
        assertEquals(
            "archive.tar (2).gz",
            candidates("archive.tar.gz", from = 2..2).single()
        )
    }

    /** A file with no extension gets the suffix appended directly. */
    @Test
    fun extensionlessFileGetsBareSuffix() {
        assertEquals("README (2)", candidates("README", from = 2..2).single())
    }

    /**
     * A leading dot marks a hidden file, not an extension.
     *
     * `splitExtension` returns this unchanged so the candidate must not become `. (2)hidden`.
     */
    @Test
    fun hiddenFileIsNotTreatedAsExtension() {
        assertEquals(".localized (2)", candidates(".localized", from = 2..2).single())
    }

    /** A trailing dot is malformed input, not an empty extension. */
    @Test
    fun trailingDotIsTrimmed() {
        val candidate = candidates("report.", from = 2..2).single()
        assertTrue("unexpected candidate: $candidate", candidate.startsWith("report (2)"))
    }

    /** Names with a dot in the stem but nothing after it must still round-trip. */
    @Test
    fun dotInsideStemIsPreserved() {
        assertEquals(
            "v1.2 notes (2).txt",
            candidates("v1.2 notes.txt", from = 2..2).single()
        )
    }

    /** Shared with `DriveDownloader` and `DriveRepository.resolveUploadName`. */
    private fun candidates(name: String, from: IntRange): List<String> {
        val (stem, suffix) = DriveDownloader.splitExtension(name)
        return from.map { n ->
            if (suffix.isEmpty()) "$stem ($n)" else "$stem ($n).$suffix"
        }
    }
}
