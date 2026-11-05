package app.acloudroid.qwq.data

import app.acloudroid.qwq.feature.photos.data.parseCookiePairs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards P2-019 / ISS-026.
 *
 * `PhotoRepository.applyRefreshedCookies` decides whether the session's credentials rotated by
 * comparing the cookie set Go just produced against the persisted one. It used to compare the
 * **raw strings**, which broke because Go iterated its `CookieMap` in randomised order: the same
 * cookie set serialised differently on every call, so every cold start looked like a rotation.
 *
 * Symptoms observed on device:
 * ```
 * D/PhotoRepository: Cookies rotated (len 2893 -> 2893)     // same length, yet "rotated"
 * I/PhotoRepository: Session generation -> 3 (URLs previously minted are now stale)
 * ```
 * That bumps the session generation, marking the whole cached photo library stale and firing a
 * `/records/lookup` per visible thumbnail.
 *
 * The root fix is in go-core/session.go `BuildCookieHeader` (keys are now sorted). These tests
 * pin the Kotlin-side defence: comparison must be order-insensitive.
 */
class CookieRotationTest {

    private val tokenA = "X-APPLE-WEBAUTH-TOKEN=v=2:t=AQAAAAB/cd=="
    private val trustA = "X-APPLE-WEBAUTH-HSA-TRUST=AQAAAAAAA="
    private val pcsA = "X-APPLE-WEBAUTH-PCS-CloudKit=PCS_TOKEN_123=="

    @Test
    fun `same cookies in different order are one set`() {
        val inOrder = "$tokenA; $trustA; $pcsA"
        val shuffled = "$pcsA; $tokenA; $trustA"

        // Different strings...
        assertNotEquals(inOrder, shuffled)
        // ...but identical credentials. This is exactly the jitter that used to be misread.
        assertEquals(parseCookiePairs(inOrder), parseCookiePairs(shuffled))
    }

    @Test
    fun `reordered cookie header must not count as rotation`() {
        val persisted = "$tokenA; $trustA; $pcsA"
        val fromGo = "$pcsA; $trustA; $tokenA"

        assertFalse(
            "order-only jitter must not be treated as a credential rotation",
            parseCookiePairs(fromGo) != parseCookiePairs(persisted)
        )
    }

    @Test
    fun `changed value is a real rotation`() {
        val persisted = "$tokenA; $trustA; $pcsA"
        val renewed = "X-APPLE-WEBAUTH-TOKEN=v=2:t=DIFFERENT; $trustA; $pcsA"

        assertNotEquals(parseCookiePairs(persisted), parseCookiePairs(renewed))
    }

    @Test
    fun `added or removed cookie is a real rotation`() {
        val persisted = "$tokenA; $trustA"

        assertNotEquals(
            parseCookiePairs(persisted),
            parseCookiePairs("$persisted; $pcsA")
        )
        assertNotEquals(
            parseCookiePairs(persisted),
            parseCookiePairs(tokenA)
        )
    }

    @Test
    fun `values containing equals signs survive parsing`() {
        // Base64 padding and the `v=2:` prefix both contain '='; only the first one separates.
        val pairs = parseCookiePairs("$tokenA; $trustA; $pcsA")

        assertEquals("v=2:t=AQAAAAB/cd==", pairs.first { it.first == "X-APPLE-WEBAUTH-TOKEN" }.second)
        assertEquals("AQAAAAAAA=", pairs.first { it.first == "X-APPLE-WEBAUTH-HSA-TRUST" }.second)
        assertEquals("PCS_TOKEN_123==", pairs.first { it.first == "X-APPLE-WEBAUTH-PCS-CloudKit" }.second)
        assertEquals(3, pairs.size)
    }

    @Test
    fun `rfc2965 attributes and blanks are skipped`() {
        val pairs = parseCookiePairs("$tokenA; \$Path=/; \$Version=1; ;  ")
        assertEquals(setOf("X-APPLE-WEBAUTH-TOKEN" to "v=2:t=AQAAAAB/cd=="), pairs)
    }

    @Test
    fun `empty and null input yield an empty set`() {
        assertTrue(parseCookiePairs(null).isEmpty())
        assertTrue(parseCookiePairs("").isEmpty())
        assertTrue(parseCookiePairs("   ").isEmpty())
    }

    @Test
    fun `duplicate keys with identical values collapse`() {
        // Shouldn't happen from a real header, but must not produce a phantom rotation.
        assertEquals(
            parseCookiePairs(tokenA),
            parseCookiePairs("$tokenA; $tokenA")
        )
    }
}
