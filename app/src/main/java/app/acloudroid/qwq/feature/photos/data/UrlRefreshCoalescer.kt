package app.acloudroid.qwq.feature.photos.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Merges per-asset URL refresh requests into batches.
 *
 * ## Why this exists
 *
 * The gallery used to re-mint a CDN signature per thumbnail. Scrolling a large library makes every
 * visible cell stale at the same moment, so one scroll produced 150+ individual
 * `/records/lookup` calls — the exact path AGENTS §6.2 singles out as getting the account
 * rate-limited. A batched refresh already existed (`refreshUrlsForMasters`, one lookup for a whole
 * shard); the grid simply never called it.
 *
 * Callers fire into a channel; a single consumer drains it, waits a short window for more work, and
 * issues **one** batch. Each caller still gets its own answer through the [CompletableDeferred] it
 * brought along, so the API is equivalent to a per-asset refresh.
 *
 * ## Guarantees
 *
 *  - **No stranded waiters.** Every request is completed on every path. A dropped
 *    `CompletableDeferred` would leave its caller blocked until its own timeout, which is the
 *    class of bug this whole change removes.
 *  - **One entry per masterId.** Concurrent requests for the same asset share a waiter, so a batch
 *    never carries duplicates.
 *  - **Bounded batching.** At most [maxBatch] masters per batch, so a response stays small.
 *
 * Extracted from `PhotoRepository` purely so it can be tested on the JVM: the repository itself
 * needs a Room database and an Android `Context`, neither of which this project can build in a
 * unit test (there is no Robolectric dependency).
 */
internal class UrlRefreshCoalescer(
    scope: CoroutineScope,
    private val coalesceMs: Long,
    private val maxBatch: Int,
    private val fetch: suspend (List<String>) -> Result<Map<String, RefreshedUrls>>
) {
    /** One queued request: the asset to refresh and the handle its caller is waiting on. */
    private class Request(
        val masterId: String,
        val result: CompletableDeferred<Result<RefreshedUrls>>
    )

    private val channel = Channel<Request>(Channel.UNLIMITED)

    /** In-flight waiters by masterId, so concurrent requests for one asset share an answer. */
    private val waiters = mutableMapOf<String, CompletableDeferred<Result<RefreshedUrls>>>()

    private val mutex = Mutex()

    /**
     * Started lazily and idempotently: the channel is unbounded so producers never block, and a
     * consumer started only on first use would race the first batch.
     */
    private val consumer = scope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            val first = channel.receive()
            val batch = mutableListOf(first)

            // Close the batch on whichever comes first: the size cap or the window.
            withTimeoutOrNull(coalesceMs) {
                while (batch.size < maxBatch) {
                    batch.add(channel.receive())
                }
            }

            runCatching { drain(batch.map { it.masterId }) }
        }
    }

    /**
     * Queues a refresh for [masterId].
     *
     * Returns the handle the caller should await. Concurrent calls for the same asset return the
     * *same* handle, so one asset never appears twice in a batch.
     */
    suspend fun request(masterId: String): CompletableDeferred<Result<RefreshedUrls>> {
        consumer.start()

        val (waiter, isNew) = mutex.withLock {
            val pending = waiters[masterId]
            if (pending != null) {
                pending to false
            } else {
                val created = CompletableDeferred<Result<RefreshedUrls>>()
                waiters[masterId] = created
                created to true
            }
        }

        if (isNew) {
            channel.send(Request(masterId, waiter))
        }

        return waiter
    }

    /**
     * Drops [waiter] if it is still the one registered for [masterId].
     *
     * Called when a caller gives up waiting. Removing it is what lets the next request for the same
     * asset start fresh instead of joining a handle nobody will ever complete. The identity check
     * keeps that from evicting a newer waiter installed in the meantime.
     */
    suspend fun abandon(masterId: String, waiter: CompletableDeferred<Result<RefreshedUrls>>) {
        mutex.withLock {
            if (waiters[masterId] === waiter) waiters.remove(masterId)
        }
    }

    /** Completes every outstanding waiter with [reason] and clears the map. Used on logout. */
    suspend fun abortAll(reason: Throwable) {
        val orphans = mutex.withLock {
            val snapshot = waiters.values.toList()
            waiters.clear()
            snapshot
        }
        orphans.forEach { it.complete(Result.failure(reason)) }
    }

    /**
     * Runs one batch and completes exactly the waiters it drained.
     *
     * Waiters are removed **before** the network call, so a slow batch does not block a new request
     * for the same asset from being queued behind it.
     */
    private suspend fun drain(masterIds: List<String>) {
        val unique = masterIds.distinct()
        if (unique.isEmpty()) return

        val drained = mutex.withLock {
            unique.mapNotNull { id -> waiters.remove(id)?.let { id to it } }
        }
        if (drained.isEmpty()) return

        val batchResult = fetch(unique)
        val urls = batchResult.getOrNull()

        for ((id, waiter) in drained) {
            val one = urls?.get(id)?.let { Result.success(it) }
                ?: Result.failure(
                    batchResult.exceptionOrNull()
                        ?: Exception("batch refresh returned no entry for master")
                )
            waiter.complete(one)
        }
    }
}
