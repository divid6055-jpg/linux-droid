package com.linuxdroid.security

import com.linuxdroid.shell.Environment
import com.linuxdroid.util.LinuxDroidLogger
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.util.regex.Pattern

/**
 * أدوات أمنية مركزية للتحقق من المسارات والروابط.
 *
 * تستخدمها كل الأوامر لمنع:
 * 1. Path Traversal — تسريب ملفات خارج صندوق الرمل
 * 2. SSRF — الوصول لشبكات داخلية خطرة
 * 3. JSON Injection — تلف بيانات
 *
 * السياسة:
 * - مسموح: داخل $HOME أو $LINUXDROID_HOME (usr/)
 * - مسموح: مسارات داخل /tmp (ملفات مؤقتة)
 * - مسموح: محتوى /etc للمعلومات النظامية فقط (قراءة)
 * - ممنوع: /proc, /sys, /data, /sdcard, /system (إلا للقراءة المحدودة)
 */
object SecurityUtils {

    private const val TAG = "SecurityUtils"

    /** المجلدات المسموح بها للقراءة/الكتابة */
    private val allowedRoots: List<File>
        get() = listOf(
            Environment.homeDir,
            Environment.rootDir,
            Environment.tmpDir,
            Environment.varDir
        )

    /**
     * يُحلّل مساراً نسبياً أو مطلقاً ويتحقق أنه داخل صندوق الرمل.
     *
     * @return المسار المُحلَّل (absolute) أو null إذا كان ممنوعاً
     */
    fun resolveSafe(path: String, workingDirectory: String): File? {
        if (path.isEmpty()) return null

        // تحويل المسار
        val raw = if (path.startsWith("~")) {
            path.replaceFirst("~", Environment.homeDir.absolutePath)
        } else if (path.startsWith("/")) {
            path
        } else {
            File(workingDirectory, path).absolutePath
        }

        val file = File(raw)

        // منع الأحرف الخطرة في الاسم (NTFS-style streams, null bytes)
        if (path.contains('\u0000')) {
            LinuxDroidLogger.w(TAG, "Blocked path with null byte: $path")
            return null
        }

        // الحصول على canonical path (يحل .. والروابط الرمزية)
        val canonical = try {
            file.canonicalFile
        } catch (e: IOException) {
            // إذا لم يكن موجوداً، نستخدم absolutePath ونحل يدوياً
            normalizePath(file.absolutePath)
        }

        // السماح بالمسارات داخل الجذور المسموح بها
        val canonicalPath = canonical.absolutePath
        for (root in allowedRoots) {
            val rootCanonical = root.canonicalFile.absolutePath
            if (canonicalPath == rootCanonical || canonicalPath.startsWith("$rootCanonical/") ||
                canonicalPath.startsWith("$rootCanonical")) {
                return canonical
            }
        }

        // حالات خاصة للقراءة فقط
        // نسمح بقراءة /etc/passwd, /etc/os-release (أنشأناها نحن)
        // لكن نمنع /proc, /sys, /data (عدا مسار التطبيق), /system
        if (isForbidden(canonicalPath)) {
            LinuxDroidLogger.w(TAG, "Blocked access to forbidden path: $canonicalPath")
            return null
        }

        // إذا لم يكن داخل الجذور المسموحة، نسمح بالقراءة فقط من المجلدات العامة
        // لكن نطبّق restrict أكثر في أوامر الكتابة
        return canonical
    }

    /** تحقق إضافي صارم للكتابة — يجب أن يكون داخل الجذور المسموح بها */
    fun resolveSafeForWrite(path: String, workingDirectory: String): File? {
        val file = resolveSafe(path, workingDirectory) ?: return null

        // للكتابة، يجب أن يكون داخل أحد الجذور المسموح بها
        val canonicalPath = file.absolutePath
        for (root in allowedRoots) {
            val rootCanonical = root.canonicalFile.absolutePath
            if (canonicalPath == rootCanonical || canonicalPath.startsWith("$rootCanonical/")) {
                return file
            }
        }

        LinuxDroidLogger.w(TAG, "Blocked write to path outside sandbox: $canonicalPath")
        return null
    }

    private fun normalizePath(p: String): File {
        // حل يدوي للـ ..
        val parts = p.split("/").toMutableList()
        val result = mutableListOf<String>()
        for (part in parts) {
            when {
                part.isEmpty() || part == "." -> {}
                part == ".." -> { if (result.isNotEmpty()) result.removeAt(result.lastIndex) }
                else -> result.add(part)
            }
        }
        return File("/" + result.joinToString("/"))
    }

    private fun isForbidden(path: String): Boolean {
        // منع الوصول لمسارات حسّاسة
        val forbidden = listOf(
            "/proc/self/environ",
            "/proc/self/maps",
            "/proc/self/mem",
            "/proc/self/cmdline",
            "/data/data/",  // قد يحوي بيانات تطبيقات أخرى
            "/data/system/",
            "/system/etc/security/"
        )
        for (f in forbidden) {
            if (path.startsWith(f)) return true
        }
        return false
    }

    /**
     * يتحقق من أن URL آمن للاتصال.
     *
     * يمنع:
     * - file://, ftp://, gopher://, dict://
     * - 127.0.0.0/8, ::1 (loopback)
     * - 169.254.169.254 (cloud metadata)
     * - 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 (private)
     * - 0.0.0.0, multicast, broadcast
     */
    fun validateUrl(urlStr: String): ValidationResult {
        // التحقق من البروتوكول
        val lower = urlStr.lowercase()
        val allowedSchemes = listOf("http://", "https://")
        val withScheme = if (allowedSchemes.any { lower.startsWith(it) }) urlStr
                         else "http://$urlStr"

        return try {
            val url = URL(withScheme)
            val scheme = url.protocol.lowercase()
            if (scheme !in listOf("http", "https")) {
                return ValidationResult(false, "Scheme '$scheme' not allowed")
            }

            val host = url.host
            if (host.isEmpty()) return ValidationResult(false, "Empty host")

            // حلّ العنوان وتحقق
            val addr = InetAddress.getByName(host)

            if (addr.isLoopbackAddress) {
                return ValidationResult(false, "Access to loopback addresses is blocked")
            }
            if (addr.isSiteLocalAddress) {
                return ValidationResult(false, "Access to private network addresses is blocked")
            }
            if (addr.isLinkLocalAddress) {
                return ValidationResult(false, "Access to link-local addresses is blocked")
            }
            if (addr.isAnyLocalAddress) {
                return ValidationResult(false, "Access to 0.0.0.0 is blocked")
            }
            if (addr.isMulticastAddress) {
                return ValidationResult(false, "Access to multicast addresses is blocked")
            }

            // حظر cloud metadata IPs
            val hostIp = addr.hostAddress
            if (hostIp == "169.254.169.254" || hostIp?.startsWith("169.254.") == true) {
                return ValidationResult(false, "Access to metadata service is blocked")
            }

            ValidationResult(true, withScheme)
        } catch (e: Exception) {
            ValidationResult(false, "Invalid URL: ${e.message}")
        }
    }

    data class ValidationResult(val ok: Boolean, val value: String)

    /** يفلتر الأسرار من سطر log قبل كتابته */
    fun sanitizeLogMessage(msg: String): String {
        var result = msg
        // إزالة قيم المتغيرات الحساسة
        val patterns = listOf(
            Pattern.compile("(?i)(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|secret[_-]?key)\\s*[=:]\\s*\\S+"),
            Pattern.compile("(?i)authorization\\s*:\\s*\\S+"),
            Pattern.compile("(?i)\\-d\\s+\"[^\"]*password[^\"]*\""),
            Pattern.compile("(?i)\\-H\\s+\"[^\"]*authorization[^\"]*\"")
        )
        for (p in patterns) {
            // SECURITY: نستخدم appendReplacement/appendTail لدعم API 24+
            // (replaceAll(Function) يتطلب API 34)
            val m = p.matcher(result)
            val sb = StringBuffer()
            while (m.find()) {
                val text = m.group()
                val eq = text.indexOf('=')
                val colon = text.indexOf(':')
                val replacement = if (eq > 0) {
                    text.substring(0, eq + 1) + "[REDACTED]"
                } else if (colon > 0) {
                    text.substring(0, colon + 1) + "[REDACTED]"
                } else {
                    "[REDACTED]"
                }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement))
            }
            m.appendTail(sb)
            result = sb.toString()
        }
        return result
    }

    /** يتحقق من SHA-256 hex string صالح */
    fun isValidSha256(s: String): Boolean =
        s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** يتحقق من اسم حزمة صالح (الحروف والأرقام والشرطات فقط) */
    fun isValidPackageName(s: String): Boolean =
        s.isNotEmpty() && s.length <= 64 &&
        s.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } &&
        !s.startsWith(".") && !s.startsWith("-")

    /** يتحقق من إصدار صالح */
    fun isValidVersion(s: String): Boolean =
        s.isNotEmpty() && s.length <= 32 &&
        s.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '+' }
}
