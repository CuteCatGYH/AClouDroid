package app.acloudroid.qwq.provider

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.CloudMediaProvider
import android.provider.CloudMediaProviderContract
import android.util.Log
import androidx.annotation.RequiresApi
import app.acloudroid.qwq.BuildConfig
import app.acloudroid.qwq.IClouDroidApp
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageLoaderFactory
import app.acloudroid.qwq.feature.photos.data.image.ICloudImageModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * System Cloud Media Provider implementation for Android 13+ (API 33).
 *
 * Exposes synced iCloud photos and videos to the Android Photo Picker (ACTION_PICK_IMAGES).
 * Correctly serves thumbnail previews (onOpenPreview) and full-size media (onOpenMedia).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ICloudMediaProvider : CloudMediaProvider() {

    private val container by lazy {
        (context?.applicationContext as? IClouDroidApp)?.container
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun onGetMediaCollectionInfo(extras: Bundle): Bundle {
        val bundle = Bundle()

        // The picker caches rows per generation and only re-queries when this value moves. Bumping
        // it is therefore the *only* way to invalidate previously synced metadata — and it must be
        // bumped whenever the shape of what [onQueryMedia] reports changes, not just when the
        // session does. Without that, a MIME-type correction stays invisible: the picker keeps the
        // old `video/*` rows, hands them to Glide's VideoDecoder, and every affected tile stays
        // blank (measured: 2540 `setDataSource failed`).
        //
        // SCHEMA_VERSION must be raised by hand whenever `onQueryMedia`'s column values change.
        val sessionGeneration = container?.sessionManager?.getSessionGeneration()?.toLong() ?: 1L
        val generation = sessionGeneration * SCHEMA_VERSION

        bundle.putString(
            CloudMediaProviderContract.MediaCollectionInfo.MEDIA_COLLECTION_ID,
            COLLECTION_ID
        )
        bundle.putLong(
            CloudMediaProviderContract.MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION,
            generation
        )
        bundle.putString(
            CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_NAME,
            "iCloud"
        )
        return bundle
    }

    override fun onQueryMedia(extras: Bundle): Cursor {
        val columns = arrayOf(
            CloudMediaProviderContract.MediaColumns.ID,
            CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS,
            CloudMediaProviderContract.MediaColumns.SYNC_GENERATION,
            CloudMediaProviderContract.MediaColumns.MIME_TYPE,
            CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
            CloudMediaProviderContract.MediaColumns.SIZE_BYTES,
            CloudMediaProviderContract.MediaColumns.DURATION_MILLIS,
            CloudMediaProviderContract.MediaColumns.WIDTH,
            CloudMediaProviderContract.MediaColumns.HEIGHT,
            CloudMediaProviderContract.MediaColumns.ORIENTATION,
            // Required for the picker to know the row is openable. Omitting it made the tile
            // render but never issue `onOpenPreview`, i.e. a permanently blank grid cell.
            CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI
        )

        val cursor = MatrixCursor(columns)
        val generation = container?.sessionManager?.getSessionGeneration()?.toLong() ?: 1L

        // Pagination is mandatory: the picker asks for `EXTRA_PAGE_SIZE` rows at a time and stops
        // when a page comes back without `EXTRA_PAGE_TOKEN`. Returning the whole library in one
        // cursor made the sync worker give up part-way, which is why only the first slice of the
        // grid ever had usable rows.
        //
        // The token is a **keyset** cursor (`"<assetDate>:<id>"`), not an offset. `photo_assets` is
        // written to (upsertAll / deleteByIds) by incremental sync and the month scanner while the
        // picker is open, and an OFFSET cursor shifts by one row for every such change — which
        // silently skips one asset and duplicates another. The drift accumulates page by page, so
        // it surfaces as blank tiles only in the earliest years, i.e. the last pages. See
        // PhotoDao.getMediaPageFirst.
        val pageSize = extras.getInt(
            CloudMediaProviderContract.EXTRA_PAGE_SIZE,
            DEFAULT_PAGE_SIZE
        ).coerceIn(1, MAX_PAGE_SIZE)
        val rawToken = extras.getString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN)
        val keyset = parseKeysetToken(rawToken)
        // A bare numeric token can only have come from the previous OFFSET-based release (the
        // picker persists page tokens across provider restarts). Serve exactly one page from it.
        val legacyOffset = if (keyset == null) rawToken?.toIntOrNull() else null

        val cursorBundle = Bundle().apply {
            putString(CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID, COLLECTION_ID)
        }
        cursor.extras = cursorBundle

        val db = container?.photoDatabase ?: return cursor

        runBlocking {
            try {
                val dao = db.photoDao()
                val page = when {
                    keyset != null -> dao.getMediaPageAfter(pageSize, keyset.first, keyset.second)
                    legacyOffset != null -> dao.getMediaPage(pageSize, legacyOffset)
                    else -> dao.getMediaPageFirst(pageSize)
                }
                for (asset in page) {
                    val mimeType = resolveMimeType(asset)
                    cursor.addRow(
                        arrayOf<Any?>(
                            asset.id,
                            asset.assetDate,
                            // Must match LAST_MEDIA_SYNC_GENERATION from onGetMediaCollectionInfo,
                            // otherwise the picker treats every row as stale and never syncs it.
                            generation,
                            mimeType,
                            0, // standard extension
                            asset.size,
                            asset.duration,
                            if (asset.width > 0) asset.width else 1080,
                            if (asset.height > 0) asset.height else 1920,
                            0,
                            // Cloud-only item: MEDIA_STORE_URI must stay null (AOSP contract).
                            null
                        )
                    )
                }

                // Cursor for the next page: the sort key of this page's last row. Omitted on a
                // short page so the picker stops instead of asking again for an empty one.
                val last = page.lastOrNull()
                if (page.size == pageSize && last != null) {
                    cursorBundle.putString(
                        CloudMediaProviderContract.EXTRA_PAGE_TOKEN,
                        encodeKeysetToken(last.assetDate, last.id)
                    )
                }

                // Diagnostic: surface the MIME mix we actually hand over. If a video ever appears
                // here with a `video` MIME the picker will route its tile through Glide's
                // VideoDecoder and the tile stays blank, which is exactly what this reports.
                run {
                    val videos = page.count { it.mediaType.equals("VIDEO", ignoreCase = true) }
                    Log.d(
                        TAG,
                        "onQueryMedia token=${rawToken ?: "first"} size=${page.size} gen=$generation " +
                            "videos=$videos sampleMime=${page.firstOrNull()?.let { resolveMimeType(it) }}"
                    )
                }

                // Warm the URLs for exactly the rows this page exposed, rather than once per
                // process. Deep scrolls into early years take minutes, so a one-shot warm-up at
                // offset 0 leaves those older pages minting a `/records/lookup` per tile — which
                // is precisely the latency window that makes `onOpenPreview` give up.
                scheduleUrlWarmUp(page.map { it.id } to page.map { it.masterId })
            } catch (e: Exception) {
                Log.e(TAG, "onQueryMedia error", e)
            }
        }

        return cursor
    }

    /**
     * Re-mints URLs for the page the picker just asked for, off the binder thread.
     *
     * ## Why per page and not once per process
     *
     * This used to be a one-shot per-process warm-up (`urlWarmUpStarted`), which was right when
     * the picker only ever read the first screen. A deep scroll back into early years takes
     * minutes and walks hundreds of
     * `onQueryMedia` pages, and a CDN signature lives only ~15–18 min — so by the time the user
     * reaches those pages the rows there are stale again. Each tile then pays a `/records/lookup`
     * re-mint inside its own `onOpenPreview`, which is precisely the 300 ms that pushes the
     * transfer past the wait budget and leaves a blank tile.
     *
     * Targeting only the ids in *this* page keeps the cost at one batched lookup per page instead
     * of one per tile, and never scans the whole library.
     *
     * @param idsAndMasters asset ids paired with the masters the page actually exposed.
     */
    private fun scheduleUrlWarmUp(idsAndMasters: Pair<List<String>, List<String>>) {
        val appContainer = container ?: return
        val (ids, masterIds) = idsAndMasters
        if (ids.isEmpty()) return
        previewDownloadPool.submit {
            try {
                runBlocking {
                    // Only the masters behind this page whose stored URL (on any of their assets)
                    // has aged out. `refreshUrlsForMasters` already dedupes, but filtering here
                    // keeps an in-window page from emitting a pointless lookup.
                    val staleIds = appContainer.photoDatabase.photoDao().getStaleUrlAssetIds(
                        System.currentTimeMillis() - PREVIEW_URL_MAX_AGE_MS,
                        ids.size
                    )
                    if (staleIds.isEmpty()) {
                        Log.d(TAG, "url warm-up: page of ${ids.size} already fresh")
                        return@runBlocking
                    }
                    // Keep only masters that belong to *this* page.
                    val staleMasters = staleIds.map { it.masterId }.toSet()
                    val pageMasters = ids.zip(masterIds)
                        .map { it.second }
                        .filter { it in staleMasters }
                        .distinct()
                    if (pageMasters.isEmpty()) {
                        Log.d(TAG, "url warm-up: page of ${ids.size} already fresh")
                        return@runBlocking
                    }
                    Log.d(TAG, "url warm-up: refreshing ${pageMasters.size} masters for this page")
                    appContainer.photoRepository.refreshUrlsForMasters(
                        pageMasters.take(WARM_UP_BATCH)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "url warm-up failed", e)
            }
        }
    }

    /**
     * Encodes a pagination cursor as `"<assetDate>:<id>"`.
     *
     * Chosen over JSON because it is cheap to parse on the hot sync path and cannot be confused
     * with the legacy bare-integer OFFSET token. `assetDate` is a `Long` and CloudKit record names
     * are `[A-Za-z0-9+/=_-]`, so `:` is an unambiguous separator.
     */
    private fun encodeKeysetToken(assetDate: Long, id: String): String =
        "$assetDate:$id"

    /**
     * Parses a keyset cursor, or null when [token] is absent or is a legacy integer offset.
     *
     * A malformed token is treated as "no token" (serve the first page) rather than throwing: the
     * picker treats an exception here as "provider broken" and drops cloud media entirely, which
     * is a much worse failure than restarting pagination from the top.
     */
    private fun parseKeysetToken(token: String?): Pair<Long, String>? {
        if (token.isNullOrBlank()) return null
        val sep = token.indexOf(':')
        if (sep <= 0 || sep == token.length - 1) {
            // No separator → either a legacy integer offset or garbage; both fall back to the
            // first page (the caller additionally probes for a legacy offset).
            return null
        }
        val date = token.substring(0, sep).toLongOrNull() ?: return null
        val id = token.substring(sep + 1)
        return date to id
    }

    override fun onQueryDeletedMedia(extras: Bundle): Cursor {
        val columns = arrayOf(CloudMediaProviderContract.MediaColumns.ID)
        val cursor = MatrixCursor(columns)
        val bundle = Bundle().apply {
            putString(CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID, COLLECTION_ID)
        }
        cursor.extras = bundle
        return cursor
    }

    /**
     * Returns the preview bytes for [mediaId].
     *
     * ## Video rows must return video bytes, not a JPEG
     *
     * The contract says the preview “should be in the original `MIME_TYPE`”. For an image that is a
     * downscaled still. For a video it means the picker's `VideoDecoder` must be able to produce a
     * frame from what we hand over — and `VideoDecoder` wraps `MediaMetadataRetriever`, which needs
     * a real container. Handing it the server-transcoded JPEG failed every time with
     * `setDataSource failed: status = 0xFFFFFFEA` (measured 2540 times).
     *
     * So video rows are served the actual media file (the same bytes `onOpenMedia` delivers), and
     * [resolveMimeType] keeps reporting a video type. That keeps the item classified as a video —
     * duration badge, video filters, `MediaItem` type — while giving the decoder something it can
     * read. Only if the download is not available in time do we fall back to the JPEG thumbnail,
     * which at least paints something instead of an empty square.
     */
    override fun onOpenPreview(
        mediaId: String,
        size: Point,
        extras: Bundle?,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val startedAt = System.currentTimeMillis()
        val thumbRequested = extras?.getBoolean(
            CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL, false
        ) ?: false

        logPreviewExtras(mediaId, extras, size)

        val isVideo = isVideoAsset(mediaId)

        // Video: prefer the real container so `VideoDecoder` can extract a frame.
        var previewFile: File? = null
        if (isVideo) {
            previewFile = try {
                getOrDownloadMediaFile(mediaId, signal)
            } catch (e: Exception) {
                Log.w(TAG, "openPreview video fetch failed media=$mediaId", e)
                null
            }
            if (previewFile == null) {
                Log.d(
                    TAG,
                    "openPreview video falling back to still media=$mediaId " +
                        "elapsed=${System.currentTimeMillis() - startedAt}ms"
                )
            }
        }

        // Image rows, and any video whose full file was not available in time.
        if (previewFile == null) {
            previewFile = try {
                getOrProvidePreviewFile(mediaId, signal)
            } catch (e: Exception) {
                Log.w(TAG, "openPreview EXCEPTION media=$mediaId", e)
                null
            }
        }

        if (previewFile == null) {
            Log.w(
                TAG,
                "openPreview MISS media=$mediaId size=${size.x}x${size.y} video=$isVideo " +
                    "thumbExtra=$thumbRequested canceled=${signal?.isCanceled} " +
                    "elapsed=${System.currentTimeMillis() - startedAt}ms"
            )
            throw FileNotFoundException("Preview not found for $mediaId")
        }

        val length = previewFile.length()
        Log.d(
            TAG,
            "openPreview OK media=$mediaId size=${size.x}x${size.y} bytes=$length video=$isVideo " +
                "file=${previewFile.name} elapsed=${System.currentTimeMillis() - startedAt}ms"
        )
        val pfd = ParcelFileDescriptor.open(previewFile, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, length)
    }

    override fun onOpenMedia(
        mediaId: String,
        extras: Bundle?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val mediaFile = getOrDownloadMediaFile(mediaId, signal)
            ?: throw FileNotFoundException("Media not found for $mediaId")

        return ParcelFileDescriptor.open(mediaFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * Returns a locally readable thumbnail for [mediaId], or null when it cannot be produced fast
     * enough for this binder call.
     *
     * ## Measured behaviour that shaped this method
     *
     * - The picker asks for a preview **once** per scroll position. It does retry, but only after
     *   the row has been off-screen, so a single miss is a permanently blank tile for that pass.
     * - `Glide` cancels its fetch after roughly 250–400 ms, and the process-wide write of the
     *   downloaded bytes can land *after* the miss was reported. So "kick off a download in the
     *   background and let it retry" does not work: the retry may not come.
     * - A cold thumbnail costs a `/records/lookup` re-mint (~250–400 ms) plus a CDN round trip
     *   (~200–400 ms), which overshoots that window on its own.
     *
     * ## Strategy
     *
     * 1. Coil disk cache (the app's own grid) — pure local read, sub-millisecond.
     * 2. Previously downloaded preview — pure local read.
     * 3. A download that is already running — wait for it, bounded by [PREVIEW_WAIT_MS].
     * 4. Otherwise start one and wait, again bounded. Waiting beats returning early because the
     *    retry may never arrive; the bound keeps a pathological case from wedging the binder.
     *
     * URLs are pre-warmed for the whole page by [scheduleUrlWarmUp], so by the time the picker
     * asks for a tile the re-mint has usually already happened and only the CDN transfer remains.
     *
     * ## Why every hit is validated with [isPlausiblePreview]
     *
     * A cached file is only trusted if it actually *is* an image. `cvws` can answer a dead
     * signature with a `200` carrying a short error document, and that lands on disk as a
     * non-empty file — which the old `length() > 0` check accepted forever. One such file turned
     * into a permanently blank tile, because every subsequent request short-circuited on the
     * stale bytes instead of downloading again. Bad bytes are now deleted and re-fetched once.
     */
    private fun getOrProvidePreviewFile(mediaId: String, signal: CancellationSignal?): File? {
        val previewDir = File(context?.cacheDir, "cmp_previews").apply { mkdirs() }
        val targetFile = File(previewDir, "$mediaId.thumb")

        // 1. Fast path: the app already cached this exact thumbnail via Coil.
        if (copyFromCoilDiskCache(mediaId, targetFile) && isPlausiblePreview(targetFile)) {
            Log.d(TAG, "preview from COIL media=$mediaId bytes=${targetFile.length()}")
            return targetFile
        }

        // 2. Already downloaded for a previous picker pass.
        if (targetFile.exists() && targetFile.length() > 0) {
            if (isPlausiblePreview(targetFile)) {
                Log.d(TAG, "preview from LOCAL media=$mediaId bytes=${targetFile.length()}")
                return targetFile
            }
            // Not an image: a truncated transfer or a CDN error body saved as `200`. Delete it
            // so the download path below re-fetches, instead of serving it forever.
            Log.w(
                TAG,
                "preview cached file is not an image media=$mediaId " +
                    "bytes=${targetFile.length()} head=${headHex(targetFile)} — discarding"
            )
            targetFile.delete()
        }

        val appContainer = container ?: return null

        // 3. Join an in-flight download for this asset instead of starting a second one.
        val existing = inFlightDownloads[mediaId]
        if (existing != null) {
            return awaitPreview(existing, targetFile, mediaId)
        }

        // 4. Start the download and wait for it, bounded.
        val task = previewDownloadPool.submit {
            try {
                val lock = previewLocks.getOrPut(mediaId) { ReentrantLock() }
                lock.withLock {
                    if (targetFile.exists() && isPlausiblePreview(targetFile)) return@withLock
                    targetFile.delete()
                    val thumbUrl = resolveThumbUrl(appContainer, mediaId) ?: return@withLock
                    Log.d(
                        TAG,
                        "preview DOWNLOAD media=$mediaId host=${hostOf(thumbUrl)}"
                    )
                    if (!downloadToFile(thumbUrl, targetFile, requireImage = true)) {
                        Log.w(TAG, "preview DOWNLOAD produced no usable image media=$mediaId")
                    }
                }
            } finally {
                inFlightDownloads.remove(mediaId)
            }
        }
        inFlightDownloads[mediaId] = task
        return awaitPreview(task, targetFile, mediaId)
    }

    /**
     * Waits for [task], then reports whatever landed on disk.
     *
     * ## Two-stage wait
     *
     * Measured on device: the picker asks for a grid thumbnail **exactly once**. If this method
     * gives up, the tile stays blank for the rest of the pass. A single tile whose transfer needed
     * 1.21 s (just over the old 1.2 s cap) was therefore permanently blank, while the very same
     * asset opened fine from the preview button 3 s later — the bytes were already on disk.
     *
     * So the fast tier ([PREVIEW_WAIT_MS]) keeps the common case snappy, and the slow tier
     * ([PREVIEW_SLOW_WAIT_MS]) only runs for stragglers. The extra wait is invisible to the user:
     * the alternative for that tile is a blank square, not a faster one.
     */
    private fun awaitPreview(task: Future<*>, targetFile: File, mediaId: String): File? {
        return try {
            task.get(PREVIEW_WAIT_MS, TimeUnit.MILLISECONDS)
            readIfPresent(targetFile, mediaId)
        } catch (e: TimeoutException) {
            // Slow tier: let the straggler finish rather than handing back a permanently blank tile.
            try {
                task.get(PREVIEW_SLOW_WAIT_MS - PREVIEW_WAIT_MS, TimeUnit.MILLISECONDS)
                readIfPresent(targetFile, mediaId)
            } catch (e2: TimeoutException) {
                // Still running. The file may have been renamed a frame ago, so check once more:
                // the download thread renames `.tmp` -> target as its very last action.
                Log.d(TAG, "preview AWAIT slow-timeout media=$mediaId")
                readIfPresent(targetFile, mediaId)
            } catch (e2: Exception) {
                Log.w(TAG, "preview AWAIT slow-tier failed media=$mediaId", e2)
                readIfPresent(targetFile, mediaId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "preview AWAIT failed media=$mediaId", e)
            readIfPresent(targetFile, mediaId)
        }
    }

    /**
     * Returns [targetFile] when it holds a plausible image, otherwise logs and returns null.
     *
     * Gated on [isPlausiblePreview] rather than `length() > 0`: a cached non-image would
     * otherwise be handed to Glide for the rest of the session.
     */
    private fun readIfPresent(targetFile: File, mediaId: String): File? {
        if (targetFile.exists() && isPlausiblePreview(targetFile)) return targetFile
        if (targetFile.exists()) {
            Log.w(
                TAG,
                "preview AWAIT miss media=$mediaId (cached bytes are not an image, " +
                    "${targetFile.length()}B head=${headHex(targetFile)})"
            )
            targetFile.delete()
        } else {
            Log.w(TAG, "preview AWAIT miss media=$mediaId (no file on disk)")
        }
        return null
    }

    /**
     * Cheap "is this actually the image we claim it is" check for a cached preview.
     *
     * Only the container magic is inspected — no decode, no dimensions. That is deliberate: this
     * runs on the binder path, and a full `BitmapFactory` decode of every cold tile would cost more
     * than the download it guards. A magic-byte check already catches the failure modes that
     * actually occur here:
     *
     *  - a truncated transfer (too short to hold any header at all),
     *  - a CDN error document (`<html>`, an XML error, a JSON body) saved under a `200`.
     *
     * Recognised containers are exactly what [resolveMimeType] promises the picker: JPEG, PNG,
     * WebP and GIF. HEIC is accepted too because `Original`-quality bytes may legitimately be
     * HEIC even though thumbnails are transcoded to JPEG.
     */
    private fun isPlausiblePreview(file: File): Boolean {
        if (!file.exists()) return false
        if (file.length() < MIN_PREVIEW_BYTES) return false
        val head = try {
            file.inputStream().use { input ->
                val buf = ByteArray(12)
                val read = input.read(buf)
                if (read < 12) return false
                buf
            }
        } catch (e: Exception) {
            Log.w(TAG, "isPlausiblePreview read failed ${file.name}", e)
            return false
        }
        return isKnownImageMagic(head)
    }

    /** Hex dump of the first bytes of [file], for diagnosing rejected cache entries. */
    private fun headHex(file: File): String {
        return try {
            file.inputStream().use { input ->
                val buf = ByteArray(8)
                val read = input.read(buf)
                if (read <= 0) return "empty"
                buf.copyOf(read).joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            "unreadable"
        }
    }

    /**
     * Resolves a download URL for [mediaId], re-minting it when the stored one has aged out.
     *
     * ## Why videos must never fall back past `thumbUrl`
     *
     * `onOpenPreview` must return an **image**, and [resolveMimeType] tells the picker every tile is
     * `image/jpeg`. For a photo the fallback chain `thumbUrl ?: medUrl ?: originalUrl` is safe: all
     * three are images of the same subject at different sizes. For a **video** only `thumbUrl` is
     * an image — `medUrl` and `originalUrl` are the `mp4`/`mov` bytes themselves.
     *
     * Falling back there hands Glide a video container labelled `image/jpeg`, and that tile stays
     * blank. Measured on device, `thumbUrl` is occasionally absent from a `/records/lookup`
     * response, and the damage is concentrated in early years because that is where the video
     * density is (48–336 videos per 1000 assets). Returning null instead makes it a logged miss
     * that a later request can recover, rather than a plausible-looking file that can never decode.
     */
    private fun resolveThumbUrl(appContainer: app.acloudroid.qwq.di.AppContainer, mediaId: String): String? {
        return runBlocking {
            withContext(Dispatchers.IO) {
                val asset = appContainer.photoDatabase.photoDao().getPhotoById(mediaId)
                if (asset == null) {
                    Log.w(TAG, "resolveThumbUrl: no DB row media=$mediaId")
                    return@withContext null
                }
                val isVideo = asset.mediaType.equals("VIDEO", ignoreCase = true) ||
                    asset.mediaType.equals("LIVE_PHOTO", ignoreCase = true)

                fun pick(u: String?, v: String?, o: String?): String? =
                    if (isVideo) u else (u ?: v ?: o)

                var thumbUrl = pick(asset.thumbUrl, asset.medUrl, asset.originalUrl)
                val ageMs = System.currentTimeMillis() - asset.urlFetchedAt

                // A missing thumbUrl is a *hard* failure for a video, so force a re-mint rather
                // than accepting the dedup window's cached answer.
                val needRefresh = thumbUrl.isNullOrBlank() || ageMs > PREVIEW_URL_MAX_AGE_MS
                if (needRefresh) {
                    val refreshRes = appContainer.photoRepository.refreshUrlsForMaster(
                        asset.masterId,
                        force = isVideo && thumbUrl.isNullOrBlank()
                    )
                    if (refreshRes.isSuccess) {
                        val fresh = refreshRes.getOrNull()
                        thumbUrl = pick(fresh?.thumbUrl, fresh?.medUrl, fresh?.originalUrl)
                    } else {
                        Log.w(
                            TAG,
                            "resolveThumbUrl refresh failed media=$mediaId: " +
                                "${refreshRes.exceptionOrNull()?.message}"
                        )
                    }
                }

                if (thumbUrl.isNullOrBlank() && isVideo) {
                    // Deliberately loud: this is the one case where the picker gets a blank tile
                    // while `onOpenMedia` would still work, so the log is the only way to tell it
                    // apart from a genuine decode failure downstream.
                    Log.w(
                        TAG,
                        "resolveThumbUrl: video has no thumbUrl (medUrl/originalUrl are video " +
                            "bytes, refusing to serve them as an image) media=$mediaId"
                    )
                }
                thumbUrl?.takeIf { it.isNotBlank() }
            }
        }
    }

    /**
     * Dumps the extras the picker sent with `onOpenPreview`.
     *
     * Kept as the first item on ISS-035's follow-up list: `EXTRA_PREVIEW_THUMBNAIL` tells us
     * whether the picker is asking for a grid thumbnail or a full preview, and the value of
     * `EXTRA_PREVIEW_MEDIA_SIZE_LIMIT` bounds what it expects back. Both are invisible today,
     * which makes "why is this one tile different" impossible to answer from the log.
     *
     * DEBUG-only, and values are truncated: extras can carry an unbounded caller-supplied blob.
     */
    private fun logPreviewExtras(mediaId: String, extras: Bundle?, size: Point) {
        if (!BuildConfig.DEBUG) return
        val dump = extras?.keySet()?.joinToString(", ") { key ->
            val value = try {
                @Suppress("DEPRECATION")
                val raw = extras.get(key)
                raw?.toString()?.take(48)
            } catch (e: Exception) {
                "<unreadable>"
            }
            "$key=$value"
        } ?: "none"
        Log.d(
            TAG,
            "openPreview extras media=$mediaId size=${size.x}x${size.y} {${dump}}"
        )
    }

    /** Host only — never the signed query string (AGENTS §8.2). */
    private fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return "none"
        return try {
            URL(url).host
        } catch (e: Exception) {
            "invalid"
        }
    }

    /**
     * Copies the app's Coil disk-cache entry for this thumbnail into [targetFile].
     *
     * The key must match `ICloudPhotoKeyer` exactly, otherwise this silently misses. The snapshot
     * is copied out before being closed: Coil keeps a lock on an entry while a snapshot is open,
     * and handing the picker the snapshot's own path would let an eviction delete the file mid-read.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private fun copyFromCoilDiskCache(mediaId: String, targetFile: File): Boolean {
        return try {
            val appContainer = container ?: return false
            val ctx = context ?: return false
            val loader = ICloudImageLoaderFactory.get(ctx, appContainer)
            val snapshot = loader.diskCache?.openSnapshot(COIL_THUMB_KEY.format(mediaId))
                ?: return false
            val tempFile = File(targetFile.parentFile, "${targetFile.name}.copy")
            try {
                snapshot.data.toFile().copyTo(tempFile, overwrite = true)
            } finally {
                snapshot.close()
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                tempFile.renameTo(targetFile)
                true
            } else {
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Coil disk cache lookup failed for $mediaId", e)
            false
        }
    }

    private fun getOrDownloadMediaFile(mediaId: String, signal: CancellationSignal?): File? = runBlocking {
        withContext(Dispatchers.IO) {
            val mediaDir = File(context?.cacheDir, "cmp_media").apply { mkdirs() }
            val targetFile = File(mediaDir, "$mediaId.full")
            if (targetFile.exists() && targetFile.length() > 0) {
                return@withContext targetFile
            }

            val appContainer = container ?: return@withContext null
            val db = appContainer.photoDatabase
            val asset = db.photoDao().getPhotoById(mediaId) ?: return@withContext null

            var mediaUrl = asset.originalUrl ?: asset.medUrl ?: asset.thumbUrl
            val ageMs = System.currentTimeMillis() - asset.urlFetchedAt
            if (mediaUrl.isNullOrBlank() || ageMs > 12 * 60 * 1000L) {
                val refreshRes = appContainer.photoRepository.refreshUrlsForMaster(asset.masterId)
                if (refreshRes.isSuccess) {
                    val fresh = refreshRes.getOrNull()
                    mediaUrl = fresh?.originalUrl ?: fresh?.medUrl ?: fresh?.thumbUrl
                }
            }

            if (mediaUrl.isNullOrBlank()) return@withContext null

            // The full-size transfer is synchronous on purpose: unlike previews, the picker keeps
            // the binder call open until the bytes are handed to the requesting app, so there is
            // nothing to gain by returning early.
            if (!downloadToFile(mediaUrl, targetFile)) {
                Log.w(TAG, "openMedia download failed media=$mediaId")
                null
            } else {
                targetFile
            }
        }
    }

    /**
     * Streams [urlStr] into [destination] atomically via a sibling `.tmp` file.
     *
     * ## Why the cancellation signal is deliberately IGNORED here
     *
     * Measured behaviour on device: the picker recycles rows aggressively and fires
     * `CancellationSignal` after a few hundred milliseconds, while a cold thumbnail needs a
     * `/records/lookup` re-mint (~300ms) plus a CDN round trip (~300ms). Honouring the signal meant
     * deleting the partial download and returning null — 52 of 445 previews came back blank, and
     * the very next request for the same asset (now with a fresh URL) succeeded in 226ms.
     *
     * Abandoned work only wastes bandwidth, and mirroring it into the cache is exactly what makes
     * the second attempt instant. So a cancelled request simply keeps going to completion in the
     * background; the caller returns whatever is already on disk.
     *
     * ## Why the payload is validated before being promoted
     *
     * A `200` does not guarantee image bytes: a CDN can answer a dead signature with a short HTML
     * or XML error document under success status. Promoting that to [destination] would create a
     * non-empty "thumbnail" that `length() > 0` accepts forever. Checking the magic on the temp
     * file costs twelve bytes of IO and keeps such a response out of the cache entirely.
     *
     * @param requireImage when true (previews), non-image payloads are treated as a failure. Full
     *   size media passes false, because those bytes may legitimately be `mp4`/`mov`.
     * @return true when [destination] holds a complete file after this call.
     */
    private fun downloadToFile(
        urlStr: String,
        destination: File,
        requireImage: Boolean = false
    ): Boolean {
        val tempFile = File(destination.parentFile, "${destination.name}.tmp")
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "download HTTP $code host=${hostOf(urlStr)} dest=${destination.name}")
                return false
            }

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }

            if (requireImage && !isPlausiblePreview(tempFile)) {
                Log.w(
                    TAG,
                    "download returned non-image host=${hostOf(urlStr)} " +
                        "dest=${destination.name} bytes=${tempFile.length()} " +
                        "head=${headHex(tempFile)}"
                )
                tempFile.delete()
                return false
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                tempFile.renameTo(destination)
            }
            destination.exists() && destination.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "download failed host=${hostOf(urlStr)}", e)
            tempFile.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * MIME type reported to the picker.
     *
     * ## The constraint this has to satisfy
     *
     * Per the CloudMediaProvider contract, a row's `MIME_TYPE` does double duty: the picker uses it
     * to (a) classify the item as photo vs video and (b) pick the decoder for the tile. For a photo
     * both point the same way, for a video they pull apart:
     *
     *  - reporting `video/mp4` classifies the item correctly, but sends the tile through Glide's
     *    `VideoDecoder`, which parses the preview bytes with `MediaMetadataRetriever`. Handing it the
     *    server-transcoded JPEG meant `setDataSource failed: status = 0xFFFFFFEA` (measured 2540
     *    times) and a blank tile.
     *  - reporting `image/jpeg` makes the tile decode, but the item then presents as a **photo**: no
     *    duration badge, no video classification, and callers receive it as an image.
     *
     * Neither is acceptable, so the split is made where the contract puts it. `MIME_TYPE` describes
     * what the media actually *is* (`video/mp4`), and [onOpenPreview] is made to honour the other
     * half of the contract — “the thumbnail should be in the original MIME_TYPE” — by returning an
     * actual (trimmed) video file for video rows instead of a JPEG. `VideoDecoder` then has real
     * video bytes to grab a frame from and succeeds.
     *
     * ## `.heic`
     *
     * A `.heic` asset's `thumbUrl` is a server-transcoded JPEG, so reporting `image/heic` would send
     * `BitmapDecoder` looking for a HEIF header in JPEG bytes — the same class of mistake. `.heic` is
     * therefore reported as `image/jpeg`. Unlike video this costs nothing, because the item is a
     * photo either way and the original HEIC reaches the caller through `onOpenMedia`.
     */
    private fun resolveMimeType(asset: PhotoAssetEntity): String =
        mimeTypeFor(asset.filename, asset.mediaType)

    /** True when the picker will route this row's tile through Glide's `VideoDecoder`. */
    private fun isVideoAsset(asset: PhotoAssetEntity): Boolean =
        asset.mediaType.equals("VIDEO", ignoreCase = true)

    /**
     * [isVideoAsset] for a raw `mediaId`, as `onOpenPreview` receives it.
     *
     * One indexed lookup on the primary key. Cheap next to the download this decision gates, and
     * the alternative — threading the asset through — would not survive the binder boundary.
     */
    private fun isVideoAsset(mediaId: String): Boolean = try {
        val appContainer = container ?: return false
        val asset = runBlocking {
            withContext(Dispatchers.IO) {
                appContainer.photoDatabase.photoDao().getPhotoById(mediaId)
            }
        }
        asset != null && isVideoAsset(asset)
    } catch (e: Exception) {
        Log.w(TAG, "isVideoAsset lookup failed media=$mediaId", e)
        false
    }

    /**
     * True when [head] starts with a container magic we are willing to hand to the picker.
     *
     * Kept as a pure function over twelve bytes so it is trivially unit-testable off-device.
     * Accepts JPEG, PNG, WebP, GIF and the ISO-BMFF (`ftyp`) boxes that carry HEIC/HEIF, which is
     * the set [resolveMimeType] promises plus the HEIC case that can still reach us via
     * `Original`-quality bytes.
     */
    private fun isKnownImageMagic(head: ByteArray): Boolean {
        fun u(i: Int) = head[i].toInt() and 0xFF

        // JPEG: FF D8 FF
        if (u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF) return true
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47) return true
        // GIF: "GIF8"
        if (u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x38) return true
        // WebP: "RIFF" .... "WEBP"
        if (u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46 &&
            u(8) == 0x57 && u(9) == 0x45 && u(10) == 0x42 && u(11) == 0x50
        ) return true
        // HEIC/HEIF: size(4) + "ftyp"
        if (u(4) == 0x66 && u(5) == 0x74 && u(6) == 0x79 && u(7) == 0x70) return true
        return false
    }

    companion object {
        private const val TAG = "ICloudMediaProvider"
        private const val COLLECTION_ID = "icloud_photos_collection"

        /**
         * Bump whenever the values `onQueryMedia` puts in its columns change.
         *
         * The picker stores synced rows keyed by `LAST_MEDIA_SYNC_GENERATION` and skips a re-query
         * when it has not moved. Multiplying it by this constant makes any metadata fix propagate
         * to already-installed devices instead of only to fresh installs.
         *
         * History:
         *  1 — videos reported with a video MIME type, which sent thumbnails to Glide VideoDecoder
         *  2 — videos reported as `image/jpeg` so the still thumbnail decodes as an image, at the
         *      cost of the item presenting as a photo
         *  3 — `.heic` reported as `image/jpeg`; page cursor changed from OFFSET to a keyset token
         *  4 — BACK to a real video MIME, with `onOpenPreview` now serving the video container so
         *      `VideoDecoder` can extract a frame. Fixes the type regression from 2 while keeping
         *      the tile decodable.
         */
        private const val SCHEMA_VERSION = 4L

        /**
         * Smallest file we will accept as a cached thumbnail.
         *
         * A real 100–200 px JPEG thumbnail is several kilobytes, so this only has to be large
         * enough to reject a truncated header and a short CDN error body — deliberately far below
         * any legitimate image so a valid but unusually small thumbnail is never discarded.
         */
        private const val MIN_PREVIEW_BYTES = 512L

        /**
         * Shared with `ICloudPhotoInterceptor.URL_MAX_AGE_MS` in spirit: a CDN signature lives
         * ~15–18 min, so re-mint anything older than 12 min before handing it to the picker.
         */
        private const val PREVIEW_URL_MAX_AGE_MS = 12 * 60 * 1000L

        /**
         * How long `onOpenPreview` may block on a download.
         *
         * Glide gives up somewhere around 250–400 ms, but it does **not** reliably retry, so
         * returning early is worse than blocking: the tile stays blank for the whole pass. Waiting
         * up to 1.2 s covers a warm CDN transfer (200–400 ms) and most cold ones; anything slower
         * gives up and lets the background thread finish for the next pass.
         */
        private const val PREVIEW_WAIT_MS = 1200L

        /**
         * Total budget for a straggling thumbnail (including [PREVIEW_WAIT_MS]).
         *
         * The picker requests each grid tile once, so giving up early means a permanently blank
         * square, not a retried one. Measured worst case on device was ~1.2 s for a cold tile on a
         * slow CDN node, so this leaves headroom without letting a hung connection block the binder
         * for an unpleasant amount of time.
         */
        private const val PREVIEW_SLOW_WAIT_MS = 4000L

        /**
         * How many in-flight transfers a page of previews may run at once.
         *
         * The picker asks for a full screen at a time; running all of them in parallel starves the
         * CDN connection pool and the process' own image pipeline. Six keeps the pipeline fed.
         */
        private const val PREVIEW_POOL_SIZE = 6

        /**
         * Must stay byte-identical to `ICloudPhotoKeyer.key()`:
         * `"icloud_${recordName}_${quality.lowercase()}"`.
         */
        private const val COIL_THUMB_KEY = "icloud_%s_thumb"

        /**
         * Per-media download locks.
         *
         * The picker issues the same preview dozens of times while scrolling (Glide retries on
         * recycle), and without coalescing each one would start its own CDN download of the same
         * file. Entries are never removed: the set is bounded by the number of photos the user
         * actually scrolled past, and each value is a few dozen bytes.
         */
        private val previewLocks = ConcurrentHashMap<String, ReentrantLock>()

        /**
         * Bounded pool that owns the actual transfers.
         *
         * Deliberately small: the picker asks for a full screen of tiles at once, and letting ~50
         * downloads run in parallel starves the binder threads and the CDN connection pool. Six
         * matches the "number of thumbnails a fast fling can legitimately consume" window.
         */
        private val previewDownloadPool: ExecutorService =
            Executors.newFixedThreadPool(PREVIEW_POOL_SIZE)

        /** Downloads queued but not yet finished, so the same asset is never fetched twice. */
        private val inFlightDownloads = ConcurrentHashMap<String, Future<*>>()

        /**
         * How many masters [scheduleUrlWarmUp] re-mints in one batch.
         *
         * Each batch costs one `/records/lookup`, and this runs once per exposed page, so the
         * bound is what keeps a deep scroll from turning into a lookup storm. It is set to the
         * default page size — a page is exactly the set of tiles the picker is about to ask for,
         * so there is nothing to gain from warming more than one page ahead.
         */
        private const val WARM_UP_BATCH = 120


        /**
         * Fallback page size when the picker does not send `EXTRA_PAGE_SIZE`.
         *
         * Matches AOSP's own default; the picker normally sends one explicitly.
         */
        private const val DEFAULT_PAGE_SIZE = 1000

        /** Upper bound so a malformed extras bundle cannot ask for the entire library at once. */
        private const val MAX_PAGE_SIZE = 2000

        /**
         * MIME type for a picker row: what the media **is**, not what [onOpenPreview] returns.
         *
         * Pure over `(filename, mediaType)` so it can be unit-tested off-device — see
         * `MediaMimeTypeTest`. The reasoning lives on [ICloudMediaProvider.resolveMimeType]; this
         * only encodes the table.
         *
         * Videos keep a video MIME because the picker derives the item's very *type* from this
         * column: reporting `image/jpeg` for a video made it show up as a photo (no duration
         * badge, no video classification). `onOpenPreview` compensates by serving a real video
         * container for those rows, so `VideoDecoder` has bytes it can read.
         *
         * `.heic` is reported as `image/jpeg` because its `thumbUrl` is a server-transcoded JPEG.
         * That costs nothing: the item is a photo either way, and the original HEIC reaches the
         * caller through `onOpenMedia`.
         */
        internal fun mimeTypeFor(filename: String, mediaType: String): String {
            val name = filename.lowercase()
            val isVideo = mediaType.equals("VIDEO", ignoreCase = true)
            return when {
                isVideo -> when {
                    name.endsWith(".mov") -> "video/quicktime"
                    name.endsWith(".m4v") -> "video/x-m4v"
                    else -> "video/mp4"
                }
                // Live Photos are delivered as stills; the motion part never reaches the picker.
                name.endsWith(".png") -> "image/png"
                name.endsWith(".webp") -> "image/webp"
                name.endsWith(".gif") -> "image/gif"
                else -> "image/jpeg"
            }
        }
    }
}
