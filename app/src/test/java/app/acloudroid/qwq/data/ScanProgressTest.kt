package app.acloudroid.qwq.data

import app.acloudroid.qwq.feature.photos.data.ScanProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the progress fraction shown in the gallery bar and the background notification (P2-016).
 *
 * The denominator comes from scraping the iCloud web page, and that count does not agree exactly
 * with the number of `CPLAsset` records the scan writes (hidden items, orphan masters, Live Photo
 * composition). The scan can therefore reach or pass the estimate while still working, so the
 * fraction must never report 100% early — a bar that sits at 100% while the app is visibly busy is
 * worse than no bar at all.
 */
class ScanProgressTest {

    @Test
    fun `no denominator yields null fraction`() {
        assertNull(ScanProgress(isScanning = true, scanned = 500, estimatedTotal = 0).fraction)
    }

    @Test
    fun `normal progress is a plain ratio`() {
        val p = ScanProgress(isScanning = true, scanned = 4000, estimatedTotal = 16000)
        assertEquals(0.25f, p.fraction!!, 0.0001f)
    }

    @Test
    fun `reaching the estimate is clamped below one`() {
        val p = ScanProgress(isScanning = true, scanned = 16000, estimatedTotal = 16000)
        assertEquals(0.99f, p.fraction!!, 0.0001f)
    }

    @Test
    fun `overshooting the estimate is still clamped below one`() {
        // Happens when the web page under-counts relative to CPLAsset records.
        val p = ScanProgress(isScanning = true, scanned = 20000, estimatedTotal = 16000)
        assertEquals(0.99f, p.fraction!!, 0.0001f)
    }

    @Test
    fun `completion reports exactly one regardless of the estimate`() {
        val p = ScanProgress(isScanning = false, scanned = 16116, estimatedTotal = 16000, isComplete = true)
        assertEquals(1f, p.fraction!!, 0.0001f)
    }

    @Test
    fun `completion reports one even without a denominator`() {
        val p = ScanProgress(isScanning = false, scanned = 16116, estimatedTotal = 0, isComplete = true)
        assertEquals(1f, p.fraction!!, 0.0001f)
    }

    @Test
    fun `zero scanned is zero`() {
        val p = ScanProgress(isScanning = true, scanned = 0, estimatedTotal = 16000)
        assertEquals(0f, p.fraction!!, 0.0001f)
    }

    @Test
    fun `defaults are idle`() {
        val p = ScanProgress()
        assertEquals(false, p.isScanning)
        assertEquals(0, p.scanned)
        assertEquals(0, p.estimatedTotal)
        assertEquals(0, p.currentYearMonth)
        assertEquals(false, p.isComplete)
    }
}
