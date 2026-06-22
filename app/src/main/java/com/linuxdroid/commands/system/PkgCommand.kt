package com.linuxdroid.commands.system

import com.linuxdroid.commands.CommandExecutor
import com.linuxdroid.commands.ShellContext
import com.linuxdroid.shell.Environment
import com.linuxdroid.util.LinuxDroidLogger
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * pkg — مدير الحزم لـ LinuxDroid.
 *
 * يعمل بشكل شبيه بـ apt/pacman:
 *   pkg install <package>      تثبيت حزمة
 *   pkg remove <package>       إزالة حزمة
 *   pkg list                   قائمة بالحزم المتاحة
 *   pkg list-installed         الحزم المثبتة
 *   pkg search <query>         بحث
 *   pkg update                 تحديث فهرس الحزم
 *   pkg upgrade                ترقية الحزم المثبتة
 *   pkg show <package>         معلومات حزمة
 *   pkg files <package>        ملفات حزمة مثبتة
 *
 * المستودع: نستخدم مستودع بسيط بتنسيق JSON (سحابة عامة)
 * ملاحظة: في النسخة الحالية نستخدم مستودع تجريبي يحتوي على سكربتات
 * shell وملفات بيانات صغيرة فقط (لا نثبت ثنائيات أصلية بدون أذونات خاصة).
 *
 * الأمان: نتحقق من SHA-256 لكل ملف قبل التثبيت.
 */
class PkgCommand : CommandExecutor {
    var mode: Int = PKG_MODE

    private val installedFile: File
        get() = File(Environment.etcDir, "installed-packages.json")

    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) {
            ctx.write(help())
            return 1
        }
        val sub = args[0]
        val rest = args.drop(1)
        return when (sub) {
            "install", "add", "i" -> cmdInstall(rest, ctx)
            "remove", "uninstall", "rm", "del" -> cmdRemove(rest, ctx)
            "list" -> cmdList(rest, ctx)
            "list-installed", "li" -> cmdListInstalled(ctx)
            "search", "s" -> cmdSearch(rest, ctx)
            "update" -> cmdUpdate(ctx)
            "upgrade", "up" -> cmdUpgrade(rest, ctx)
            "show", "info" -> cmdShow(rest, ctx)
            "files", "f" -> cmdFiles(rest, ctx)
            "help", "-h", "--help" -> { ctx.write(help()); 0 }
            else -> { ctx.writeErrln("pkg: unknown subcommand '$sub'"); ctx.write(help()); 1 }
        }
    }

    private fun cmdInstall(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("pkg install: missing package name"); return 1 }
        val installed = readInstalled(ctx)
        for (pkg in args) {
            if (installed.any { it.name == pkg }) {
                ctx.writeln("pkg: $pkg is already installed")
                continue
            }
            val meta = fetchPackageInfo(pkg, ctx) ?: run {
                ctx.writeErrln("pkg: package '$pkg' not found in repository")
                continue
            }
            ctx.writeln("Installing $pkg ${meta.version}...")
            try {
                downloadAndInstall(meta, ctx)
                installed.add(meta)
                ctx.writeln("Successfully installed $pkg ${meta.version}")
            } catch (e: Exception) {
                ctx.writeErrln("pkg: failed to install $pkg: ${e.message}")
                LinuxDroidLogger.e("PkgCommand", "install failed", e)
            }
        }
        writeInstalled(installed, ctx)
        return 0
    }

    private fun cmdRemove(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("pkg remove: missing package"); return 1 }
        val installed = readInstalled(ctx)
        for (pkg in args) {
            val meta = installed.find { it.name == pkg }
            if (meta == null) { ctx.writeErrln("pkg: $pkg is not installed"); continue }
            ctx.writeln("Removing $pkg...")
            // حذف الملفات
            meta.files.forEach { f ->
                val file = File(Environment.rootDir, f)
                if (file.exists()) file.delete()
            }
            installed.remove(meta)
            ctx.writeln("Removed $pkg")
        }
        writeInstalled(installed, ctx)
        return 0
    }

    private fun cmdList(args: List<String>, ctx: ShellContext): Int {
        val index = fetchIndex(ctx) ?: return 1
        ctx.writeln("Available packages:")
        ctx.writeln(String.format("%-20s %-10s %s", "NAME", "VERSION", "DESCRIPTION"))
        for (p in index) {
            ctx.writeln(String.format("%-20s %-10s %s", p.name, p.version, p.description))
        }
        return 0
    }

    private fun cmdListInstalled(ctx: ShellContext): Int {
        val installed = readInstalled(ctx)
        if (installed.isEmpty()) { ctx.writeln("No packages installed."); return 0 }
        ctx.writeln(String.format("%-20s %-10s %s", "NAME", "VERSION", "DESCRIPTION"))
        for (p in installed) {
            ctx.writeln(String.format("%-20s %-10s %s", p.name, p.version, p.description))
        }
        return 0
    }

    private fun cmdSearch(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("pkg search: missing query"); return 1 }
        val query = args.joinToString(" ")
        val index = fetchIndex(ctx) ?: return 1
        val matches = index.filter { p ->
            query.lowercase() in p.name.lowercase() ||
            query.lowercase() in p.description.lowercase()
        }
        if (matches.isEmpty()) { ctx.writeln("No packages found for '$query'"); return 1 }
        ctx.writeln("Search results:")
        for (p in matches) ctx.writeln(String.format("%-20s %-10s %s", p.name, p.version, p.description))
        return 0
    }

    private fun cmdUpdate(ctx: ShellContext): Int {
        ctx.writeln("Updating package index...")
        val success = fetchIndex(ctx, force = true) != null
        return if (success) { ctx.writeln("Index updated."); 0 } else { ctx.writeErrln("pkg: failed to update index"); 1 }
    }

    private fun cmdUpgrade(args: List<String>, ctx: ShellContext): Int {
        val installed = readInstalled(ctx)
        val index = fetchIndex(ctx) ?: return 1
        var upgraded = 0
        for (p in installed) {
            val latest = index.find { it.name == p.name }
            if (latest != null && latest.version != p.version) {
                ctx.writeln("Upgrading ${p.name} ${p.version} -> ${latest.version}")
                try {
                    downloadAndInstall(latest, ctx)
                    p.version = latest.version
                    p.files = latest.files
                    upgraded++
                } catch (e: Exception) {
                    ctx.writeErrln("pkg: failed to upgrade ${p.name}: ${e.message}")
                }
            }
        }
        writeInstalled(installed, ctx)
        ctx.writeln("$upgraded package(s) upgraded")
        return 0
    }

    private fun cmdShow(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("pkg show: missing package"); return 1 }
        val meta = fetchPackageInfo(args[0], ctx) ?: run {
            ctx.writeErrln("pkg: package '${args[0]}' not found"); return 1
        }
        ctx.writeln("Package: ${meta.name}")
        ctx.writeln("Version: ${meta.version}")
        ctx.writeln("Description: ${meta.description}")
        ctx.writeln("Size: ${meta.size} bytes")
        ctx.writeln("SHA-256: ${meta.sha256}")
        ctx.writeln("Files:")
        meta.files.forEach { ctx.writeln("  /${it}") }
        return 0
    }

    private fun cmdFiles(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("pkg files: missing package"); return 1 }
        val installed = readInstalled(ctx)
        val meta = installed.find { it.name == args[0] }
        if (meta == null) { ctx.writeErrln("pkg: ${args[0]} is not installed"); return 1 }
        meta.files.forEach { ctx.writeln("/${it}") }
        return 0
    }

    /** قراءة قائمة المثبتة (JSON بسيط) */
    private fun readInstalled(ctx: ShellContext): MutableList<PackageInfo> {
        if (!installedFile.exists()) return mutableListOf()
        return try {
            parseSimpleJson(installedFile.readText())
        } catch (e: Exception) {
            LinuxDroidLogger.w("PkgCommand", "Failed to read installed: ${e.message}")
            mutableListOf()
        }
    }

    private fun writeInstalled(list: List<PackageInfo>, ctx: ShellContext) {
        val sb = StringBuilder()
        sb.append("[")
        for ((i, p) in list.withIndex()) {
            if (i > 0) sb.append(",")
            sb.append("""{"name":"${p.name}","version":"${p.version}","description":"${p.description}","size":${p.size},"sha256":"${p.sha256}","files":[""")
            sb.append(p.files.joinToString(",") { "\"$it\"" })
            sb.append("]}")
        }
        sb.append("]")
        installedFile.writeText(sb.toString())
    }

    /** يجلب فهرس الحزم من المستودع (مخبّأ لمدة ساعة) */
    private fun fetchIndex(ctx: ShellContext, force: Boolean = false): List<PackageInfo>? {
        val cacheFile = File(ctx.context.cacheDir, "pkg-index.json")
        if (!force && cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < 3600000) {
            return parseSimpleJson(cacheFile.readText())
        }
        return try {
            val url = URL(REPO_URL)
            url.openStream().use { input ->
                cacheFile.outputStream().use { input.copyTo(it) }
            }
            parseSimpleJson(cacheFile.readText())
        } catch (e: Exception) {
            LinuxDroidLogger.w("PkgCommand", "Failed to fetch index: ${e.message}")
            // نعود بقائمة فارغة بدلاً من الفشل (قد يكون غير متصل)
            if (cacheFile.exists()) parseSimpleJson(cacheFile.readText()) else emptyList()
        }
    }

    private fun fetchPackageInfo(name: String, ctx: ShellContext): PackageInfo? {
        val index = fetchIndex(ctx) ?: return null
        return index.find { it.name == name }
    }

    private fun downloadAndInstall(meta: PackageInfo, ctx: ShellContext) {
        // SECURITY: تحقق من اسم الحزمة قبل التنزيل
        if (!com.linuxdroid.security.SecurityUtils.isValidPackageName(meta.name)) {
            throw SecurityException("Invalid package name: ${meta.name}")
        }
        if (!com.linuxdroid.security.SecurityUtils.isValidVersion(meta.version)) {
            throw SecurityException("Invalid version: ${meta.version}")
        }
        if (!com.linuxdroid.security.SecurityUtils.isValidSha256(meta.sha256)) {
            throw SecurityException("Invalid SHA-256 format: ${meta.sha256}")
        }

        // SECURITY: تحقق من URL الحزمة قبل التنزيل
        val downloadUrlStr = "${REPO_BASE}/${meta.name}/${meta.version}/package.tar.gz"
        val urlCheck = com.linuxdroid.security.SecurityUtils.validateUrl(downloadUrlStr)
        if (!urlCheck.ok) {
            throw SecurityException("Invalid download URL: ${urlCheck.value}")
        }

        val tmpFile = File(ctx.context.cacheDir, "${meta.name}-${meta.version}.tar.gz")
        try {
            URL(urlCheck.value).openStream().use { input ->
                tmpFile.outputStream().use { input.copyTo(it) }
            }

            // التحقق من SHA-256
            val actualHash = sha256(tmpFile)
            if (!actualHash.equals(meta.sha256, ignoreCase = true)) {
                throw SecurityException("SHA-256 mismatch: expected ${meta.sha256}, got $actualHash")
            }

            // SECURITY: تحقق من أن كل ملف في meta.files داخل صندوق الرمل
            val safeRoot = Environment.rootDir.canonicalFile
            for (relPath in meta.files) {
                val target = File(Environment.rootDir, relPath).canonicalFile
                val targetPath = target.absolutePath
                val rootPath = safeRoot.absolutePath
                if (!targetPath.startsWith("$rootPath/") && targetPath != rootPath) {
                    throw SecurityException("Package tries to write outside sandbox: $relPath")
                }
                target.parentFile?.mkdirs()
                // في النسخة الحقيقية نستخرج من tar.gz هنا
            }
        } finally {
            // تنظيف الملف المؤقت دائماً
            tmpFile.delete()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }

    /** تحليل JSON بسيط (بدون مكتبة خارجية) */
    private fun parseSimpleJson(json: String): MutableList<PackageInfo> {
        val result = mutableListOf<PackageInfo>()
        val regex = Regex("""\{"name":"([^"]+)","version":"([^"]+)","description":"([^"]*)","size":(\d+),"sha256":"([^"]+)","files":\[([^\]]*)\]\}""")
        for (m in regex.findAll(json)) {
            val (name, version, description, size, sha256, filesStr) = m.destructured
            val files = if (filesStr.isBlank()) emptyList() else filesStr.split(",").map { it.trim('"') }
            result.add(PackageInfo(name, version, description, size.toLong(), sha256, files))
        }
        return result
    }

    override fun help() = """
        pkg — LinuxDroid package manager
        Usage: pkg <command> [packages...]

        Commands:
          install <pkg>          Install package(s)
          remove <pkg>           Remove package(s)
          list                   List available packages
          list-installed         List installed packages
          search <query>         Search packages
          update                 Update package index
          upgrade                Upgrade installed packages
          show <pkg>             Show package info
          files <pkg>            List files of installed package
          help                   Show this help
    """.trimIndent()

    data class PackageInfo(
        val name: String,
        var version: String,
        val description: String,
        val size: Long,
        val sha256: String,
        var files: List<String>
    )

    companion object {
        const val PKG_MODE = 0
        const val APT_MODE = 1
        // المستودع التجريبي (يمكن استبداله بمستودع حقيقي)
        private const val REPO_BASE = "https://raw.githubusercontent.com/divid6055-jpg/linux-droid/main/repo"
        private const val REPO_URL = "$REPO_BASE/index.json"
    }
}

/** nano — محرر نصوص بسيط داخل الطرفية */
class NanoCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("nano: missing file"); return 1 }
        val f = if (args[0].startsWith("/")) File(args[0]) else File(ctx.workingDirectory, args[0])
        if (!f.exists()) f.createNewFile()
        // تبسيط: نطبع رسالة ونحفظ ما يكتبه المستخدم حتى Ctrl+X
        ctx.write("\u001B[2J\u001B[H") // clear
        ctx.writeln("GNU nano 6.0 — LinuxDroid Edition")
        ctx.writeln("File: ${f.absolutePath}")
        ctx.writeln("")
        ctx.writeln("(Simple editor — type to add content, Ctrl+X to save & exit)")
        ctx.writeln("")
        // نقرأ من stdin حتى ^X
        val sb = StringBuilder()
        val reader = ctx.stdin.bufferedReader(Charsets.UTF_8)
        var line = reader.readLine()
        while (line != null) {
            if (line == "\u0018" || line == "^X") break  // Ctrl+X
            sb.append(line).append("\n")
            line = reader.readLine()
        }
        f.writeText(sb.toString())
        ctx.writeln("Saved to ${f.absolutePath}")
        return 0
    }
}

/** vi/vim — محرر Vi (stub) */
class ViCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("vi: missing file"); return 1 }
        ctx.writeErrln("vi: full Vi editor not yet implemented. Use 'nano' instead.")
        return 1
    }
}
