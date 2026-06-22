package com.linuxdroid.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * نظام تسجيل موحّد للتطبيق.
 *
 * الميزات:
 * - تسجيل إلى logcat (للتطوير)
 * - تسجيل إلى ملف دوار (للتشخيص بعد الإغلاق)
 * - مستويات منفصلة (VERBOSE/DEBUG/INFO/WARN/ERROR)
 * - حد أقصى لحجم الملف لمنع امتلاء التخزين
 *
 * الأمان: لا نسجّل أبداً كلمات المرور أو المفاتيح أو البيانات الحساسة.
 */
object LinuxDroidLogger {

    private const val MAX_LOG_SIZE = 512 * 1024L // 512 KB
    private const val MAX_LOG_FILES = 3

    private lateinit var logFile: File
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        logFile = File(context.filesDir, "logs/linuxdroid.log")
        logFile.parentFile?.mkdirs()
        rotateIfNeeded()
        i(TAG, "=== LinuxDroid logger initialized ===")
    }

    fun v(tag: String, msg: String) = write('V', tag, msg, null)
    fun d(tag: String, msg: String) = write('D', tag, msg, null)
    fun i(tag: String, msg: String) = write('I', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write('E', tag, msg, t)

    private fun write(level: Char, tag: String, msg: String, t: Throwable?) {
        // SECURITY: فلترة الأسرار من الـ log لمنع تسرّبها
        val safeMsg = com.linuxdroid.security.SecurityUtils.sanitizeLogMessage(msg)

        // logcat
        when (level) {
            'V' -> Log.v(tag, safeMsg)
            'D' -> Log.d(tag, safeMsg)
            'I' -> Log.i(tag, safeMsg)
            'W' -> Log.w(tag, safeMsg, t)
            'E' -> Log.e(tag, safeMsg, t)
        }

        // file (synchronized to avoid interleaving from multiple threads)
        synchronized(this) {
            try {
                val ts = dateFormat.format(Date())
                val line = "$ts $level/$tag: $safeMsg\n" +
                    (t?.let { "\n" + stackTraceToString(it) } ?: "")
                logFile.appendText(line)
                rotateIfNeeded()
            } catch (_: Exception) {
                // لا يمكننا حتى التسجيل — تجاهل بهدوء
            }
        }
    }

    private fun stackTraceToString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists()) return
        if (logFile.length() < MAX_LOG_SIZE) return
        // rotate: .log -> .1 -> .2 -> delete .3
        for (i in (MAX_LOG_FILES - 1) downTo 1) {
            val older = File(logFile.parentFile, "linuxdroid.log.$i")
            val newer = File(logFile.parentFile, if (i == 1) "linuxdroid.log" else "linuxdroid.log.${i - 1}")
            if (newer.exists()) {
                older.delete()
                newer.renameTo(older)
            }
        }
        logFile.writeText("")
    }

    fun getLogContent(): String = if (logFile.exists()) logFile.readText() else ""

    private const val TAG = "LinuxDroidLogger"
}
