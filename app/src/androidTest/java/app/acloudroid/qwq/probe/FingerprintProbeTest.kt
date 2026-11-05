package app.acloudroid.qwq.probe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.acloudroid.qwq.data.session.SessionManager
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull

/**
 * A probe test to investigate the deduplication mechanisms on a real device with a real session.
 */
@RunWith(AndroidJUnit4::class)
class FingerprintProbeTest {
    private val TAG = "FingerprintProbe"

    @Test
    fun probeSessionState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionManager = SessionManager(context)

        val cookies = sessionManager.getCookieString() ?: ""
        assertNotNull("Cookies should not be null", cookies)
        Log.i(TAG, "Number of cookies stored: ${cookies.split(";").size}")

        val hasPhotosCookie = cookies.contains("X-APPLE-WEBAUTH-PCS-Photos")
        Log.i(TAG, "Has X-APPLE-WEBAUTH-PCS-Photos cookie: $hasPhotosCookie")

        if (!hasPhotosCookie) {
            Log.w(TAG, "WARNING: PCS-Photos cookie is missing. Upload will likely fail.")
        }
    }
}
