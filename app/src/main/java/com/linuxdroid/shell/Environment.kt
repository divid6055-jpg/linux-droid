package com.linuxdroid.shell

import android.content.Context
import com.linuxdroid.util.LinuxDroidLogger
import java.io.File

/**
 * بيئة shell — متغيرات النظام والمستخدم.
 *
 * تنشئ بنية مجلدات شبيهة بـ Linux داخل مساحة التطبيق الخاصة:
 *   /data/data/com.linuxdroid/files/
 *     usr/                 ← نظام الملفات الجذري الافتراضي
 *       bin/               ← الأوامر الثنائية (رمزية)
 *       lib/               ← المكتبات
 *       share/             ← بيانات مشتركة
 *       etc/               ← إعدادات
 *     home/                ← $HOME للمستخدم
 *       .config/           ← إعدادات XDG
 *       .cache/            ← مخبأ
 *       .local/share/      ← بيانات محلية
 *     tmp/                 ← ملفات مؤقتة
 *     var/                 ← بيانات متغيرة
 *       log/
 *       run/
 *
 * ملاحظة أمنية: لا نستخدم /sdcard أو التخزين المشترك بدون إذن صريح.
 */
object Environment {

    private const val TAG = "Environment"

    lateinit var rootDir: File     // مسار الجذر الافتراضي (usr/)
        private set
    lateinit var homeDir: File     // $HOME
        private set
    lateinit var tmpDir: File      // $TMPDIR
        private set
    lateinit var varDir: File      // /var
        private set
    lateinit var etcDir: File      // /etc
        private set
    lateinit var binDir: File      // /usr/bin (نظرياً، نستخدم الأوامر المدمجة)
        private set

    /** متغيرات البيئة الافتراضية */
    val defaultEnv: MutableMap<String, String> = mutableMapOf()

    fun initialize(context: Context) {
        val base = context.filesDir
        rootDir = File(base, "usr").apply { mkdirs() }
        homeDir = File(base, "home").apply { mkdirs() }
        tmpDir  = File(base, "tmp").apply { mkdirs() }
        varDir  = File(base, "var").apply { mkdirs() }
        etcDir  = File(rootDir, "etc").apply { mkdirs() }
        binDir  = File(rootDir, "bin").apply { mkdirs() }

        // بنية فرعية
        File(rootDir, "lib").mkdirs()
        File(rootDir, "share").mkdirs()
        File(homeDir, ".config").mkdirs()
        File(homeDir, ".cache").mkdirs()
        File(homeDir, ".local/share").mkdirs()
        File(varDir, "log").mkdirs()
        File(varDir, "run").mkdirs()

        // متغيرات البيئة
        defaultEnv.clear()
        defaultEnv["HOME"] = homeDir.absolutePath
        defaultEnv["TMPDIR"] = tmpDir.absolutePath
        defaultEnv["PATH"] = "${binDir.absolutePath}:/system/bin:/system/xbin"
        defaultEnv["USER"] = userName()
        defaultEnv["LOGNAME"] = userName()
        defaultEnv["SHELL"] = "linuxdroid-sh"
        defaultEnv["TERM"] = "xterm-256color"
        defaultEnv["LANG"] = "en_US.UTF-8"
        defaultEnv["LC_ALL"] = "en_US.UTF-8"
        defaultEnv["PWD"] = homeDir.absolutePath
        defaultEnv["OLDPWD"] = homeDir.absolutePath
        defaultEnv["HOSTNAME"] = "linuxdroid"
        defaultEnv["ANDROID_ROOT"] = "/system"
        defaultEnv["ANDROID_DATA"] = "/data"
        defaultEnv["BOOTCLASSPATH"] = System.getenv("BOOTCLASSPATH") ?: ""
        defaultEnv["LINUXDROID_VERSION"] = "1.0.0"
        defaultEnv["LINUXDROID_HOME"] = rootDir.absolutePath

        // كتابة /etc/passwd و /etc/group (شكلي فقط)
        writeEtcFiles()

        LinuxDroidLogger.i(TAG, "Environment ready. HOME=${homeDir.absolutePath}")
    }

    private fun writeEtcFiles() {
        File(etcDir, "passwd").writeText(
            "root:x:0:0:root:${homeDir.absolutePath}:/bin/sh\n" +
            "${userName()}:x:1000:1000:LinuxDroid User:${homeDir.absolutePath}:/bin/sh\n"
        )
        File(etcDir, "group").writeText(
            "root:x:0:\n" +
            "users:x:1000:${userName()}\n" +
            "wheel:x:10:${userName()}\n"
        )
        File(etcDir, "hostname").writeText("linuxdroid\n")
        File(etcDir, "os-release").writeText(
            """NAME="LinuxDroid"
VERSION="1.0.0 (Aether)"
ID=linuxdroid
ID_LIKE=linux
PRETTY_NAME="LinuxDroid 1.0.0 (Aether)"
VERSION_ID="1.0"
HOME_URL="https://github.com/divid6055-jpg/linux-droid"
SUPPORT_URL="https://github.com/divid6055-jpg/linux-droid/issues"
""".trimIndent() + "\n"
        )
        File(etcDir, "motd").writeText(
            """
            ╔══════════════════════════════════════════╗
            ║         LinuxDroid v1.0.0 (Aether)        ║
            ║   Lightweight Linux environment for       ║
            ║              Android                     ║
            ╚══════════════════════════════════════════╝

            Welcome! Type 'help' to see available commands.
            Type 'pkg list' to see installable packages.
        """.trimIndent() + "\n"
        )
    }

    fun userName(): String = "linuxdroid"

    /** تنفيذ وحلّ متغير بيئة في سلسلة (مثل $HOME → /path) */
    fun expand(input: String, env: Map<String, String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '$' && i + 1 < input.length) {
                val next = input[i + 1]
                if (next == '{') {
                    val end = input.indexOf('}', i + 2)
                    if (end > 0) {
                        val name = input.substring(i + 2, end)
                        sb.append(env[name] ?: "")
                        i = end + 1
                        continue
                    }
                } else if (next == '$') {
                    sb.append(System.currentTimeMillis().toString())
                    i += 2; continue
                } else if (next == '?') {
                    sb.append(env["?"] ?: "0")
                    i += 2; continue
                } else if (next.isLetterOrDigit() || next == '_') {
                    var j = i + 1
                    while (j < input.length && (input[j].isLetterOrDigit() || input[j] == '_')) j++
                    val name = input.substring(i + 1, j)
                    sb.append(env[name] ?: "")
                    i = j; continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
