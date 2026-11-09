package app.acloudroid.qwq.util

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

object LogcatCapture {

    private val threadtimePattern = Pattern.compile("^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(.*)$")

    fun captureLogcat(): String {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val defaultDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()

        try {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime").start()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val matcher = threadtimePattern.matcher(line)
                    if (matcher.matches()) {
                        val mmddTime = matcher.group(1)
                        val rest = matcher.group(2)
                        // Converts "03-30 14:22:01.456" -> "2026-03-30 14:22:01.456"
                        sb.append("$currentYear-$mmddTime $rest\n")
                    } else {
                        val timestamp = defaultDateFormat.format(Date())
                        sb.append("$timestamp $line\n")
                    }
                    line = reader.readLine()
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            Log.e("LogcatCapture", "Failed to capture logcat: ${e.message}", e)
            sb.append("${defaultDateFormat.format(Date())} [ERROR] Failed to capture logcat: ${e.message}\n")
        }

        return sb.toString()
    }

    fun clearLogcat() {
        try {
            val process = ProcessBuilder("logcat", "-c").start()
            process.waitFor()
            Log.i("LogcatCapture", "Logcat buffer cleared")
        } catch (e: Exception) {
            Log.e("LogcatCapture", "Failed to clear logcat buffer: ${e.message}", e)
        }
    }
}
