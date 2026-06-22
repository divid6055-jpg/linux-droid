package com.linuxdroid.commands

import android.content.Context
import java.io.InputStream
import java.io.OutputStream

/**
 * سياق تنفيذ الأمر — يحمل كل ما يحتاجه الأمر ليعمل.
 *
 * تمرير سياق منفصل لكل أمر يتيح:
 *  - الأنابيب: كل أمر في السلسلة له stdout مختلف
 *  - التوجيه: stdin/stdout/stderr يمكن إعادة توجيهها
 *  - متغيرات بيئة مستقلة (عند الحاجة)
 *  - معرفة مجلد العمل الحالي
 *
 * ملاحظة: workingDirectory قابل للتعديل (cd يؤثر عليه)
 */
data class ShellContext(
    val context: Context,
    val environment: MutableMap<String, String>,
    var workingDirectory: String,
    var stdin: InputStream,
    var stdout: OutputStream,
    var stderr: OutputStream,
    val sessionId: String,
    var columns: Int = 80,
    var rows: Int = 24,
    /** انتهى الأمر بهذا الكود */
    var lastExitCode: Int = 0
) {
    fun write(s: String) {
        try { stdout.write(s.toByteArray(Charsets.UTF_8)); stdout.flush() } catch (_: Exception) {}
    }
    fun writeError(s: String) {
        try { stderr.write(s.toByteArray(Charsets.UTF_8)); stderr.flush() } catch (_: Exception) {}
    }
    fun writeln(s: String) = write(s + "\r\n")
    fun writeErrln(s: String) = writeError(s + "\r\n")
    fun readAll(): String = stdin.bufferedReader(Charsets.UTF_8).use { it.readText() }
    fun readLines(): List<String> = stdin.bufferedReader(Charsets.UTF_8).use { it.readLines() }

    /**
     * SECURITY: يحلّ المسار بأمان داخل صندوق الرمل.
     *
     * @param path المسار النسبي أو المطلق
     * @return الملف المُحلَّل أو null إذا كان ممنوعاً
     */
    fun resolveSafe(path: String): java.io.File? =
        com.linuxdroid.security.SecurityUtils.resolveSafe(path, workingDirectory)

    /**
     * SECURITY: يحلّ المسار بأمان للكتابة (أكثر تقييداً).
     */
    fun resolveSafeForWrite(path: String): java.io.File? =
        com.linuxdroid.security.SecurityUtils.resolveSafeForWrite(path, workingDirectory)
}
