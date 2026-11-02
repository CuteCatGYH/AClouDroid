package com.getup.iClouDroid.probe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.getup.iClouDroid.data.session.SessionManager
import com.getup.icloudcore.Icloudcore
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D3 reconnaissance — see `docs/TODO_DRIVE.md` §5.0.
 *
 * D0 proved `changes/database` does not exist, so the delta mechanism is unknown. This exercises
 * every remaining candidate against the live server and dumps each raw response to
 * `cacheDir/drive_probe_d3/`.
 *
 * **Temporary.** Delete once the findings are archived into `docs/REFERENCE.md` §5.8.
 *
 * Run WITHOUT uninstalling (keeps the logged-in session alive):
 * ```
 * ./gradlew :app:installDebug :app:installDebugAndroidTest
 * adb shell am instrument -w \
 *   -e class com.getup.iClouDroid.probe.DriveDeltaProbeTest \
 *   com.getup.iClouDroid.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DriveDeltaProbeTest {
    private val TAG = "DriveDeltaProbe"

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun probeDeltaCandidates() {
        val sessionManager = SessionManager(context)
        assertTrue(
            "No persisted session — log in through the app first",
            sessionManager.isLoggedIn()
        )

        val cookies = sessionManager.getCookieString().orEmpty()
        val init = JSONObject(Icloudcore.initSession(cookies, sessionManager.isChinaMainland()))
        assertTrue("initSession failed: $init", init.optBoolean("success", false))
        Log.i(TAG, "drivews=${init.optString("drivewsUrl")}")

        val outDir = File(context.cacheDir, "drive_probe_d3").apply { mkdirs() }
        val summary = Icloudcore.probeDriveDeltaCandidates(outDir.absolutePath)
        File(outDir, "_summary.json").writeText(summary)

        val json = JSONObject(summary)
        assertTrue("probe failed: $summary", json.optBoolean("success", false))

        val steps = json.optJSONArray("steps")
        for (i in 0 until (steps?.length() ?: 0)) {
            val s = steps!!.optJSONObject(i)
            Log.i(
                TAG,
                "[${s.optString("step")}] ${s.optString("method")} status=${s.optInt("status")} " +
                    "bytes=${s.optInt("bytes")} ok=${s.optBoolean("ok")} | ${s.optString("note")}",
            )
            val head = s.optString("head")
            if (head.isNotEmpty()) {
                Log.i(TAG, "   body=${head.replace("\n", " ").take(600)}")
            }
        }
    }
}
