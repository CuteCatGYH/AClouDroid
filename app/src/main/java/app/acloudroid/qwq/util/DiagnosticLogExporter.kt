package app.acloudroid.qwq.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticLogExporter {

    suspend fun createDiagnosticZip(context: Context): File = withContext(Dispatchers.IO) {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fullDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val now = Date()
        val timestampStr = dateFormat.format(now)

        val logDir = File(context.cacheDir, "logs").apply { if (!exists()) mkdirs() }
        cleanStaleExportZips(context, maxAgeDays = 3)
        val zipFile = File(logDir, "iClouDroid_Diagnostic_$timestampStr.zip")

        // 1. Device Info Content
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()

        val versionName = packageInfo?.versionName ?: "Unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong() ?: 0L
        }

        val deviceInfoText = buildString {
            appendLine("================================================================")
            appendLine("iClouDroid Diagnostic Information")
            appendLine("================================================================")
            appendLine("Export Timestamp : ${fullDateFormat.format(now)}")
            appendLine("Package Name     : ${context.packageName}")
            appendLine("Version Name     : $versionName")
            appendLine("Version Code     : $versionCode")
            appendLine("Android OS       : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device Model     : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Brand            : ${Build.BRAND}")
            appendLine("Build Fingerprint: ${Build.FINGERPRINT}")
            appendLine("================================================================")
        }

        // 2. Behavior Log File
        val behaviorFile = AppLogger.getAppBehaviorFile(context)

        // 3. Logcat Content
        val logcatContent = LogcatCapture.captureLogcat()

        // Create Zip Output Stream
        ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
            // Add device_info.txt
            zipOut.putNextEntry(ZipEntry("device_info.txt"))
            zipOut.write(deviceInfoText.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // Add app_behavior.log
            zipOut.putNextEntry(ZipEntry("app_behavior.log"))
            if (behaviorFile.exists()) {
                FileInputStream(behaviorFile).use { input ->
                    input.copyTo(zipOut)
                }
            } else {
                zipOut.write("No behavior logs available.\n".toByteArray(Charsets.UTF_8))
            }
            zipOut.closeEntry()

            // Add logcat.log
            zipOut.putNextEntry(ZipEntry("logcat.log"))
            zipOut.write(logcatContent.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()
        }

        zipFile
    }

    suspend fun exportAndShareDiagnosticZip(context: Context) {
        val zipFile = createDiagnosticZip(context)
        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, zipFile)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "iClouDroid 诊断日志 (${zipFile.name})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooserIntent = Intent.createChooser(shareIntent, "导出诊断日志压缩包").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(chooserIntent)
    }

    private fun cleanStaleExportZips(context: Context, maxAgeDays: Int = 3) {
        try {
            val logDir = File(context.cacheDir, "logs")
            if (!logDir.exists()) return
            val threshold = System.currentTimeMillis() - maxAgeDays * 24 * 60 * 60 * 1000L
            logDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".zip") && file.lastModified() < threshold) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("DiagnosticLogExporter", "Failed to clean stale zips: ${e.message}")
        }
    }

    fun clearAllLogs(context: Context) {
        AppLogger.clearLogs(context)
        LogcatCapture.clearLogcat()
        // Clean up cache log folder
        File(context.cacheDir, "logs").let { dir ->
            if (dir.exists()) {
                dir.listFiles()?.forEach { file -> file.delete() }
            }
        }
    }
}
