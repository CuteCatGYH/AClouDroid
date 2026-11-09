package app.acloudroid.qwq.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Thread-safe logger that outputs both to android.util.Log and a persistent local log file.
 * All entries are formatted with a precise timestamp: yyyy-MM-dd HH:mm:ss.SSS.
 */
object AppLogger {
    private const val MAX_LOG_FILE_SIZE_BYTES = 2 * 1024 * 1024L // 2MB limit per file

    @Volatile
    private var appContext: Context? = null

    private val executor = Executors.newSingleThreadExecutor()

    private val dateFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        appContext = context.applicationContext
        i("AppLogger", "AppLogger initialized")
    }

    fun v(tag: String, message: String) {
        Log.v(tag, message)
        appendLog("VERBOSE", tag, message, null)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        appendLog("DEBUG", tag, message, null)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        appendLog("INFO", tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
        appendLog("WARN", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        appendLog("ERROR", tag, message, throwable)
    }

    private fun appendLog(level: String, tag: String, message: String, throwable: Throwable?) {
        val ctx = appContext ?: return
        val timestamp = dateFormat.format(Date())

        executor.execute {
            try {
                val logDir = File(ctx.filesDir, "logs").apply { if (!exists()) mkdirs() }
                val logFile = File(logDir, "app_behavior.log")

                // Simple size rotation
                if (logFile.exists() && logFile.length() > MAX_LOG_FILE_SIZE_BYTES) {
                    val oldFile = File(logDir, "app_behavior.old.log")
                    if (oldFile.exists()) oldFile.delete()
                    logFile.renameTo(oldFile)
                }

                FileWriter(logFile, true).use { writer ->
                    writer.append("$timestamp [$level] [$tag] $message\n")
                    throwable?.let { t ->
                        val printWriter = PrintWriter(writer)
                        t.printStackTrace(printWriter)
                        printWriter.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e("AppLogger", "Failed to write log to file: ${e.message}")
            }
        }
    }

    fun getAppBehaviorFile(context: Context): File {
        val logDir = File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }
        val targetFile = File(logDir, "app_behavior.log")
        val oldFile = File(logDir, "app_behavior.old.log")

        if (oldFile.exists() && oldFile.length() > 0) {
            // Combine old file and current file for export if available
            val combinedFile = File(context.cacheDir, "app_behavior_combined.log")
            try {
                combinedFile.writeText(oldFile.readText() + "\n" + (if (targetFile.exists()) targetFile.readText() else ""))
                return combinedFile
            } catch (e: Exception) {
                Log.w("AppLogger", "Failed to combine old log files: ${e.message}")
            }
        }

        if (!targetFile.exists()) {
            targetFile.createNewFile()
        }
        return targetFile
    }

    fun clearLogs(context: Context) {
        executor.execute {
            try {
                val logDir = File(context.filesDir, "logs")
                if (logDir.exists()) {
                    logDir.listFiles()?.forEach { file -> file.delete() }
                }
                val combinedFile = File(context.cacheDir, "app_behavior_combined.log")
                if (combinedFile.exists()) {
                    combinedFile.delete()
                }
            } catch (e: Exception) {
                Log.e("AppLogger", "Failed to clear log files: ${e.message}")
            }
        }
    }
}
