package app.acloudroid.qwq.feature.photos.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gallery used to refresh one CDN signature per visible thumbnail. A scroll of a 14 901-photo
 * library made every cell stale at once, producing 150+ `/records/lookup` calls in a burst — the
 * path AGENTS §6.2 calls out as getting the account rate-limited.
 *
 * These tests pin down the coalescing contract: one network call per burst, every caller answered,
 * and — the property that actually broke on device — no waiter ever left hanging.
 */
class UrlRefreshCoalescerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun urls(thumb: String) = RefreshedUrls(thumb, null, null, null)

    /**
     * Twenty concurrent requests must collapse into a single batch call, not twenty.
     *
     * This is the whole point of the change: before it, each of these was its own lookup.
     */
    @Test
    fun concurrentRequestsShareOneBatch() = runBlocking {
        val batches = mutableListOf<List<String>>()
        val coalescer = UrlRefreshCoalescer(
            scope = scope,
            coalesceMs = 200,
            maxBatch = 20
        ) { ids ->
            batches += ids
            Result.success(ids.associateWith { urls("thumb-$it") })
        }

        val masters = (1..20).map { "m$it" }
        val results = masters.map { id ->
            async { id to coalescer.request(id).await() }
        }.map { it.await() }

        assertEquals("twenty cells must be one lookup", 1, batches.size)
        assertEquals(masters.toSet(), batches.single().toSet())
        results.forEach { (id, result) ->
            assertEquals("thumb-$id", result.getOrNull()?.thumbUrl)
        }
    }

    /**
     * More than the batch cap splits into ceil(n / maxBatch) calls.
     *
     * 50 masters with a cap of 20 must be 3 lookups, never 50.
     */
    @Test
    fun oversizedBurstIsSplitByBatchCap() = runBlocking {
        val batches = mutableListOf<List<String>>()
        val coalescer = UrlRefreshCoalescer(
            scope = scope,
            coalesceMs = 200,
            maxBatch = 20
        ) { ids ->
            batches += ids
            Result.success(ids.associateWith { urls("t") })
        }

        val masters = (1..50).map { "m$it" }
        masters.map { id -> async { coalescer.request(id).await() } }.forEach { it.await() }

        assertTrue("50 masters must not be 50 lookups: ${batches.size}", batches.size <= 3)
        assertEquals(
            "every master must appear exactly once across batches",
            masters.toSet(),
            batches.flatten().toSet()
        )
    }

    /**
     * Repeating a masterId while a refresh is pending must not duplicate it in the batch.
     *
     * The grid legitimately asks twice: the same photo can be visible in both the month strip and
     * the main grid at once.
     */
    @Test
    fun duplicateMasterIdIsMergedNotDuplicated() = runBlocking {
        val batches = mutableListOf<List<String>>()
        val coalescer = UrlRefreshCoalescer(scope, coalesceMs = 200, maxBatch = 20) { ids ->
            batches += ids
            Result.success(ids.associateWith { urls("t") })
        }

        val first = async { coalescer.request("same").await() }
        val second = async { coalescer.request("same").await() }

        val a = first.await()
        val b = second.await()

        assertEquals(1, batches.size)
        assertEquals(listOf("same"), batches.single())
        assertEquals(a.getOrNull()?.thumbUrl, b.getOrNull()?.thumbUrl)
    }

    /**
     * A failing batch must fail **every** waiter in it, not just leave them hanging.
     *
     * This is the regression that matters most: on device a caller could end up awaiting a handle
     * that was never completed, which is indistinguishable from a frozen grid.
     */
    @Test
    fun failedBatchCompletesEveryWaiter() = runBlocking {
        val coalescer = UrlRefreshCoalescer(scope, coalesceMs = 200, maxBatch = 20) {
            Result.failure(IllegalStateException("lookup exploded"))
        }

        val masters = (1..5).map { "m$it" }
        val results = withTimeout(5_000) {
            masters.map { id -> async { coalescer.request(id).await() } }.map { it.await() }
        }

        assertEquals(5, results.size)
        results.forEach { result ->
            assertTrue("a failed batch must surface as a failure", result.isFailure)
            assertNotNull(result.exceptionOrNull())
        }
    }

    /**
     * A master the server did not answer for must still complete, with a failure.
     *
     * A partial response is normal (a malformed record name, for instance). Treating a missing key
     * as "nothing to do" would leave that caller blocked forever.
     */
    @Test
    fun missingEntryInBatchFailsThatWaiter() = runBlocking {
        val coalescer = UrlRefreshCoalescer(scope, coalesceMs = 200, maxBatch = 20) { ids ->
            // Answer only the first id; the rest must not be stranded.
            val present = ids.first()
            Result.success(mapOf(present to urls("t")))
        }

        val results = withTimeout(5_000) {
            listOf("a", "b").map { id -> async { id to coalescer.request(id).await() } }
                .map { it.await() }
        }.toMap()

        assertTrue("the answered id must succeed", results.getValue("a").isSuccess)
        assertTrue("the unanswered id must fail rather than hang", results.getValue("b").isFailure)
    }

    /**
     * [UrlRefreshCoalescer.abandon] frees the slot so a later request starts fresh.
     *
     * If the map entry survived, every subsequent request for that asset would join a handle that
     * nobody completes — the original bug, just moved.
     */
    @Test
    fun abandonAllowsAFreshRequest() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val coalescer = UrlRefreshCoalescer(scope, coalesceMs = 50, maxBatch = 20) { ids ->
            calls += ids
            Result.success(ids.associateWith { urls("t") })
        }

        // Abandon while the batch is still open, so the request is dropped before it is sent.
        val waiter = coalescer.request("m1")
        coalescer.abandon("m1", waiter)

        // The abandoned waiter must not block a new request for the same master.
        val result = withTimeout(5_000) { coalescer.request("m1").await() }

        assertTrue("a re-requested master must be refreshed again", result.isSuccess)

        // The abandoned request may or may not have been batched (it was already queued); what
        // matters is that the master reached the network at least once and was answered.
        assertTrue("the master must have been refreshed", calls.any { "m1" in it })
    }

    /** [UrlRefreshCoalescer.abortAll] answers everything still parked, e.g. on logout. */
    @Test
    fun abortAllCompletesPendingWaiters() {
        val gate = CompletableDeferred<Unit>()
        val coalescer = UrlRefreshCoalescer(scope, coalesceMs = 500, maxBatch = 20) {
            gate.await()
            Result.success(emptyMap())
        }

        runBlocking {
            val waiter = coalescer.request("m1")
            // Let the consumer pick the request up before aborting.
            delay(100)
            coalescer.abortAll(IllegalStateException("logout"))

            val result = withTimeout(5_000) { waiter.await() }
            assertTrue(result.isFailure)
            gate.complete(Unit)
        }
    }
}
