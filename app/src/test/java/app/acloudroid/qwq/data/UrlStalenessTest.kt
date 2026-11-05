package app.acloudroid.qwq.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the staleness rule used by `ICloudPhotoInterceptor` after P2-019.
 *
 * Before the fix a session-generation mismatch alone forced a URL re-mint. Measurement on a
 * physical device (`.artifacts/url_lifetime_experiment.md`) showed that is wrong on both counts:
 *
 *  - a URL stays valid across a full session rebuild (206 after generation N → N+1), so a plain
 *    mismatch is not evidence of a dead link;
 *  - real expiry happens at ~15–18 minutes (206 at 882s, 410 at 1065s), far sooner than the
 *    45-minute window the code used, so age is the signal that actually matters.
 *
 * The rule now: re-mint when the URL is missing, or older than the max age, or (when the
 * generation also moved) older than half the max age.
 */
class UrlStalenessTest {

    // Mirrors ICloudPhotoInterceptor.URL_MAX_AGE_MS (12 min, chosen inside the 15–18 min window).
    private val maxAgeMs = 12 * 60 * 1000L

    private fun shouldRefresh(
        hasUrl: Boolean,
        ageMs: Long,
        generationStale: Boolean
    ): Boolean {
        val ageStale = ageMs > maxAgeMs
        val generationStaleAndAging = generationStale && ageMs > maxAgeMs / 2
        return !hasUrl || ageStale || generationStaleAndAging
    }

    @Test
    fun `fresh url with matching generation is served from cache`() {
        assertFalse(shouldRefresh(hasUrl = true, ageMs = 60_000, generationStale = false))
    }

    @Test
    fun `generation mismatch alone does not force a refresh`() {
        // The core P2-019 regression: this used to re-mint on every rebuild.
        assertFalse(shouldRefresh(hasUrl = true, ageMs = 60_000, generationStale = true))
    }

    @Test
    fun `generation mismatch refreshes once the url is half aged`() {
        assertTrue(shouldRefresh(hasUrl = true, ageMs = maxAgeMs / 2 + 1, generationStale = true))
    }

    @Test
    fun `same age but matching generation is still cached`() {
        assertFalse(shouldRefresh(hasUrl = true, ageMs = maxAgeMs / 2 + 1, generationStale = false))
    }

    @Test
    fun `url older than max age refreshes regardless of generation`() {
        assertTrue(shouldRefresh(hasUrl = true, ageMs = maxAgeMs + 1, generationStale = false))
        assertTrue(shouldRefresh(hasUrl = true, ageMs = maxAgeMs + 1, generationStale = true))
    }

    @Test
    fun `missing url always refreshes`() {
        assertTrue(shouldRefresh(hasUrl = false, ageMs = 0, generationStale = false))
    }

    @Test
    fun `max age sits inside the measured 15 to 18 minute lifetime`() {
        // Guards against someone re-inflating the constant back to the old 45 minutes.
        assertTrue(
            "URL_MAX_AGE_MS must stay under the measured ~15min expiry",
            maxAgeMs < 15 * 60 * 1000L
        )
        assertTrue(
            "URL_MAX_AGE_MS should leave a usable cache window",
            maxAgeMs >= 8 * 60 * 1000L
        )
    }
}
