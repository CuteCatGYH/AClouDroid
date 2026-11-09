package app.acloudroid.qwq.feature.photos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for ISS-069.
 *
 * The bug: a photo uploaded from a bad network failed three times, was marked `FAILED` in the
 * backup queue, and was *also* skipped by every later scan because a pre-upload fingerprint claim
 * had never been withdrawn. Net effect -- the file could never be backed up again, and nothing
 * surfaced an error.
 *
 * Two properties are pinned here:
 *
 *  1. a claim with no live queue row behind it is always released;
 *  2. a claim with a `PENDING` / `UPLOADING` row is never released, because that would race the
 *     upload currently in flight.
 */
class FingerprintHealingTest {

    private fun heal(keys: List<String>, statuses: Map<String, String>) =
        FingerprintHealing.keysToWithdraw(keys, statuses)

    /** The exact reported scenario: retries burned, queue row left as FAILED. */
    @Test
    fun `failed upload releases its claim`() {
        val result = heal(
            listOf("20261108_191331.heic|6287052|1794136414"),
            mapOf("20261108_191331.heic|6287052|1794136414" to "FAILED")
        )
        assertEquals(listOf("20261108_191331.heic|6287052|1794136414"), result)
    }

    /** The user cleared the queue; the abandoned claim must not keep hiding the file. */
    @Test
    fun `claim without a queue row is released`() {
        val result = heal(listOf("a.jpg|100|1700000000"), emptyMap())
        assertEquals(listOf("a.jpg|100|1700000000"), result)
    }

    /** An in-flight upload owns its claim; releasing it would let a scan double-enqueue. */
    @Test
    fun `pending and uploading claims are kept`() {
        val keys = listOf("a|1|1", "b|2|2")
        val result = heal(keys, mapOf("a|1|1" to "PENDING", "b|2|2" to "UPLOADING"))
        assertTrue(result.isEmpty())
    }

    /** A confirmed row is not a claim at all; it must never be touched. */
    @Test
    fun `successful claim is kept`() {
        assertTrue(heal(listOf("a|1|1"), mapOf("a|1|1" to "SUCCESS")).isEmpty())
    }

    /**
     * The mixed case that matters in practice -- one bad network session produces several failed
     * uploads while another is still retrying.
     */
    @Test
    fun `only the dead claims are released`() {
        val keys = listOf("bad1|1|1", "bad2|2|2", "live|3|3", "gone|4|4")
        val result = heal(
            keys,
            mapOf(
                "bad1|1|1" to "FAILED",
                "bad2|2|2" to "FAILED",
                "live|3|3" to "UPLOADING"
                // "gone|4|4" deliberately absent
            )
        )
        assertEquals(listOf("bad1|1|1", "bad2|2|2", "gone|4|4"), result)
    }

    /** An unknown status must still resolve towards withdrawal: stranding is the worse failure. */
    @Test
    fun `unknown status releases the claim`() {
        assertEquals(listOf("a|1|1"), heal(listOf("a|1|1"), mapOf("a|1|1" to "WHATEVER")))
    }

    @Test
    fun `nothing to heal is a no-op`() {
        assertTrue(heal(emptyList(), mapOf("a|1|1" to "FAILED")).isEmpty())
    }
}
