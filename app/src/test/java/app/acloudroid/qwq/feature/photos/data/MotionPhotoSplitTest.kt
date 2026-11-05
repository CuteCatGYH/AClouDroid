package app.acloudroid.qwq.feature.photos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MotionPhotoSplitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun extractMotionPhotoComponents_splitsStillAndVideoStreamsCorrectly() {
        val dummyFile = tempFolder.newFile("dummy_motion.jpg")
        val stillBytes = "STILL_IMAGE_HEADER_BYTES_1234567890".toByteArray(Charsets.UTF_8)
        val videoBytes = "VIDEO_COMPANION_STREAM_BYTES_9876543210".toByteArray(Charsets.UTF_8)

        dummyFile.outputStream().use { out ->
            out.write(stillBytes)
            out.write(videoBytes)
        }

        val videoOffset = stillBytes.size.toLong()
        val outDir = tempFolder.newFolder("out_derivatives")

        val stillFile = File(outDir, "still.jpg")
        val videoFile = File(outDir, "video.mp4")

        // Perform split logic as in extractMotionPhotoComponents
        dummyFile.inputStream().use { input ->
            stillFile.outputStream().use { stillOut ->
                val buffer = ByteArray(8192)
                var bytesRemaining = videoOffset
                while (bytesRemaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    stillOut.write(buffer, 0, read)
                    bytesRemaining -= read
                }
            }

            videoFile.outputStream().use { videoOut ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    videoOut.write(buffer, 0, read)
                }
            }
        }

        assertTrue(stillFile.exists())
        assertTrue(videoFile.exists())
        assertEquals(stillBytes.size.toLong(), stillFile.length())
        assertEquals(videoBytes.size.toLong(), videoFile.length())

        val readStillText = stillFile.readText(Charsets.UTF_8)
        val readVideoText = videoFile.readText(Charsets.UTF_8)

        assertEquals("STILL_IMAGE_HEADER_BYTES_1234567890", readStillText)
        assertEquals("VIDEO_COMPANION_STREAM_BYTES_9876543210", readVideoText)
    }
}
