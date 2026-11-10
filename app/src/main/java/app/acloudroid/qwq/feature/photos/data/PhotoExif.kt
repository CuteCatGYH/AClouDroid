package app.acloudroid.qwq.feature.photos.data

import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject

/**
 * Parsed EXIF/GPS for one asset, as emitted by `icloudcore.FetchPhotoMetadata`.
 *
 * Every field is nullable, and `null` means "the camera did not record this". That is the whole
 * point of the type: the info panel renders only what is present, so absence has to be
 * representable. Never substitute 0 for a missing number — `f/0` and `ISO 0` are not "unknown",
 * they are wrong.
 *
 * Reference for the source fields: docs/REFERENCE.md §2A.
 */
data class PhotoExif(
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lensModel: String? = null,

    /** Aperture, e.g. 1.85 for f/1.85. */
    val fNumber: Double? = null,
    /** Shutter speed in seconds, e.g. 0.001353 (~1/739s). */
    val exposureTime: Double? = null,
    val iso: Int? = null,
    /** Focal length in mm. */
    val focalLength: Double? = null,
    /** 35mm-equivalent focal length in mm. */
    val focalLength35: Int? = null,
    val exposureBias: Double? = null,

    /** Apple's EXIF capture time, verbatim (`"2026:10:21 11:03:54"`). */
    val dateTimeOriginal: String? = null,

    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
    val colorModel: String? = null,
    val profileName: String? = null,

    /** Null when the record has no `{GPS}` block — the common case (~68% of samples). */
    val gps: PhotoGps? = null,

    /** False when the record carries no `mediaMetaDataEnc` at all (screenshots, imports). */
    val hasMetadata: Boolean = false
)

/** Position block. Only present when the photo actually has a fix. */
data class PhotoGps(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val speed: Double? = null,
    /** Compass heading of the camera at capture, degrees. */
    val imgDirection: Double? = null,
    /** Horizontal accuracy, metres. */
    val horizontalError: Double? = null,
    val timestamp: String? = null
)

object PhotoExifParser {
    /**
     * Parses the JSON emitted by `icloudcore.FetchPhotoMetadata`.
     *
     * Returns null only for a blank/unparseable payload. A well-formed object with no camera
     * fields (a screenshot) still returns a [PhotoExif] with `hasMetadata = false`, because the
     * caller needs to distinguish "no metadata" from "not fetched yet".
     */
    fun parse(json: String?): PhotoExif? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            PhotoExif(
                cameraMake = o.optStringOrNull("cameraMake"),
                cameraModel = o.optStringOrNull("cameraModel"),
                lensModel = o.optStringOrNull("lensModel"),
                fNumber = o.optDoubleOrNull("fNumber"),
                exposureTime = o.optDoubleOrNull("exposureTime"),
                iso = o.optIntOrNull("iso"),
                focalLength = o.optDoubleOrNull("focalLength"),
                focalLength35 = o.optDoubleOrNull("focalLength35")?.toInt(),
                exposureBias = o.optDoubleOrNull("exposureBias"),
                dateTimeOriginal = o.optStringOrNull("dateTimeOriginal"),
                pixelWidth = o.optIntOrNull("pixelWidth"),
                pixelHeight = o.optIntOrNull("pixelHeight"),
                colorModel = o.optStringOrNull("colorModel"),
                profileName = o.optStringOrNull("profileName"),
                gps = o.optJSONObject("gps")?.let { g ->
                    // A GPS object without a usable coordinate pair is not a location.
                    val lat = g.optDoubleOrNull("latitude") ?: return@let null
                    val lng = g.optDoubleOrNull("longitude") ?: return@let null
                    PhotoGps(
                        latitude = lat,
                        longitude = lng,
                        altitude = g.optDoubleOrNull("altitude"),
                        speed = g.optDoubleOrNull("speed"),
                        imgDirection = g.optDoubleOrNull("imgDirection"),
                        horizontalError = g.optDoubleOrNull("hPositioningError"),
                        timestamp = g.optStringOrNull("timestamp")
                    )
                },
                hasMetadata = o.optBoolean("hasMetadata", false)
            )
        } catch (e: Exception) {
            null
        }
    }

    // org.json's optDouble/optInt return 0.0/0 for a missing key, which is indistinguishable from
    // a genuine zero. Since 0 is never a valid aperture/shutter/ISO, presence must be checked.
    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}

object LocalExifExtractor {
    data class LocalExifData(
        val assetDate: Long,
        val originalCreationDate: Long,
        val width: Int,
        val height: Int,
        val orientation: Int,
        val make: String? = null,
        val model: String? = null,
        val software: String? = null,
        val lensModel: String? = null,
        val fNumber: Double? = null,
        val exposureTime: Double? = null,
        val iso: Int? = null,
        val focalLength: Double? = null,
        val focalLength35: Int? = null,
        val altitude: Double? = null,
        val dateTimeOriginal: String? = null,
        val lat: Double? = null,
        val lon: Double? = null
    )

    data class DerivativeResult(
        val thumbPath: String? = null,
        val thumbWidth: Int = 0,
        val thumbHeight: Int = 0,
        val medPath: String? = null,
        val medWidth: Int = 0,
        val medHeight: Int = 0
    )

    data class MotionPhotoComponents(
        val stillFile: java.io.File,
        val videoFile: java.io.File,
        val videoInfo: VideoInfo
    )

    /**
     * Everything `CPLMaster` needs for a movie record.
     *
     * Videos go through a completely different asset class than photos: they have no EXIF, and
     * Apple still stores real `resOriginalWidth` / `resOriginalHeight` / `videoFrameRate` plus a
     * JPEG poster frame. Uploading one without those produced a record the Apple clients could
     * not render at all (ISS-040).
     */
    data class VideoInfo(
        val width: Int = 0,
        val height: Int = 0,
        val durationMs: Long = 0,
        val rotation: Int = 0,
        val frameRate: Double? = null,
        val codec: String? = null,
        val creationDateMillis: Long = 0L,
        /**
         * Device make / model / OS for the QuickTime metadata block.
         *
         * Apple renders a video's camera info from `moov/meta` (`com.apple.quicktime.make`
         * &c.), not from EXIF - an Android-recorded MP4 has no such atoms, so the panel reports
         * "no camera information" until we write them. These values come from `Build`, which is
         * accurate here because a local-album backup only ever uploads clips shot on (or
         * imported to) this device.
         */
        val make: String? = null,
        val model: String? = null,
        val software: String? = null,
        /** ISO-6709 coordinates from the file/MediaStore, when location permission allows. */
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    fun extract(context: android.content.Context, uri: android.net.Uri): LocalExifData {
        val now = System.currentTimeMillis()
        var assetDate = now
        var width = 0
        var height = 0
        var orientation = 1
        var make: String? = null
        var model: String? = null
        var software: String? = null
        var lensModel: String? = null
        var fNumber: Double? = null
        var exposureTime: Double? = null
        var iso: Int? = null
        var focalLength: Double? = null
        var focalLength35: Int? = null
        var altitude: Double? = null
        var dateTimeOriginal: String? = null
        var lat: Double? = null
        var lon: Double? = null

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exifInterface = ExifInterface(inputStream)

                val dateStr: String? = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exifInterface.getAttribute(ExifInterface.TAG_DATETIME)
                if (!dateStr.isNullOrBlank()) {
                    dateTimeOriginal = dateStr
                    val parsedTime = parseExifDate(dateStr)
                    if (parsedTime > 0) {
                        assetDate = parsedTime
                    }
                }

                width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)

                make = exifInterface.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                model = exifInterface.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                software = exifInterface.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim()
                lensModel = exifInterface.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()

                val fn = exifInterface.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)
                if (fn > 0) fNumber = fn

                val et = exifInterface.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
                if (et > 0) exposureTime = et

                val isoVal = exifInterface.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
                if (isoVal > 0) iso = isoVal

                val fl = exifInterface.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
                if (fl > 0) focalLength = fl

                val fl35 = exifInterface.getAttributeInt(
                    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, -1
                )
                if (fl35 > 0) focalLength35 = fl35

                // MediaProvider redacts location by **zeroing** the GPS rationals rather than
                // removing the tags, so a permission-less read still yields a `latLong` of
                // [0.0, 0.0]. Writing that through would drop a pin in the Gulf of Guinea for
                // every backed-up photo, which is worse than no pin at all.
                val latLong = exifInterface.latLong
                if (latLong != null && latLong.size >= 2) {
                    val pLat = latLong[0]
                    val pLon = latLong[1]
                    if (pLat != 0.0 || pLon != 0.0) {
                        lat = pLat
                        lon = pLon
                    }
                }

                val alt = exifInterface.getAttributeDouble(ExifInterface.TAG_GPS_ALTITUDE, Double.NaN)
                // EXIF encodes altitude as a positive magnitude plus a below-sea-level ref.
                // Only meaningful once we have a position; a redacted read carries 0 here too.
                if (!alt.isNaN() && lat != null) {
                    val belowSeaLevel = exifInterface.getAttributeInt(
                        ExifInterface.TAG_GPS_ALTITUDE_REF, 0
                    ) == 1
                    altitude = if (belowSeaLevel) -alt else alt
                }
            }
        } catch (e: Exception) {
            // Fallback silently if EXIF read fails
        }

        return LocalExifData(
            assetDate = assetDate,
            originalCreationDate = assetDate,
            width = width,
            height = height,
            orientation = orientation,
            make = make,
            model = model,
            software = software,
            lensModel = lensModel,
            fNumber = fNumber,
            exposureTime = exposureTime,
            iso = iso,
            focalLength = focalLength,
            focalLength35 = focalLength35,
            altitude = altitude,
            dateTimeOriginal = dateTimeOriginal,
            lat = lat,
            lon = lon
        )
    }

    /**
     * Generates temporary 320px (Thumbnail) and 2048px (Medium) JPEG files in cacheDir for CPL derivatives.
     *
     * @param orientation the source's EXIF orientation, supplied by the caller instead of being
     *   re-read here. The upload path already parsed it via [extract], and re-opening the
     *   content URI a second (and third) time both costs round trips and creates a second source
     *   of truth that can disagree with the record we write.
     */
    fun generateDerivatives(
        context: android.content.Context,
        uri: android.net.Uri,
        orientation: Int
    ): DerivativeResult {
        var thumbPath: String? = null
        var thumbW = 0
        var thumbH = 0
        var medPath: String? = null
        var medW = 0
        var medH = 0

        try {
            val outDir = java.io.File(context.cacheDir, "upload_derivatives")
            if (!outDir.exists()) {
                outDir.mkdirs()
            }

            // Decode dimensions first
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, options)
            }

            val origW = options.outWidth
            val origH = options.outHeight

            if (origW > 0 && origH > 0) {
                // 1. Generate the Thumbnail JPEG (`resJPEGThumbRes`).
                //
                // This is the derivative every Apple client paints in its grid, so its long edge
                // is what "how sharp does my backup look" actually means. The 320px we shipped
                // before was visibly soft next to a device-captured asset, whose own
                // `resJPEGThumbRes` measures ~311x553 (video) to ~384 (photo). 512 sits just above
                // that range: still small enough to stay inside what iCloud accepts for this
                // field, large enough to survive a 3x-density full-width tile.
                //
                // Quality is 85 to match the medium derivative. Raising the edge while leaving a
                // JPEG quality of 80 mostly buys ringing artefacts, not sharpness.
                val thumbBitmap = decodeSampledBitmap(
                    context, uri, origW, origH, THUMB_MAX_DIM, orientation
                )
                if (thumbBitmap != null) {
                    val thumbFile = java.io.File(outDir, "thumb_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.jpg")
                    java.io.FileOutputStream(thumbFile).use { out ->
                        thumbBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    thumbW = thumbBitmap.width
                    thumbH = thumbBitmap.height
                    thumbPath = thumbFile.absolutePath
                    thumbBitmap.recycle()
                }

                // 2. Generate 2048px Medium JPEG (`resJPEGMedRes`).
                //
                // Matches the measured size of Apple's own medium derivative for a 4000x3000
                // capture (1536x2048). Do not exceed 2048: this field is also what the full-screen
                // viewer and the system picker fall back to.
                val medBitmap = decodeSampledBitmap(
                    context, uri, origW, origH, MED_MAX_DIM, orientation
                )
                if (medBitmap != null) {
                    val medFile = java.io.File(outDir, "med_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.jpg")
                    java.io.FileOutputStream(medFile).use { out ->
                        medBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    medW = medBitmap.width
                    medH = medBitmap.height
                    medPath = medFile.absolutePath
                    medBitmap.recycle()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("LocalExifExtractor", "Failed to generate image derivatives: ${e.message}")
        }

        return DerivativeResult(
            thumbPath = thumbPath,
            thumbWidth = thumbW,
            thumbHeight = thumbH,
            medPath = medPath,
            medWidth = medW,
            medHeight = medH
        )
    }

    private fun decodeSampledBitmap(
        context: android.content.Context,
        uri: android.net.Uri,
        reqW: Int,
        reqH: Int,
        targetMaxDim: Int,
        orientation: Int
    ): android.graphics.Bitmap? {
        val maxDim = Math.max(reqW, reqH)
        var sampleSize = 1
        if (maxDim > targetMaxDim) {
            sampleSize = Math.round(maxDim.toFloat() / targetMaxDim.toFloat())
        }

        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = Math.max(1, sampleSize)
        }

        // BitmapFactory never applies EXIF orientation, so a portrait capture decoded here is a
        // landscape buffer. The derivatives must be **physically upright** because they carry no
        // EXIF of their own - same as Apple's, whose med frame for a 4000x3000 `orientation = 6`
        // capture measures 1536x2048.
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream, null, opts)
        }?.let { applyExifOrientation(it, orientation) }
    }

    /**
     * Bakes EXIF orientation into the pixels.
     *
     * The derived JPEGs must be **physically upright**, because they carry no EXIF of their own.
     * Apple's own derivatives are baked the same way: for a 4000x3000 capture with
     * `orientation = 6` their `resJPEGMedRes` measures 1536x2048, i.e. rotated. Ours used to ship
     * a landscape 2000x1500 frame for the same photo, which showed up sideways in iCloud and in
     * macOS Photos (ISS-040).
     *
     * This mirrors what the download side already does (ISS-037).
     */
    private fun applyExifOrientation(
        bitmap: android.graphics.Bitmap,
        orientation: Int
    ): android.graphics.Bitmap {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(270f)
            else -> return bitmap
        }

        return try {
            val rotated = android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            // A failed rotation is better than a failed upload: keep the unrotated frame.
            android.util.Log.w("LocalExifExtractor", "orientation bake failed: ${e.message}")
            bitmap
        }
    }

    /**
     * Reads movie dimensions, duration, rotation, frame rate and codec.
     *
     * Apple stores all of these on `CPLMaster` for videos, and a video uploaded without them is
     * unrenderable on their side (ISS-040). `ExifInterface` cannot read any of it for an mp4, and
     * previously this path returned all zeroes.
     */
    fun extractVideoInfo(context: android.content.Context, uri: android.net.Uri): VideoInfo {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            var width = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            var height = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            // Apple records post-rotation dimensions, so a portrait clip must report
            // height > width. Android reports the encoded frame size plus a rotation hint.
            if (rotation == 90 || rotation == 270) {
                val swapped = width
                width = height
                height = swapped
            }

            val duration = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val frameRate = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull()
                ?: retriever
                    .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toDoubleOrNull()
                    ?.takeIf { duration > 0 }
                    ?.let { it / (duration / 1000.0) }

            val mime = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)

            val dateMillis = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)
                ?.let { parseMediaDate(it) } ?: 0L

            val coords = readVideoLocation(context, uri, retriever)
            VideoInfo(
                width = width,
                height = height,
                durationMs = duration,
                rotation = rotation,
                frameRate = frameRate,
                codec = codecName(mime),
                creationDateMillis = dateMillis,
                make = android.os.Build.MANUFACTURER?.takeIf { it.isNotBlank() },
                model = android.os.Build.MODEL?.takeIf { it.isNotBlank() },
                software = android.os.Build.VERSION.RELEASE,
                latitude = coords?.first,
                longitude = coords?.second
            )
        } catch (e: Exception) {
            android.util.Log.w("LocalExifExtractor", "Failed to read video info: ${e.message}")
            VideoInfo()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Extracts a Motion Photo's JPEG still and MP4 companion video into temporary files.
     */
    fun extractMotionPhotoComponents(
        context: android.content.Context,
        contentUri: android.net.Uri,
        videoOffset: Long
    ): MotionPhotoComponents {
        val outDir = java.io.File(context.cacheDir, "upload_derivatives").apply { if (!exists()) mkdirs() }
        val uuid = java.util.UUID.randomUUID().toString()
        val stillFile = java.io.File(outDir, "motion_still_$uuid.jpg")
        val videoFile = java.io.File(outDir, "motion_vid_$uuid.mp4")

        context.contentResolver.openInputStream(contentUri)?.use { input ->
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

        val videoInfo = extractVideoInfoFromFile(videoFile)
        return MotionPhotoComponents(
            stillFile = stillFile,
            videoFile = videoFile,
            videoInfo = videoInfo
        )
    }

    /**
     * Extracts video info from a local video [File].
     */
    fun extractVideoInfoFromFile(file: java.io.File): VideoInfo {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)

            var width = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            var height = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            if (rotation == 90 || rotation == 270) {
                val swapped = width
                width = height
                height = swapped
            }

            val duration = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val frameRate = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull()
                ?: retriever
                    .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toDoubleOrNull()
                    ?.takeIf { duration > 0 }
                    ?.let { it / (duration / 1000.0) }

            val mime = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)

            VideoInfo(
                width = width,
                height = height,
                durationMs = duration,
                rotation = rotation,
                frameRate = frameRate,
                codec = codecName(mime),
                creationDateMillis = 0L,
                make = android.os.Build.MANUFACTURER?.takeIf { it.isNotBlank() },
                model = android.os.Build.MODEL?.takeIf { it.isNotBlank() },
                software = android.os.Build.VERSION.RELEASE
            )
        } catch (e: Exception) {
            android.util.Log.w("LocalExifExtractor", "Failed to read video info from file: ${e.message}")
            VideoInfo()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Reads GPS coordinates for a video from every source that can carry them.
     *
     * **All of these require `ACCESS_MEDIA_LOCATION`.** Without it MediaProvider strips location
     * before we ever see the bytes - measured on a real capture: `moov/udta/©xyz` exists with the
     * permission and is absent without it, so there is no permission-free path.
     *
     *  1. `METADATA_KEY_LOCATION` - Apple/Samsung write an ISO-6709 string into `udta/©xyz`.
     *  2. MediaStore LATITUDE / LONGITUDE - often simply unset, permission or not.
     *  3. Nothing, reported as null so callers omit the field rather than writing 0,0.
     */
    private fun readVideoLocation(
        context: android.content.Context,
        uri: android.net.Uri,
        retriever: android.media.MediaMetadataRetriever? = null
    ): Pair<Double, Double>? {
        // 1. QuickTime ©xyz, surfaced by the extractor as an ISO-6709 string.
        retriever?.let { r ->
            runCatching {
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_LOCATION)
            }.getOrNull()?.let { raw ->
                parseIso6709(raw)?.let { return it }
            }
        }

        // 2. MediaStore columns.
        return try {
            @Suppress("DEPRECATION")
            val cols = arrayOf(
                android.provider.MediaStore.Video.Media.LATITUDE,
                android.provider.MediaStore.Video.Media.LONGITUDE
            )
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val lat = c.getDouble(0)
                val lon = c.getDouble(1)
                // MediaStore uses 0/0 for "no fix", and it is also a valid coordinate, so treat
                // an exact zero pair as absent - the odds of a real capture at exactly 0,0 are
                // nil and a wrong pin is worse than no pin.
                if (lat == 0.0 && lon == 0.0) null else lat to lon
            }
        } catch (e: Exception) {
            android.util.Log.d("LocalExifExtractor", "video location unavailable: ${e.message}")
            null
        }
    }

    /**
     * Parses an ISO-6709 point: `+35.9553+120.1715/`, `-33.8688+151.2093/`.
     *
     * Apple appends altitude as a third signed component (`+35.9552+120.1714+025.683/`), which
     * must be ignored here - latitude and longitude are the first two.
     */
    internal fun parseIso6709(raw: String?): Pair<Double, Double>? {
        if (raw.isNullOrBlank()) return null
        val body = raw.trim().removeSuffix("/")
        val matches = Regex("[+-]\\d+(?:\\.\\d+)?").findAll(body).map { it.value }.toList()
        if (matches.size < 2) return null
        val lat = matches[0].toDoubleOrNull() ?: return null
        val lon = matches[1].toDoubleOrNull() ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        return lat to lon
    }

    /**
     * Builds the JPEG poster frame Apple expects at `resJPEGThumbRes` / `resJPEGMedRes`.
     *
     * Without these a video has no thumbnail anywhere: Apple shows an exclamation mark, and our
     * own grid shows the Coil error slot (the grey "iCloud" tile).
     */
    fun generateVideoDerivatives(
        context: android.content.Context,
        uri: android.net.Uri,
        rotation: Int
    ): DerivativeResult {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return DerivativeResult()

            // **Do not rotate blindly.** `getFrameAtTime` is documented to return the raw encoded
            // frame, but in practice MediaCodec applies the container rotation on many devices -
            // so rotating by `METADATA_KEY_VIDEO_ROTATION` unconditionally double-rotates and the
            // poster frame lands 90° off (observed on SM-F9760: portrait clips came out sideways).
            //
            // The frame's own aspect ratio settles it without guessing. A clip whose rotation is
            // 90/270 is encoded landscape and displayed portrait, so:
            //   * frame still landscape -> rotation metadata has NOT been applied, rotate it
            //   * frame already portrait -> the decoder applied it, leave it alone
            val encodedLandscape = frame.width > frame.height
            val needsRotation = (rotation == 90 || rotation == 270) && encodedLandscape
            // 180° cannot be detected from the aspect ratio. It is also vanishingly rare on
            // phone-captured video, and the decoder does not apply it, so honour it.
            val orientation = when {
                needsRotation -> rotationToOrientation(rotation)
                rotation == 180 -> 3
                else -> 1
            }
            val upright = applyExifOrientation(frame, orientation)

            val outDir = java.io.File(context.cacheDir, "upload_derivatives").apply { mkdirs() }

            // Same long edge and quality as the photo thumbnail: a poster frame is a poster
            // frame, and Apple paints both in the same grid at the same size.
            val thumb = scaleDown(upright, THUMB_MAX_DIM)
            val thumbFile = java.io.File(
                outDir,
                "vthumb_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.jpg"
            )
            java.io.FileOutputStream(thumbFile).use {
                thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)
            }
            val thumbW = thumb.width
            val thumbH = thumb.height
            if (thumb !== upright) thumb.recycle()

            val med = scaleDown(upright, MED_MAX_DIM)
            val medFile = java.io.File(
                outDir,
                "vmed_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.jpg"
            )
            java.io.FileOutputStream(medFile).use {
                med.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)
            }
            val medW = med.width
            val medH = med.height
            if (med !== upright) med.recycle()
            upright.recycle()

            return DerivativeResult(
                thumbPath = thumbFile.absolutePath,
                thumbWidth = thumbW,
                thumbHeight = thumbH,
                medPath = medFile.absolutePath,
                medWidth = medW,
                medHeight = medH
            )
        } catch (e: Exception) {
            android.util.Log.w("LocalExifExtractor", "Failed to generate video derivatives: ${e.message}")
            return DerivativeResult()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(source: android.graphics.Bitmap, targetMaxDim: Int): android.graphics.Bitmap {
        val maxDim = Math.max(source.width, source.height)
        if (maxDim <= targetMaxDim) return source
        val scale = targetMaxDim.toFloat() / maxDim.toFloat()
        val w = Math.max(1, Math.round(source.width * scale))
        val h = Math.max(1, Math.round(source.height * scale))
        return android.graphics.Bitmap.createScaledBitmap(source, w, h, true)
    }

    /**
     * Long edge of the JPEG thumbnail uploaded as `resJPEGThumbRes`.
     *
     * This is the frame every Apple client paints in its grid, so it is the only derivative whose
     * sharpness a user actually sees. Apple's own records carry ~311x553 (a video poster frame) up
     * to `384` (a photo) here; 320 - what we used to write - read as visibly soft beside them. 512
     * stays inside the range iCloud accepts for this field while giving a 3x-density full-width
     * tile enough pixels.
     *
     * Both photos and videos use it: a video's poster frame is the same image pipeline.
     */
    const val THUMB_MAX_DIM = 512

    /**
     * Long edge of the JPEG medium derivative uploaded as `resJPEGMedRes`.
     *
     * Matches Apple's own medium derivative, measured at 1536x2048 for a 4000x3000 capture with
     * `orientation = 6`. Do not raise this: it is also what the full-screen viewer and the system
     * picker fall back to, so every extra pixel is a multiplied transfer cost.
     */
    const val MED_MAX_DIM = 2048

    /** Maps a movie rotation hint (degrees) onto an EXIF orientation value. */
    private fun rotationToOrientation(rotation: Int): Int = when (rotation) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    /**
     * Maps an Android codec MIME to the name Apple stores in `codec`.
     *
     * Measured on real records: `"HEVC"` for a modern iPhone capture and `"H.264"` for an older
     * one. Note HEVC is NOT written as "H.265" - iCloud renders this string verbatim in the info
     * panel, so an unmatched value shows up as a wrong codec name rather than a missing one.
     */
    private fun codecName(mime: String?): String? = when (mime?.lowercase()) {
        "video/avc" -> "H.264"
        "video/hevc", "video/h265" -> "HEVC"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4"
        else -> null
    }

    /** `METADATA_KEY_DATE` is `"yyyyMMdd'T'HHmmss.SSS'Z'"`; fall back to 0 when unparseable. */
    private fun parseMediaDate(raw: String): Long {
        return try {
            val fmt = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(raw)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseExifDate(dateStr: String): Long {
        return try {
            val format = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            format.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
