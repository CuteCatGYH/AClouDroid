package app.acloudroid.qwq.feature.photos.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.regex.Pattern

/**
 * Information about a detected Android Motion Photo (Live Photo).
 */
data class MotionPhotoInfo(
    val isMotionPhoto: Boolean = false,
    val videoOffset: Long = 0L,
    val videoSize: Long = 0L,
    val presentationTimestampUs: Long = 0L
)

/**
 * Utility for detecting Google / Samsung Motion Photo XMP metadata in local image files (JPEG, HEIC, &c.).
 */
object MotionPhotoDetector {
    private const val TAG = "MotionPhotoDetector"

    // Standard GCamera Motion Photo patterns
    private val MOTION_PHOTO_PATTERN = Pattern.compile(
        "(?:GCamera|Camera):MotionPhoto\\s*=\\s*[\"']?1[\"']?",
        Pattern.CASE_INSENSITIVE
    )
    private val MICRO_VIDEO_OFFSET_PATTERN = Pattern.compile(
        "(?:GCamera|Camera):MicroVideoOffset\\s*=\\s*[\"']?(\\d+)[\"']?",
        Pattern.CASE_INSENSITIVE
    )
    private val MICRO_VIDEO_TIMESTAMP_PATTERN = Pattern.compile(
        "(?:GCamera|Camera):MicroVideoPresentationTimestampUs\\s*=\\s*[\"']?(\\d+)[\"']?",
        Pattern.CASE_INSENSITIVE
    )

    // Container / Item format patterns (Pixel / Samsung Motion Photo v2)
    private val ITEM_BLOCK_PATTERN = Pattern.compile(
        "<(?:Container|Directory):Item\\b[^>]*>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )
    private val ITEM_LENGTH_PATTERN = Pattern.compile(
        "Item:Length\\s*=\\s*[\"']?(\\d+)[\"']?",
        Pattern.CASE_INSENSITIVE
    )
    private val ITEM_PADDING_PATTERN = Pattern.compile(
        "Item:Padding\\s*=\\s*[\"']?(\\d+)[\"']?",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Detects Motion Photo metadata from a local [Uri] and its file size.
     */
    fun detect(context: Context, uri: Uri, fileSize: Long): MotionPhotoInfo {
        if (fileSize <= 0) return MotionPhotoInfo()

        return try {
            val readLimit = minOf(fileSize, 2 * 1024 * 1024L).toInt()
            val inputStream = if (uri.scheme == "file" && uri.path != null) {
                try {
                    java.io.FileInputStream(java.io.File(uri.path!!))
                } catch (e: Exception) {
                    context.contentResolver.openInputStream(uri)
                }
            } else {
                context.contentResolver.openInputStream(uri)
            }

            val headerBytes = inputStream?.use { stream ->
                val buf = ByteArray(readLimit)
                var bytesRead = 0
                while (bytesRead < readLimit) {
                    val read = stream.read(buf, bytesRead, readLimit - bytesRead)
                    if (read <= 0) break
                    bytesRead += read
                }
                if (bytesRead < readLimit) buf.copyOf(bytesRead) else buf
            } ?: return MotionPhotoInfo()

            val xmpString = extractXmpFromBytes(headerBytes)
            if (!xmpString.isNullOrBlank()) {
                parseXmpString(xmpString, fileSize)
            } else {
                MotionPhotoInfo()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting Motion Photo for $uri: ${e.message}")
            MotionPhotoInfo()
        }
    }

    /**
     * Detects Motion Photo metadata from an [InputStream] and total file size.
     */
    fun detectFromStream(inputStream: InputStream, fileSize: Long): MotionPhotoInfo {
        if (fileSize <= 0) return MotionPhotoInfo()

        return try {
            val readLimit = minOf(fileSize, 2 * 1024 * 1024L).toInt()
            val buf = ByteArray(readLimit)
            var bytesRead = 0
            while (bytesRead < readLimit) {
                val read = inputStream.read(buf, bytesRead, readLimit - bytesRead)
                if (read <= 0) break
                bytesRead += read
            }
            val headerBytes = if (bytesRead < readLimit) buf.copyOf(bytesRead) else buf

            val xmpString = extractXmpFromBytes(headerBytes)
            if (!xmpString.isNullOrBlank()) {
                parseXmpString(xmpString, fileSize)
            } else {
                MotionPhotoInfo()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting Motion Photo from stream: ${e.message}")
            MotionPhotoInfo()
        }
    }

    /**
     * Extracts XMP string from the initial file header bytes.
     * Tries ExifInterface first, then falls back to direct XML block scanning (required for HEIC).
     */
    fun extractXmpFromBytes(bytes: ByteArray): String? {
        // 1. Try ExifInterface (works for JPEG on device)
        try {
            ByteArrayInputStream(bytes).use { stream ->
                val exifInterface = ExifInterface(stream)
                val xmp = exifInterface.getAttribute(ExifInterface.TAG_XMP)
                if (!xmp.isNullOrBlank()) {
                    return xmp
                }
            }
        } catch (_: Throwable) {
            // Catches Throwable (e.g. unmocked android.util.Log in JVM unit tests)
        }

        // 2. Direct string scan for <x:xmpmeta ... </x:xmpmeta> (works for HEIC and all formats)
        val str = String(bytes, Charsets.UTF_8)
        val startIdx = str.indexOf("<x:xmpmeta")
        if (startIdx != -1) {
            val endIdx = str.indexOf("</x:xmpmeta>", startIdx)
            if (endIdx != -1) {
                return str.substring(startIdx, endIdx + "</x:xmpmeta>".length)
            }
        }

        // 3. Fallback scan for <rdf:RDF ... </rdf:RDF>
        val rdfStart = str.indexOf("<rdf:RDF")
        if (rdfStart != -1) {
            val rdfEnd = str.indexOf("</rdf:RDF>", rdfStart)
            if (rdfEnd != -1) {
                return str.substring(rdfStart, rdfEnd + "</rdf:RDF>".length)
            }
        }

        return null
    }

    /**
     * Parses an XMP XML string payload to extract Motion Photo video offset and size.
     */
    fun parseXmpString(xmp: String, fileSize: Long): MotionPhotoInfo {
        if (xmp.isBlank() || fileSize <= 0) return MotionPhotoInfo()

        // 1. Check GCamera:MotionPhoto="1" + MicroVideoOffset
        val hasMotionPhotoFlag = MOTION_PHOTO_PATTERN.matcher(xmp).find()
        val offsetMatcher = MICRO_VIDEO_OFFSET_PATTERN.matcher(xmp)

        if (hasMotionPhotoFlag && offsetMatcher.find()) {
            val microVideoOffset = offsetMatcher.group(1)?.toLongOrNull() ?: 0L
            if (microVideoOffset > 0 && microVideoOffset < fileSize) {
                val timestampMatcher = MICRO_VIDEO_TIMESTAMP_PATTERN.matcher(xmp)
                val timestampUs = if (timestampMatcher.find()) {
                    timestampMatcher.group(1)?.toLongOrNull() ?: 0L
                } else {
                    0L
                }

                return MotionPhotoInfo(
                    isMotionPhoto = true,
                    videoOffset = fileSize - microVideoOffset,
                    videoSize = microVideoOffset,
                    presentationTimestampUs = timestampUs
                )
            }
        }

        // 2. Check Container / Item format (Pixel / Samsung Motion Photo v2)
        if (xmp.contains("MotionPhoto", ignoreCase = true)) {
            val matcher = ITEM_BLOCK_PATTERN.matcher(xmp)
            while (matcher.find()) {
                val itemBlock = matcher.group(0) ?: ""
                if (itemBlock.contains("Semantic=\"MotionPhoto\"", ignoreCase = true) ||
                    itemBlock.contains("Semantic='MotionPhoto'", ignoreCase = true)
                ) {
                    val lengthMatcher = ITEM_LENGTH_PATTERN.matcher(itemBlock)
                    if (lengthMatcher.find()) {
                        val itemLength = lengthMatcher.group(1)?.toLongOrNull() ?: 0L
                        val paddingMatcher = ITEM_PADDING_PATTERN.matcher(itemBlock)
                        val padding = if (paddingMatcher.find()) {
                            paddingMatcher.group(1)?.toLongOrNull() ?: 0L
                        } else {
                            0L
                        }

                        val totalVideoSize = itemLength + padding
                        if (totalVideoSize > 0 && totalVideoSize < fileSize) {
                            return MotionPhotoInfo(
                                isMotionPhoto = true,
                                videoOffset = fileSize - totalVideoSize,
                                videoSize = totalVideoSize,
                                presentationTimestampUs = 0L
                            )
                        }
                    }
                }
            }
        }

        return MotionPhotoInfo()
    }
}
