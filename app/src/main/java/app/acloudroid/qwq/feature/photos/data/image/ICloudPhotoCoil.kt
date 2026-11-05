package app.acloudroid.qwq.feature.photos.data.image

import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.intercept.Interceptor
import coil.key.Keyer
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.Options
import app.acloudroid.qwq.BuildConfig
import app.acloudroid.qwq.data.session.SessionManager
import app.acloudroid.qwq.di.AppContainer
import app.acloudroid.qwq.feature.photos.data.PhotoRepository
import app.acloudroid.qwq.feature.photos.data.RefreshedUrls
import app.acloudroid.qwq.feature.photos.data.db.PhotoAssetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URL

data class ICloudImageModel(
    val recordName: String,
    val masterId: String,
    val url: String?,
    val fetchedAt: Long,
    val sessionGeneration: Int = 0,
    val quality: Quality = Quality.THUMB,
    /**
     * When true the request may only be served from an existing cache entry.
     *
     * This exists for the full-screen viewer's *base* layer: it deliberately asks for the same
     * `THUMB` cache key the grid already populated, so opening a photo paints instantly from the
     * in-memory bitmap instead of flashing a spinner. Because it is a pure cache read, it must not
     * trigger a `/records/lookup`: a miss simply fails and the sharp layer above it covers the
     * screen, so spending a network round trip on it would only contend with the real request.
     *
     * In particular this is what keeps a cold URL (expired signature) from producing *two* lookups
     * per opened photo — the base layer would otherwise re-mint concurrently with the sharp layer.
     *
     * NOTE: this cannot be enforced by the interceptor alone; the request must also disable
     * network reads (`ImageRequest.networkCachePolicy(ENABLED/DISABLED)` is controlled by the
     * caller). The interceptor's job is only to not mint a URL on the way in.
     */
    val cacheOnly: Boolean = false
) {
    enum class Quality { THUMB, MEDIUM, ORIGINAL }

    companion object {
        fun fromEntity(entity: PhotoAssetEntity, quality: Quality = Quality.THUMB): ICloudImageModel {
            return ICloudImageModel(
                recordName = entity.id,
                masterId = entity.masterId,
                url = pickUrl(entity, quality),
                fetchedAt = entity.urlFetchedAt,
                sessionGeneration = entity.sessionGeneration,
                quality = quality
            )
        }

        /** Builds the cache-only base layer used by the full-screen viewer. */
        fun cacheOnlyThumb(entity: PhotoAssetEntity): ICloudImageModel =
            fromEntity(entity, Quality.THUMB).copy(cacheOnly = true)

        fun pickUrl(entity: PhotoAssetEntity, quality: Quality): String? = when (quality) {
            Quality.THUMB -> entity.thumbUrl ?: entity.medUrl ?: entity.originalUrl
            Quality.MEDIUM -> entity.medUrl ?: entity.originalUrl ?: entity.thumbUrl
            Quality.ORIGINAL -> entity.originalUrl ?: entity.medUrl ?: entity.thumbUrl
        }

        fun pickUrl(urls: RefreshedUrls?, quality: Quality): String? = when (quality) {
            Quality.THUMB -> urls?.thumbUrl ?: urls?.medUrl ?: urls?.originalUrl
            Quality.MEDIUM -> urls?.medUrl ?: urls?.originalUrl ?: urls?.thumbUrl
            Quality.ORIGINAL -> urls?.originalUrl ?: urls?.medUrl ?: urls?.thumbUrl
        }
    }
}

class ICloudPhotoKeyer : Keyer<ICloudImageModel> {
    override fun key(data: ICloudImageModel, options: Options): String {
        return "icloud_${data.recordName}_${data.quality.name.lowercase()}"
    }
}

/**
 * Classifies why an image load failed (P2-011).
 *
 * Only a dead signature is fixable by re-minting the URL. A decoder failure (e.g. an exotic HEIC
 * variant) returns the same broken bytes no matter how many fresh URLs we ask for, so treating it
 * as a URL problem just burns `/records/lookup` calls and floods the log.
 */
internal object ImageLoadFailure {
    /** HTTP status code carried by [throwable], or null when it was not an HTTP failure. */
    fun httpCodeOf(throwable: Throwable?): Int? =
        (throwable as? coil.network.HttpException)?.response?.code

    /** The two statuses a CDN uses when a signed URL is no longer accepted. */
    fun isExpiredUrlCode(code: Int?): Boolean = code == 403 || code == 410

    /** True when the failure means "this signed URL is no longer accepted by the CDN". */
    fun isExpiredUrl(throwable: Throwable?): Boolean =
        isExpiredUrlCode(httpCodeOf(throwable))
}

/**
 * Resolves and, when needed, re-mints iCloud CDN download URLs before Coil executes a request.
 *
 * Invalidation signals, in order of what actually causes a dead link:
 *
 *  1. **Age** — the dominant factor. **Measured on device** (SM-F9760, see
 *     `.artifacts/url_lifetime_experiment.md`): a `thumbUrl` returns `206` at 882s (14.7 min) and
 *     `410` at 1065s (17.75 min). So the real lifetime is ~15–18 minutes, not the "1–2 hours"
 *     the earlier docs claimed. [URL_MAX_AGE_MS] is therefore set to 12 min: comfortably inside
 *     the observed window, so we re-mint proactively instead of waiting for a 410.
 *
 *  2. **Session generation** — *not* a validity signal on its own. The same experiment showed a
 *     URL minted under generation N still returning `206` after a full session rebuild into
 *     N+1, so generation is now bumped only when credentials are genuinely dead (`force` rebuild
 *     or 401/421 recovery). It remains useful for the account-switch case (P2-018), where the
 *     `recordName` itself no longer belongs to the current account — and there a *matching*
 *     generation must still refresh because the row is from a different library entirely.
 *
 * On top of the proactive check, any failed load (403/410 or otherwise) triggers exactly one
 * refresh-and-retry, so we are never silently stuck on a dead link.
 */
class ICloudPhotoInterceptor(
    private val repository: PhotoRepository,
    private val sessionManager: SessionManager
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data
        if (data !is ICloudImageModel) {
            return chain.proceed(request)
        }

        val stableKey = "icloud_${data.recordName}_${data.quality.name.lowercase()}"

        // Cache-only: never mint a URL, never retry. A miss is fine.
        //
        // NOTE: this must NOT go through `buildRequest`, which replaces `data` with the URL. A
        // cache-only caller deliberately has no URL (it is asking "give me whatever is already
        // cached for this key"), and substituting `""` replaces the image source with an empty
        // string — no fetcher can handle that, so the request fails without ever consulting the
        // disk cache. The key alone is what makes the lookup work.
        if (data.cacheOnly) {
            val keyed = request.newBuilder()
                .memoryCacheKey(stableKey)
                .diskCacheKey(stableKey)
                .build()
            return chain.proceed(keyed)
        }

        val currentGeneration = sessionManager.getSessionGeneration()
        val generationStale = data.sessionGeneration != currentGeneration
        val ageMs = System.currentTimeMillis() - data.fetchedAt
        val ageStale = ageMs > URL_MAX_AGE_MS

        // P2-019: a generation mismatch alone is NOT a reason to re-mint. Measured on device, a
        // URL minted under generation N still returned 206 after a full rebuild into N+1 — the
        // CDN signature is bound to the record, not to the session. Keying purely on generation
        // meant every genuine rebuild (`force` on auth recovery) re-fetched every visible
        // thumbnail, which is the actual mechanism behind the P2-008 refresh storm.
        //
        // It is still a *hint* though, and a cheap one: if the session changed, the row may come
        // from an entirely different account (P2-018), where re-minting against the new session
        // is the only way to discover the record is foreign. So a mismatch shortens the tolerated
        // age to half. That keeps the cross-account case responsive without paying a lookup per
        // cell on every ordinary rebuild.
        val generationStaleAndAging = generationStale && ageMs > URL_MAX_AGE_MS / 2

        var activeUrl = data.url
        var refreshedProactively = false

        // NOTE: this interceptor runs for every visible cell, on the caller's dispatcher, and a
        // cold start fires ~24 of them back to back. Verbose per-image logging here cost real
        // main-thread time (Logcat is an IPC), so the happy path stays silent unless the
        // request needed network work.
        if (BuildConfig.DEBUG && (activeUrl.isNullOrBlank() || ageStale || generationStale)) {
            Log.d(
                TAG,
                "load ${data.recordName} quality=${data.quality} " +
                    "hasUrl=${!activeUrl.isNullOrBlank()} ageMs=$ageMs ageStale=$ageStale " +
                    "genStale=$generationStale"
            )
        }

        if (activeUrl.isNullOrBlank() || ageStale || generationStaleAndAging) {
            val fresh = refresh(data)
            if (fresh != null) {
                activeUrl = pickFreshUrl(data, fresh)
                refreshedProactively = true
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "refreshed ${data.recordName} -> host=${hostOf(activeUrl)}")
                }
            }
        }

        val result = chain.proceed(buildRequest(request, activeUrl, stableKey))

        when (result) {
            is ErrorResult -> {
                val message = result.throwable.message ?: result.throwable::class.java.simpleName
                Log.w(TAG, "fail ${data.recordName} quality=${data.quality} error=$message")

                // One shot refresh-and-retry, unless we already minted a fresh URL this pass.
                if (!refreshedProactively) {
                    val fresh = refresh(data)
                    val retryUrl = pickFreshUrl(data, fresh)
                    if (!retryUrl.isNullOrBlank() && retryUrl != activeUrl) {
                        Log.i(
                            TAG,
                            "retrying ${data.recordName} with refreshed url host=${hostOf(retryUrl)}"
                        )
                        return chain.proceed(buildRequest(request, retryUrl, stableKey))
                    }
                }
            }
            else -> Unit // happy path: no log, keeps the first-screen burst quiet
        }

        return result
    }

    private fun buildRequest(
        request: coil.request.ImageRequest,
        url: String?,
        stableKey: String
    ): coil.request.ImageRequest {
        return request.newBuilder()
            .data(url ?: "")
            .memoryCacheKey(stableKey)
            .diskCacheKey(stableKey)
            .build()
    }

    private suspend fun refresh(data: ICloudImageModel): RefreshedUrls? = refreshGate.withPermit {
        // Bound the burst: a generation bump makes every visible cell stale at once.
        val result = repository.refreshUrlsForMaster(data.masterId)
        result.getOrNull().also {
            if (it == null) {
                Log.w(
                    TAG,
                    "refresh failed for master=${data.masterId}: ${result.exceptionOrNull()?.message}"
                )
            }
        }
        return@withPermit result.getOrNull()
    }

    private fun pickFreshUrl(data: ICloudImageModel, urls: RefreshedUrls?): String? {
        return ICloudImageModel.pickUrl(urls, data.quality) ?: data.url
    }

    private fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return "none"
        return try {
            URL(url).host
        } catch (e: Exception) {
            "invalid"
        }
    }

    companion object {
        private const val TAG = "ICloudPhotoCoil"

        /**
         * How old a stored `downloadURL` may be before it is proactively re-minted.
         *
         * Chosen from measurement, not guesswork. Probing a real `thumbUrl` every 3 minutes on a
         * physical device (see `.artifacts/url_lifetime_experiment.md`):
         *
         * | age | status |
         * | --- | --- |
         * | 701s (11.7m) | 206 |
         * | 882s (14.7m) | 206 |
         * | 1065s (17.8m) | 410 |
         *
         * So the true lifetime is ~15–18 minutes. 12 minutes sits inside that window with a few
         * minutes of headroom for clock skew and slow networks, while still being short enough
         * that a thumbnail is refreshed *before* the CDN starts refusing it. The 45-minute value
         * this replaced was inherited from the (incorrect) "1–2 hour" assumption and left a
         * ~30 minute blind spot where every load had to fail once before recovering.
         */
        private const val URL_MAX_AGE_MS = 12 * 60 * 1000L

        /**
         * Caps how many URL re-mints run at once.
         */
        private val refreshGate = Semaphore(permits = 3)
    }
}

object ICloudImageLoaderFactory {
    @Volatile
    private var INSTANCE: ImageLoader? = null

    /**
     * Ceiling for the thumbnail disk cache.
     *
     * 256MB covers roughly 5,000 thumbnails at ~50KB each, which is several screens' worth of
     * scrolling in each direction - far more than the jump card or the grid will ask for at once.
     * Coil evicts by LRU past this, so it is a cap on disk usage rather than a quota to fill.
     */
    private const val DISK_CACHE_MAX_BYTES = 256L * 1024 * 1024

    /**
     * Builds (once) the Coil loader for iCloud assets.
     *
     * Dependencies come from [app.acloudroid.qwq.di.AppContainer] rather than being constructed
     * here, so the loader and the repositories share exactly one `SessionManager` instance.
     */
    fun get(context: Context, container: AppContainer): ImageLoader {
        return INSTANCE ?: synchronized(this) {
            val loader = ImageLoader.Builder(context)
                .components {
                    // P2-010: iCloud hands back the native HEIC for `resOriginalRes`, and some of
                    // those carry an HDR gain map (compatible brand `MiHE` rather than `MiPr`).
                    // Coil's default BitmapFactory decoder returns a null bitmap for them, so
                    // originals showed "image failed to load" while the JPEG thumbnails (already
                    // transcoded server-side) were fine.
                    // ImageDecoder handles gain maps and multi-image HEIF correctly.
                    add(ImageDecoderDecoder.Factory())
                    add(ICloudPhotoKeyer())
                    add(ICloudPhotoInterceptor(container.photoRepository, container.sessionManager))
                }
                // Disk cache must be attached EXPLICITLY. Coil only installs its default disk cache
                // when the loader comes from `Coil.imageLoader(context)`; a hand-built
                // `ImageLoader.Builder(context).build()` has none. Without this line every
                // "prefetch" reported success while writing nothing, `clearCaches()` silently
                // no-oped, and a cold start re-downloaded every visible thumbnail — the cache in
                // `cache/image_cache` was being written by the *latter* path only.
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("image_cache"))
                        .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                        .build()
                }
                .crossfade(true)
                .build()
            INSTANCE = loader
            loader
        }
    }

    /**
     * The already-built loader, or null if nothing has asked for it yet.
     *
     * For background work that only *uses* the cache and has no business constructing the loader:
     * if it does not exist, nothing has ever rendered a thumbnail, so there is no warm cache to
     * populate and there is nothing to do. Background callers should treat null as "skip", not
     * "build it" — building from a worker thread would race `Application.onCreate`'s warm-up and
     * defeat the very contention that warm-up exists to avoid.
     */
    fun peek(): ImageLoader? = INSTANCE

    /**
     * Builds the loader and touches its disk cache ahead of first use.
     *
     * `DiskLruCache.initialize()` scans the journal under a monitor. When the first grid frame
     * requests ~24 thumbnails at once they all queue behind that lock (measured 160ms of monitor
     * contention), which shows up as dropped frames on a cold start. Calling this from
     * `Application.onCreate` on a background dispatcher pays the cost before the grid exists.
     *
     * Must be called off the main thread. Best effort: failures are ignored by the caller.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun warmUp(context: Context, container: AppContainer) {
        val loader = get(context, container)
        loader.diskCache?.openSnapshot("__warmup__")?.close()
    }

    /**
     * Drops every cached image byte, memory and disk (P2-018).
     *
     * Cache keys are `icloud_{recordName}_{quality}` — stable by design (AGENTS §6.3), which also
     * means they are **account-scoped without saying so**: the same key in a later session points
     * at a `recordName` that either does not exist or belongs to a different account, and the
     * bytes on disk were fetched with a signature that has since died. That is why a stale
     * `image_cache/` shows the old account's thumbnails even when the DB was cleared.
     *
     * Only clears caches; the loader instance and its interceptor wiring survive, so the next
     * login reuses the same singleton (`warmUp` already opened the disk cache and it stays warm).
     *
     * @return true when both caches were cleared without an exception.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun clearCaches(context: Context, container: AppContainer): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val loader = get(context, container)
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
                Log.i(TAG, "Coil memory + disk caches cleared")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Coil cache clear failed", e)
                false
            }
        }

    /**
     * Evicts specific assets from both memory and disk caches.
     *
     * Used when photos are deleted remotely so deleted thumbnails and previews
     * do not linger in Coil caches.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun evictAssets(recordNames: Collection<String>) {
        if (recordNames.isEmpty()) return
        val loader = INSTANCE ?: return
        try {
            val qualities = listOf("thumb", "med", "original")
            for (name in recordNames) {
                for (q in qualities) {
                    val key = "icloud_${name}_$q"
                    loader.memoryCache?.remove(coil.memory.MemoryCache.Key(key))
                    loader.diskCache?.remove(key)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to evict assets from Coil cache: ${e.message}")
        }
    }

    private const val TAG = "ICloudPhotoCoil"
}
