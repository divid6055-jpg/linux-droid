package com.linuxdroid.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.linuxdroid.util.LinuxDroidLogger

/**
 * مدير الأمان المركزي للتطبيق.
 *
 * المسؤوليات:
 * 1. كشف بيئة التصحيح (debugger attached)
 * 2. كشف الجذر (root) — للإبلاغ فقط، لا نرفض التشغيل
 * 3. كشف المحاكاة (emulator) — للإبلاغ فقط
 * 4. التحقق من توقيع التطبيق
 * 5. تزويد المكونات الأخرى بمعلومات الثقة
 *
 * القرارات:
 * - لا نرفض التشغيل أبداً (المستخدم حر في جهازه)
 * - نسجّل تنبيهات للأحداث المريبة
 * - نعرض تحذيرات في الواجهة فقط
 */
object SecurityManager {

    private const val TAG = "SecurityManager"

    @Volatile
    private var isDebuggable = false
    @Volatile
    private var isRooted = false
    @Volatile
    private var isEmulator = false
    @Volatile
    private var signatureHash: String? = null

    fun initialize(context: Context) {
        isDebuggable = detectDebuggable(context)
        isRooted = detectRoot()
        isEmulator = detectEmulator()
        signatureHash = computeSignatureHash(context)

        LinuxDroidLogger.i(TAG, "Security scan complete: " +
            "debuggable=$isDebuggable, rooted=$isRooted, " +
            "emulator=$isEmulator, sig=${signatureHash?.take(8)}…")

        if (isDebuggable) {
            LinuxDroidLogger.w(TAG, "App is running in debuggable mode — sensitive logs are enabled.")
        }
        if (isRooted) {
            LinuxDroidLogger.w(TAG, "Root detected on device. Some operations may behave differently.")
        }
    }

    fun isDebuggable(): Boolean = isDebuggable
    fun isRooted(): Boolean = isRooted
    fun isEmulator(): Boolean = isEmulator
    fun signatureHash(): String? = signatureHash

    private fun detectDebuggable(context: Context): Boolean {
        return try {
            val flags = context.applicationInfo.flags
            (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            LinuxDroidLogger.w(TAG, "Failed to check debuggable flag: ${e.message}")
            false
        }
    }

    private fun detectRoot(): Boolean {
        val indicators = listOf(
            "/system/app/Superuser.apk",
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/su", "/su/bin/su",
            "/magisk/.core/bin/su", "/system/app/MagiskManager.apk"
        )
        return indicators.any { java.io.File(it).exists() }
    }

    private fun detectEmulator(): Boolean {
        val props = mapOf(
            "ro.kernel.qemu" to "1",
            "ro.product.model" to "sdk",
            "ro.product.brand" to "generic",
            "ro.hardware" to "goldfish",
            "ro.hardware" to "ranchu"
        )
        // can't read build props without native code, use Build.*
        val isEmuByBuild = (Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER == "Genymotion" ||
            Build.BRAND.startsWith("generic") ||
            Build.DEVICE.startsWith("generic") ||
            Build.PRODUCT == "google_sdk")
        return isEmuByBuild
    }

    private fun computeSignatureHash(context: Context): String? {
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val sigs = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            if (sigs.isNullOrEmpty()) return null
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(sigs[0].toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            LinuxDroidLogger.w(TAG, "Failed to compute signature: ${e.message}")
            null
        }
    }
}
