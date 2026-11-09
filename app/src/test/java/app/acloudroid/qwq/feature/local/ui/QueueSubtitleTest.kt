package app.acloudroid.qwq.feature.local.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "stale error on a live row" fix.
 *
 * Reported symptom: pressing 重试失败 on a FAILED upload put the row back in the queue, but the
 * subtitle kept showing the old reason, so `6.2 MB · NETWORK: timeout` sat under a 正在上传 chip and
 * the button appeared not to have worked.
 *
 * Two layers fix it -- the DAO nulls `lastError`, and this rendering rule refuses to show an error
 * for any status other than FAILED. The tests pin the second layer, because that is the one that
 * holds even if a future code path forgets to clear the column.
 */
class QueueSubtitleTest {

    @Test
    fun `failed row shows the reason`() {
        assertEquals(
            "6.2 MB · NETWORK: timeout",
            QueueSubtitle.build("6.2 MB", "FAILED", "NETWORK: timeout")
        )
    }

    /** The regression: a retried row is live again, so the old reason must not appear. */
    @Test
    fun `pending row hides stale reason`() {
        assertEquals(
            "6.2 MB",
            QueueSubtitle.build("6.2 MB", "PENDING", "NETWORK: timeout")
        )
    }

    @Test
    fun `uploading row hides stale reason`() {
        assertEquals(
            "6.2 MB",
            QueueSubtitle.build("6.2 MB", "UPLOADING", "NETWORK: timeout")
        )
    }

    @Test
    fun `success row hides stale reason`() {
        assertEquals(
            "6.2 MB",
            QueueSubtitle.build("6.2 MB", "SUCCESS", "NETWORK: timeout")
        )
    }

    @Test
    fun `failed row without a reason is just the size`() {
        assertEquals("6.2 MB", QueueSubtitle.build("6.2 MB", "FAILED", null))
        assertEquals("6.2 MB", QueueSubtitle.build("6.2 MB", "FAILED", ""))
        assertEquals("6.2 MB", QueueSubtitle.build("6.2 MB", "FAILED", "   "))
    }

    @Test
    fun `reason is trimmed`() {
        assertEquals(
            "6.2 MB · TIMEOUT",
            QueueSubtitle.build("6.2 MB", "FAILED", "  TIMEOUT  ")
        )
    }

    @Test
    fun `only failed counts as failed`() {
        assertTrue(QueueSubtitle.isFailed("FAILED"))
        listOf("PENDING", "UPLOADING", "SUCCESS", "failed", "").forEach {
            assertFalse("status=$it should not be treated as FAILED", QueueSubtitle.isFailed(it))
        }
    }
}
