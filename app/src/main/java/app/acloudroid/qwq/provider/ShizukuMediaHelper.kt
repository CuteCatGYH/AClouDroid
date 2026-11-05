package app.acloudroid.qwq.provider

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Helper to interact with Shizuku for registering iClouDroid as an allowed CloudMediaProvider.
 */
object ShizukuMediaHelper {

    const val ADB_COMMAND = "adb shell \"device_config set_sync_disabled_for_tests persistent && device_config override mediaprovider allowed_cloud_providers app.acloudroid.qwq,com.google.android.apps.photos && device_config override storage_native_boot allowed_cloud_providers app.acloudroid.qwq,com.google.android.apps.photos && content call --uri content://media/ --method set_cloud_provider --extra cloud_provider:s:app.acloudroid.qwq.cloudmedia\""

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    fun requestShizukuPermission(requestCode: Int = 1001) {
        try {
            if (isShizukuAvailable()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Throwable) {
            // Ignored
        }
    }

    suspend fun executeActivation(): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isShizukuAvailable()) {
                return@withContext Result.failure(IllegalStateException("Shizuku 服务未运行"))
            }
            if (!hasShizukuPermission()) {
                return@withContext Result.failure(IllegalStateException("未授予 Shizuku 权限"))
            }

            // Using reflection to invoke Shizuku.newProcess (standard way for shell exec in Shizuku)
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true

            val shellCmd = arrayOf(
                "sh",
                "-c",
                "device_config set_sync_disabled_for_tests persistent && " +
                "device_config override mediaprovider allowed_cloud_providers app.acloudroid.qwq,com.google.android.apps.photos && " +
                "device_config override storage_native_boot allowed_cloud_providers app.acloudroid.qwq,com.google.android.apps.photos && " +
                "content call --uri content://media/ --method set_cloud_provider --extra cloud_provider:s:app.acloudroid.qwq.cloudmedia"
            )

            val process = method.invoke(null, shellCmd, null, null) as Process
            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.appendLine(line)
                }
            }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Result.success("激活成功！系统云端媒体已绑定 AClouDroid。")
            } else {
                Result.failure(RuntimeException("执行失败 (exitCode=$exitCode): $output"))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
