package com.getup.iClouDroid.feature.drive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filename and MIME handling for Drive downloads.
 *
 * These are pure functions but they sit on a security boundary: the name comes from another
 * device and is written to shared storage. A name containing `/` would create a nested path, and
 * a name containing `..` could escape the target directory entirely.
 */
class DriveDownloadNamingTest {

    // ---- sanitizeFileName ----------------------------------------------------------------

    @Test
    fun `plain name is unchanged`() {
        assertEquals("report.pdf", DriveDownloader.sanitizeFileName("report.pdf", "pdf"))
    }

    /** The listing's `name` usually already carries the extension; appending would double it. */
    @Test
    fun `extension is not appended twice`() {
        assertEquals("report.pdf", DriveDownloader.sanitizeFileName("report.pdf", "pdf"))
        assertEquals("photo.HEIC", DriveDownloader.sanitizeFileName("photo.HEIC", "heic"))
    }

    @Test
    fun `extension is appended when the name lacks it`() {
        assertEquals("report.pdf", DriveDownloader.sanitizeFileName("report", "pdf"))
    }

    @Test
    fun `no extension means no suffix`() {
        assertEquals("README", DriveDownloader.sanitizeFileName("README", null))
        assertEquals("README", DriveDownloader.sanitizeFileName("README", ""))
    }

    /** A slash must never survive: it would turn one file into a directory path. */
    @Test
    fun `path separators are replaced`() {
        val out = DriveDownloader.sanitizeFileName("../../etc/passwd", null)
        assertFalse("must not contain a slash: $out", out.contains('/'))
        assertFalse("must not contain a backslash: $out", out.contains('\\'))
    }

    @Test
    fun `windows-illegal characters are replaced`() {
        val out = DriveDownloader.sanitizeFileName("a:b*c?d\"e<f>g|h", null)
        listOf(':', '*', '?', '"', '<', '>', '|').forEach {
            assertFalse("must not contain $it: $out", out.contains(it))
        }
    }

    @Test
    fun `control characters are replaced`() {
        val out = DriveDownloader.sanitizeFileName("a\u0000b\u001Fc", null)
        assertFalse(out.contains('\u0000'))
        assertFalse(out.contains('\u001F'))
    }

    @Test
    fun `blank name falls back`() {
        assertEquals("未命名", DriveDownloader.sanitizeFileName("", null))
        assertEquals("未命名", DriveDownloader.sanitizeFileName("   ", null))
    }

    /** Android and most filesystems reject a trailing dot. */
    @Test
    fun `trailing dots are stripped`() {
        val out = DriveDownloader.sanitizeFileName("report...", null)
        assertFalse("must not end with a dot: $out", out.endsWith("."))
    }

    @Test
    fun `very long names are truncated`() {
        val out = DriveDownloader.sanitizeFileName("x".repeat(500), null)
        assertTrue("length ${out.length} should be bounded", out.length <= 180)
    }

    @Test
    fun `unicode names survive`() {
        val out = DriveDownloader.sanitizeFileName("空文件夹报告", "pdf")
        assertEquals("空文件夹报告.pdf", out)
    }

    // ---- splitExtension ------------------------------------------------------------------

    @Test
    fun `simple extension splits`() {
        assertEquals("report" to "pdf", DriveDownloader.splitExtension("report.pdf"))
    }

    @Test
    fun `only the last dot splits`() {
        assertEquals("archive.tar" to "gz", DriveDownloader.splitExtension("archive.tar.gz"))
    }

    @Test
    fun `no extension yields empty suffix`() {
        assertEquals("README" to "", DriveDownloader.splitExtension("README"))
    }

    /**
     * `.localized` is a hidden file, not a file named "" with extension "localized". Splitting it
     * would produce the nonsense name ` (1).localized`.
     */
    @Test
    fun `leading dot is not an extension`() {
        assertEquals(".localized" to "", DriveDownloader.splitExtension(".localized"))
    }

    @Test
    fun `trailing dot is not an extension`() {
        assertEquals("report" to "", DriveDownloader.splitExtension("report."))
    }

    @Test
    fun `name from a listing without extension`() {
        assertEquals("20261026_191909" to "", DriveDownloader.splitExtension("20261026_191909"))
    }

    // ---- the duplicate-suffix shape itself --------------------------------------------

    /**
     * The suffix goes before the extension. `report.pdf (1)` would lose the file type and the
     * system would no longer offer a PDF viewer for it.
     */
    @Test
    fun `suffix is placed before the extension`() {
        val (stem, suffix) = DriveDownloader.splitExtension("report.pdf")
        val renamed = if (suffix.isEmpty()) "$stem (1)" else "$stem (1).$suffix"
        assertEquals("report (1).pdf", renamed)
    }

    @Test
    fun `suffixing a file without extension`() {
        val (stem, suffix) = DriveDownloader.splitExtension("README")
        val renamed = if (suffix.isEmpty()) "$stem (1)" else "$stem (1).$suffix"
        assertEquals("README (1)", renamed)
    }

    @Test
    fun `suffixing a hidden file keeps it hidden`() {
        val (stem, suffix) = DriveDownloader.splitExtension(".localized")
        val renamed = if (suffix.isEmpty()) "$stem (1)" else "$stem (1).$suffix"
        assertEquals(".localized (1)", renamed)
    }

    // ---- mimeTypeFor ---------------------------------------------------------------------

    @Test
    fun `common types map correctly`() {
        assertEquals("application/pdf", DriveDownloader.mimeTypeFor("pdf"))
        assertEquals("image/jpeg", DriveDownloader.mimeTypeFor("jpg"))
        assertEquals("video/mp4", DriveDownloader.mimeTypeFor("mp4"))
        assertEquals("text/plain", DriveDownloader.mimeTypeFor("txt"))
        assertEquals("application/zip", DriveDownloader.mimeTypeFor("zip"))
    }

    @Test
    fun `extension matching is case-insensitive`() {
        assertEquals("image/jpeg", DriveDownloader.mimeTypeFor("JPG"))
        assertEquals("application/pdf", DriveDownloader.mimeTypeFor("PDF"))
    }

    /**
     * An unknown extension must not guess something specific. MediaStore uses the type to build
     * the "open with" list, and a wrong specific type offers apps that cannot open the file.
     */
    @Test
    fun `unknown extension falls back to octet-stream`() {
        assertEquals("application/octet-stream", DriveDownloader.mimeTypeFor("qqq"))
        assertEquals("application/octet-stream", DriveDownloader.mimeTypeFor(null))
        assertEquals("application/octet-stream", DriveDownloader.mimeTypeFor(""))
    }
}
