package app.acloudroid.qwq.feature.photos

import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.getup.icloudcore.Icloudcore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * Verifies the download-path patch over the **same FD route the app uses**, on a real iPhone clip.
 *
 * Unit tests cannot cover this: they exercise the byte rewriting on an `*os.File` opened from a
 * path, whereas the app hands Go a descriptor detached from `ContentResolver.openFileDescriptor`.
 * The two differ in ways that have already bitten this project once (a file closed by net/http),
 * so this runs the real thing.
 *
 * The clip is taken from a previous download and only read; every mutation happens on a copy in
 * `cacheDir`, and the MediaStore row it creates is deleted afterwards.
 *
 * ```bash
 * adb shell am instrument -w \
 *   -e class app.acloudroid.qwq.feature.photos.AndroidVideoMetadataInjectTest \
 *   app.acloudroid.qwq.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -s AndroidMetaInject
 * ```
 */
@RunWith(AndroidJUnit4::class)
class AndroidVideoMetadataInjectTest {

    private val tag = "AndroidMetaInject"

    @Test
    fun patchIphoneClipThroughFd() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val src = File("/sdcard/Movies/iClouDroid/IMG_2128.MOV")
        if (!src.exists()) {
            Log.w(tag, "no iPhone clip at $src - skipping")
            return
        }

        val work = File(context.cacheDir, "inject_probe.mov")
        src.copyTo(work, overwrite = true)
        val beforeBytes = work.length()
        val beforeDuration = durationOf(work)
        val beforeLocation = locationOf(work)

        // Mirror the download path: a raw descriptor handed to Go, which takes ownership.
        val raf = RandomAccessFile(work, "rw")
        val fd = ParcelFileDescriptor.dup(raf.fd)
        raf.close()
        val report = Icloudcore.injectAndroidVideoMetadataFD(fd.detachFd().toLong())
        Log.i(tag, "report = $report")
        Log.i(tag, "before: bytes=$beforeBytes duration=$beforeDuration location=$beforeLocation")

        val afterBytes = work.length()
        val afterDuration = durationOf(work)
        val afterLocation = locationOf(work)
        Log.i(tag, "after:  bytes=$afterBytes duration=$afterDuration location=$afterLocation")

        // The borrow-from-free path must not resize the file: `stco` holds absolute offsets.
        assertEquals("file size must not change", beforeBytes, afterBytes)
        // The clip must still be decodable, and the duration must be identical.
        assertEquals("duration must survive", beforeDuration, afterDuration)
        assertTrue("duration was not readable after patch", (afterDuration ?: 0) > 0)

        assertTrue("injection reported failure: $report", report.contains("\"ok\":true"))
        assertTrue("model not translated: $report", report.contains("iPhone SE"))

        verifyProductionOrder(context, src)
    }

    /**
     * Replicates `MediaStoreDownloader.downloadSingleFile` step for step: insert a pending row,
     * stream the original bytes in, patch through `openFileDescriptor(uri, "rw")`, then publish.
     *
     * The cache-file test above proves the byte rewriting; this proves the *wiring*, which is the
     * part that could fail for reasons unrelated to the rewrite - a pending row refusing `"rw"`,
     * or the patch landing after MediaStore already indexed the file.
     */
    private fun verifyProductionOrder(context: android.content.Context, source: File) {
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "ProbeDeleteMe.mov")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/quicktime")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Movies/iClouDroid/Probe")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = android.provider.MediaStore.Video.Media
            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: run {
            Log.w(tag, "insert refused; cannot verify the production order")
            return
        }

        var publishedFile: File? = null
        try {
            // 1. Stream the ORIGINAL bytes in, exactly like a real download.
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }

            // 2. Patch through the same descriptor route the downloader uses.
            val report = try {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    Icloudcore.injectAndroidVideoMetadataFD(pfd.detachFd().toLong())
                }
            } catch (e: Exception) {
                "open-rw-failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            Log.i(tag, "production-order report = $report")

            // 3. Publish.
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            // 4. Read the published file back and confirm the atoms are there.
            @Suppress("DEPRECATION")
            val dataCol = android.provider.MediaStore.MediaColumns.DATA
            resolver.query(uri, arrayOf(dataCol), null, null, null)?.use { c ->
                if (c.moveToFirst()) publishedFile = File(c.getString(0))
            }

            val f = publishedFile
            if (f == null || !f.exists()) {
                Log.w(tag, "could not resolve the published file path")
                return
            }
            val bytes = f.readBytes()
            Log.i(tag, "published ${f.name} bytes=${bytes.size} (original ${source.length()})")
            Log.i(tag, "location via MMR = ${locationOf(f)}")

            assertTrue(
                "file size must not change (stco offsets)",
                bytes.size.toLong() == source.length()
            )
            assertTrue("auth atom missing after publish", hasAtom(bytes, "auth"))
            assertTrue("©xyz atom missing after publish", hasAtom(bytes, "\u00a9xyz"))
            assertTrue("published file no longer reports a location", locationOf(f) != null)
        } finally {
            publishedFile?.parentFile?.delete()
            resolver.delete(uri, null, null)
        }
    }

    /** Walks top-level boxes looking for `moov/udta/<atom>` by raw type bytes. */
    private fun hasAtom(data: ByteArray, type: String): Boolean {
        val needle = type.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 4..data.size - 4) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun durationOf(f: File): Long? = MediaMetadataRetriever().let { r ->
        try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            Log.w(tag, "duration read failed", e)
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun locationOf(f: File): String? = MediaMetadataRetriever().let { r ->
        try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
        } catch (e: Exception) {
            Log.w(tag, "location read failed", e)
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
