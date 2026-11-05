package app.acloudroid.qwq.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieValidationResultTest {

    @Test
    fun `validate with null or empty string returns invalid`() {
        val nullResult = CookieValidationResult.validate(null)
        assertFalse(nullResult.isValid)
        assertEquals(0, nullResult.cookieNames.size)

        val emptyResult = CookieValidationResult.validate("   ")
        assertFalse(emptyResult.isValid)
        assertEquals(0, emptyResult.cookieNames.size)
    }

    @Test
    fun `validate with all required cookies returns valid`() {
        val cookieStr = "X-APPLE-WEBAUTH-TOKEN=token123; X-APPLE-WEBAUTH-HSA-TRUST=trust456; X-APPLE-WEBAUTH-PCS-CloudKit=pcs789"
        val result = CookieValidationResult.validate(cookieStr)

        assertTrue(result.isValid)
        assertTrue(result.hasAuthToken)
        assertTrue(result.hasHsaTrust)
        assertTrue(result.hasPcsCloudKit)
        assertEquals(3, result.cookieNames.size)
        assertEquals(8, result.valueLength("X-APPLE-WEBAUTH-TOKEN"))
        assertEquals(8, result.valueLength("X-APPLE-WEBAUTH-HSA-TRUST"))
        assertEquals(6, result.valueLength("X-APPLE-WEBAUTH-PCS-CloudKit"))
    }

    @Test
    fun `validate is case insensitive for cookie names`() {
        // Notice lowercase Cloudkit
        val cookieStr = "x-apple-webauth-token=token123; x-apple-webauth-pcs-cloudkit=pcs789"
        val result = CookieValidationResult.validate(cookieStr)

        assertTrue(result.isValid)
        assertTrue(result.hasAuthToken)
        assertTrue(result.hasPcsCloudKit)
        assertFalse(result.hasHsaTrust)
    }

    @Test
    fun `validate with missing PCS cookie returns invalid`() {
        val cookieStr = "X-APPLE-WEBAUTH-TOKEN=token123; X-APPLE-WEBAUTH-HSA-TRUST=trust456"
        val result = CookieValidationResult.validate(cookieStr)

        assertFalse(result.isValid)
        assertTrue(result.hasAuthToken)
        assertTrue(result.hasHsaTrust)
        assertFalse(result.hasPcsCloudKit)
        assertEquals(listOf("X-APPLE-WEBAUTH-PCS-CloudKit"), result.missingKeys)
    }

    @Test
    fun `validate valueLength handles quotes and trimming properly`() {
        val cookieStr = "X-APPLE-WEBAUTH-TOKEN=\"token_with_quotes\"; foo=bar"
        val result = CookieValidationResult.validate(cookieStr)

        assertEquals("token_with_quotes".length, result.valueLength("X-APPLE-WEBAUTH-TOKEN"))
    }
}
