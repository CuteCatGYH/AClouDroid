package app.acloudroid.qwq.data

import app.acloudroid.qwq.feature.photos.data.CoreErrorKind
import app.acloudroid.qwq.feature.photos.data.classifyCoreResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards P2-015.
 *
 * The Go core layer now reports a structured `errorCode` + `httpStatus` on every result, and
 * [classifyCoreResponse] is the single place that turns that into a decision.
 *
 * This matters far more than it looks: the month-scan rewrite issues a long chain of
 * `/records/query` calls, so a single misclassification compounds. Getting 429 or a timeout wrong
 * means the app would rebuild the session (a `/validate` round trip plus re-signing every cached
 * URL) on every hiccup instead of simply backing off.
 *
 * History this suite pins down:
 *  - ISS-013 / ISS-015: `jsonStr.contains("401")` matched base64 `continuationMarker` content.
 *  - ISS-020: a dead cursor must be dropped, but only on a *confirmed* 401.
 */
class CoreErrorTest {

    private fun classify(json: String) = classifyCoreResponse(json)

    // ---------------------------------------------------------------- structured errorCode

    @Test
    fun `errorCode AUTH_EXPIRED maps to AUTH_EXPIRED`() {
        val result = classify("""{"success":false,"errorCode":"AUTH_EXPIRED","errorMessage":"session gone"}""")
        assertEquals(CoreErrorKind.AUTH_EXPIRED, result.kind)
    }

    @Test
    fun `errorCode RATE_LIMITED is not treated as auth failure`() {
        // The whole point of P2-015: a throttle must be retried, never turned into a rebuild.
        val result = classify(
            """{"success":false,"errorCode":"RATE_LIMITED","httpStatus":429,"errorMessage":"slow down"}"""
        )
        assertEquals(CoreErrorKind.RATE_LIMITED, result.kind)
        assertEquals(429, result.httpStatus)
    }

    @Test
    fun `errorCode TIMEOUT is not treated as auth failure`() {
        val result = classify(
            """{"success":false,"errorCode":"TIMEOUT","errorMessage":"context deadline exceeded"}"""
        )
        assertEquals(CoreErrorKind.TIMEOUT, result.kind)
    }

    @Test
    fun `errorCode ZONE_MISSING is its own kind`() {
        val result = classify(
            """{"success":false,"errorCode":"ZONE_MISSING","httpStatus":200,"errorMessage":"PrimarySync zone not found in zones list"}"""
        )
        assertEquals(CoreErrorKind.ZONE_MISSING, result.kind)
    }

    @Test
    fun `every documented errorCode is understood`() {
        val expected = mapOf(
            "AUTH_EXPIRED" to CoreErrorKind.AUTH_EXPIRED,
            "NOT_FOUND" to CoreErrorKind.NOT_FOUND,
            "RATE_LIMITED" to CoreErrorKind.RATE_LIMITED,
            "TIMEOUT" to CoreErrorKind.TIMEOUT,
            "NETWORK" to CoreErrorKind.NETWORK,
            "PARSE" to CoreErrorKind.PARSE,
            "ZONE_MISSING" to CoreErrorKind.ZONE_MISSING,
            "UNKNOWN" to CoreErrorKind.UNKNOWN
        )
        for ((code, kind) in expected) {
            val result = classify("""{"success":false,"errorCode":"$code"}""")
            assertEquals("errorCode=$code", kind, result.kind)
        }
    }

    @Test
    fun `unknown errorCode falls back to UNKNOWN`() {
        val result = classify("""{"success":false,"errorCode":"SOMETHING_NEW"}""")
        assertEquals(CoreErrorKind.UNKNOWN, result.kind)
    }

    // ---------------------------------------------------------------- success

    @Test
    fun `success is NONE even when errorCode is present`() {
        val result = classify("""{"success":true,"errorCode":"AUTH_EXPIRED","photos":[]}""")
        assertEquals(CoreErrorKind.NONE, result.kind)
    }

    @Test
    fun `success without errorCode is NONE`() {
        val result = classify("""{"success":true,"photos":[],"hasMore":false}""")
        assertEquals(CoreErrorKind.NONE, result.kind)
    }

    // ---------------------------------------------------------------- legacy httpStatus path

    @Test
    fun `httpStatus 401 without errorCode still maps to AUTH_EXPIRED`() {
        // Compatibility with responses produced by an older AAR.
        val result = classify("""{"success":false,"httpStatus":401,"errorMessage":"nope"}""")
        assertEquals(CoreErrorKind.AUTH_EXPIRED, result.kind)
    }

    @Test
    fun `httpStatus 421 without errorCode still maps to AUTH_EXPIRED`() {
        val result = classify("""{"success":false,"httpStatus":421,"errorMessage":"misdirected"}""")
        assertEquals(CoreErrorKind.AUTH_EXPIRED, result.kind)
    }

    @Test
    fun `httpStatus 429 without errorCode maps to RATE_LIMITED`() {
        val result = classify("""{"success":false,"httpStatus":429,"errorMessage":"throttled"}""")
        assertEquals(CoreErrorKind.RATE_LIMITED, result.kind)
    }

    @Test
    fun `httpStatus 404 without errorCode maps to NOT_FOUND`() {
        val result = classify("""{"success":false,"httpStatus":404,"errorMessage":"missing"}""")
        assertEquals(CoreErrorKind.NOT_FOUND, result.kind)
    }

    // ---------------------------------------------------------------- base64 false positive

    @Test
    fun `base64 continuationMarker containing 401 is not an auth failure`() {
        // This is the exact shape that broke ISS-013 / ISS-015: the marker is opaque base64 and
        // can contain the digits "401" anywhere.
        val json = """
            {"success":false,
             "continuationMarker":"eyJzZXJ2ZXJDaGFuZ2VUb2tlbiI6IjQwMSJ9",
             "errorMessage":"something unrelated went wrong"}
        """.trimIndent()
        val result = classify(json)
        assertNotEquals(CoreErrorKind.AUTH_EXPIRED, result.kind)
        assertEquals(CoreErrorKind.UNKNOWN, result.kind)
    }

    @Test
    fun `errorMessage containing 401 without a status word is not an auth failure`() {
        // The word-boundary regex must not match a bare number buried in prose.
        val result = classify("""{"success":false,"errorMessage":"record 1401234 could not be found"}""")
        assertNotEquals(CoreErrorKind.AUTH_EXPIRED, result.kind)
    }

    @Test
    fun `errorMessage with an explicit HTTP 401 is an auth failure`() {
        // Compatibility shim for an older AAR that only produced prose.
        val result = classify("""{"success":false,"errorMessage":"records/query HTTP 401: unauthorized"}""")
        assertEquals(CoreErrorKind.AUTH_EXPIRED, result.kind)
    }

    // ---------------------------------------------------------------- malformed input

    @Test
    fun `blank input is UNKNOWN and does not throw`() {
        assertEquals(CoreErrorKind.UNKNOWN, classify("").kind)
    }

    @Test
    fun `non json input is UNKNOWN and does not throw`() {
        assertEquals(CoreErrorKind.UNKNOWN, classify("<html>502 Bad Gateway</html>").kind)
    }

    @Test
    fun `json array input is UNKNOWN and does not throw`() {
        assertEquals(CoreErrorKind.UNKNOWN, classify("""[{"success":false}]""").kind)
    }
}
