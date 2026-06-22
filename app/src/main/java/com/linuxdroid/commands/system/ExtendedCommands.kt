package com.linuxdroid.commands.system

import com.linuxdroid.commands.CommandExecutor
import com.linuxdroid.commands.ShellContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * أوامر Linux إضافية:
 * - printf: تنسيق وإخراج نص
 * - tar: أرشفة ملفات (تبسيط: gzip only)
 * - gzip / gunzip: ضغط/فك ضغط GZIP
 * - zip / unzip: أرشفة ZIP
 * - base64: ترميز/فك ترميز Base64
 * - md5sum / sha256sum: هاشات
 * - timeout: تشغيل أمر مع مهلة
 * - watch: تنفيذ أمر كل N ثانية
 * - time: قياس زمن تنفيذ أمر
 * - xargs: بناء أوامر من stdin
 * - basename/directory: تمت في TextCommands
 */

/** printf — طباعة منسّقة (متوافقة مع C printf) */
class PrintfCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("printf: missing format"); return 1 }
        val format = args[0]
        val rest = args.drop(1)

        // معالجة تنسيق بسيط: %s, %d, %f, %c, %x, %o, %%
        val result = StringBuilder()
        var i = 0
        var argIdx = 0
        while (i < format.length) {
            val c = format[i]
            if (c == '\\' && i + 1 < format.length) {
                when (format[i + 1]) {
                    'n' -> result.append('\n')
                    't' -> result.append('\t')
                    'r' -> result.append('\r')
                    '\\' -> result.append('\\')
                    '"' -> result.append('"')
                    '\'' -> result.append('\'')
                    '0' -> result.append(0.toChar())
                    else -> { result.append('\\').append(format[i + 1]) }
                }
                i += 2
                continue
            }
            if (c == '%' && i + 1 < format.length) {
                val spec = format[i + 1]
                when (spec) {
                    's' -> { result.append(rest.getOrNull(argIdx) ?: ""); argIdx++ }
                    'd', 'i' -> {
                        val v = rest.getOrNull(argIdx)?.toIntOrNull() ?: 0
                        result.append(v); argIdx++
                    }
                    'f' -> {
                        val v = rest.getOrNull(argIdx)?.toDoubleOrNull() ?: 0.0
                        result.append(v); argIdx++
                    }
                    'c' -> { result.append(rest.getOrNull(argIdx)?.firstOrNull() ?: ""); argIdx++ }
                    'x' -> {
                        val v = rest.getOrNull(argIdx)?.toIntOrNull() ?: 0
                        result.append(v.toString(16)); argIdx++
                    }
                    'X' -> {
                        val v = rest.getOrNull(argIdx)?.toIntOrNull() ?: 0
                        result.append(v.toString(16).uppercase()); argIdx++
                    }
                    'o' -> {
                        val v = rest.getOrNull(argIdx)?.toIntOrNull() ?: 0
                        result.append(v.toString(8)); argIdx++
                    }
                    '%' -> result.append('%')
                    else -> result.append('%').append(spec)
                }
                i += 2
                continue
            }
            result.append(c)
            i++
        }
        ctx.write(result.toString())
        return 0
    }
    override fun help() = "printf — format and print data\nUsage: printf FORMAT [ARGS...]"
}

/** gzip — ضغط ملفات بـ GZIP */
class GzipCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var keep = false; var stdout = false; var verbose = false
        val files = mutableListOf<String>()
        for (arg in args) {
            when {
                arg == "-k" || arg == "--keep" -> keep = true
                arg == "-c" || arg == "--stdout" -> stdout = true
                arg == "-v" || arg == "--verbose" -> verbose = true
                arg == "-d" || arg == "--decompress" -> return gunzipFiles(files, ctx, keep, stdout, verbose)
                arg.startsWith("-") -> {}
                else -> files.add(arg)
            }
        }
        if (files.isEmpty()) {
            // ضغط stdin إلى stdout
            GZIPOutputStream(ctx.stdout).use { gz -> ctx.stdin.copyTo(gz) }
            return 0
        }
        var exit = 0
        for (path in files) {
            val f = ctx.resolveSafe(path) ?: run {
                ctx.writeErrln("gzip: $path: Access denied (path outside sandbox)")
                exit = 1; continue
            }
            if (!f.exists()) { ctx.writeErrln("gzip: $path: No such file"); exit = 1; continue }
            if (f.isDirectory) { ctx.writeErrln("gzip: $path: Is a directory"); exit = 1; continue }
            try {
                if (stdout) {
                    FileInputStream(f).use { input -> GZIPOutputStream(ctx.stdout).use { gz -> input.copyTo(gz) } }
                } else {
                    val outFile = File(f.parentFile, f.name + ".gz")
                    FileInputStream(f).use { input ->
                        GZIPOutputStream(FileOutputStream(outFile)).use { gz -> input.copyTo(gz) }
                    }
                    if (!keep) f.delete()
                    if (verbose) ctx.writeln("gzip: $path -> ${outFile.name}")
                }
            } catch (e: Exception) {
                ctx.writeErrln("gzip: $path: ${e.message}"); exit = 1
            }
        }
        return exit
    }

    private fun gunzipFiles(files: List<String>, ctx: ShellContext, keep: Boolean, stdout: Boolean, verbose: Boolean): Int {
        if (files.isEmpty()) {
            GZIPInputStream(ctx.stdin).use { gz -> gz.copyTo(ctx.stdout) }
            return 0
        }
        var exit = 0
        for (path in files) {
            val f = ctx.resolveSafe(path) ?: run {
                ctx.writeErrln("gunzip: $path: Access denied"); exit = 1; continue
            }
            if (!f.exists()) { ctx.writeErrln("gunzip: $path: No such file"); exit = 1; continue }
            try {
                val outName = if (f.name.endsWith(".gz")) f.name.dropLast(3) else f.name + ".out"
                if (stdout) {
                    GZIPInputStream(FileInputStream(f)).use { gz -> gz.copyTo(ctx.stdout) }
                } else {
                    val outFile = File(f.parentFile, outName)
                    GZIPInputStream(FileInputStream(f)).use { gz ->
                        FileOutputStream(outFile).use { out -> gz.copyTo(out) }
                    }
                    if (!keep) f.delete()
                    if (verbose) ctx.writeln("gunzip: $path -> ${outFile.name}")
                }
            } catch (e: Exception) {
                ctx.writeErrln("gunzip: $path: ${e.message}"); exit = 1
            }
        }
        return exit
    }
    override fun help() = "gzip — compress files\nUsage: gzip [-k] [-c] [-d] [-v] FILE..."
}

/** gunzip — فك ضغط GZIP */
class GunzipCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        return GzipCommand().execute(listOf("-d") + args, ctx)
    }
}

/** zip — إنشاء أرشيف ZIP */
class ZipCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.size < 2) { ctx.writeErrln("zip: usage: zip ARCHIVE FILE..."); return 1 }
        var recursive = false
        val nonFlag = mutableListOf<String>()
        for (arg in args) {
            when {
                arg == "-r" -> recursive = true
                arg.startsWith("-") -> {}
                else -> nonFlag.add(arg)
            }
        }
        if (nonFlag.size < 2) { ctx.writeErrln("zip: need archive and files"); return 1 }
        val archivePath = nonFlag[0]
        val sources = nonFlag.drop(1)
        val archive = ctx.resolveSafeForWrite(archivePath) ?: run {
            ctx.writeErrln("zip: $archivePath: Access denied"); return 1
        }
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(archive))).use { zos ->
                for (src in sources) {
                    val f = ctx.resolveSafe(src) ?: run {
                        ctx.writeErrln("zip: $src: Access denied"); continue
                    }
                    if (!f.exists()) { ctx.writeErrln("zip: $src: No such file"); continue }
                    addToZip(zos, f, f.name, recursive, ctx)
                }
            }
            return 0
        } catch (e: Exception) {
            ctx.writeErrln("zip: ${e.message}"); return 1
        }
    }

    private fun addToZip(zos: ZipOutputStream, file: File, name: String, recursive: Boolean, ctx: ShellContext) {
        if (file.isDirectory) {
            if (!recursive) return
            zos.putNextEntry(ZipEntry("$name/"))
            zos.closeEntry()
            file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                addToZip(zos, child, "$name/${child.name}", recursive, ctx)
            }
        } else {
            FileInputStream(file).use { input ->
                zos.putNextEntry(ZipEntry(name))
                input.copyTo(zos)
                zos.closeEntry()
            }
        }
    }
    override fun help() = "zip — package and compress files\nUsage: zip [-r] ARCHIVE FILE..."
}

/** unzip — استخراج أرشيف ZIP */
class UnzipCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("unzip: missing archive"); return 1 }
        var listOnly = false; var targetDir: String? = null
        val files = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-l" -> listOnly = true
                args[i] == "-d" && i + 1 < args.size -> targetDir = args[++i]
                !args[i].startsWith("-") -> files.add(args[i])
            }
            i++
        }
        if (files.isEmpty()) { ctx.writeErrln("unzip: missing archive"); return 1 }
        val archive = ctx.resolveSafe(files[0]) ?: run {
            ctx.writeErrln("unzip: ${files[0]}: Access denied"); return 1
        }
        if (!archive.exists()) { ctx.writeErrln("unzip: ${files[0]}: No such file"); return 1 }

        val outDir = if (targetDir != null) {
            ctx.resolveSafeForWrite(targetDir) ?: run {
                ctx.writeErrln("unzip: $targetDir: Access denied"); return 1
            }
        } else archive.parentFile

        outDir.mkdirs()

        try {
            ZipInputStream(FileInputStream(archive)).use { zis ->
                var entry = zis.nextEntry
                if (listOnly) {
                    ctx.writeln("  Length    Date    Time    Name")
                    ctx.writeln("---------  ---------- -----   ----")
                }
                var count = 0
                var totalSize = 0L
                while (entry != null) {
                    if (listOnly) {
                        ctx.writeln(String.format("%9d  %s   %s", entry.size, "----", entry.name))
                    } else {
                        // SECURITY: منع path traversal في ZIP entries
                        val outFile = File(outDir, entry.name)
                        val safeOutDir = outDir.canonicalFile
                        val safeOutFile = outFile.canonicalFile
                        if (!safeOutFile.path.startsWith(safeOutDir.path + File.separator) &&
                            safeOutFile != safeOutDir) {
                            ctx.writeErrln("unzip: skipping unsafe path: ${entry.name}")
                            entry = zis.nextEntry; continue
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                            count++
                            totalSize += entry.size
                        }
                    }
                    entry = zis.nextEntry
                }
                if (listOnly) ctx.writeln("---------                     -------")
                else ctx.writeln("Extracted $count file(s), $totalSize bytes")
            }
            return 0
        } catch (e: Exception) {
            ctx.writeErrln("unzip: ${e.message}"); return 1
        }
    }
    override fun help() = "unzip — extract ZIP archives\nUsage: unzip [-l] [-d DIR] ARCHIVE"
}

/** tar — أرشفة (تبسيط: يدعم .tar.gz فقط) */
class TarCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var extract = false; var create = false; var verbose = false
        var gzip = false; var fileList = false
        val files = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.drop(1)) when (c) {
                        'x' -> extract = true
                        'c' -> create = true
                        'v' -> verbose = true
                        'z' -> gzip = true
                        't' -> fileList = true
                        'f' -> { if (i + 1 < args.size) files.add(args[++i]) }
                        else -> {}
                    }
                }
                else -> files.add(arg)
            }
            i++
        }
        if (files.isEmpty()) { ctx.writeErrln("tar: missing archive"); return 1 }

        val archive = ctx.resolveSafe(files[0]) ?: run {
            ctx.writeErrln("tar: ${files[0]}: Access denied"); return 1
        }

        if (create) {
            // تبسيط: نستخدم ZIP بدلاً من tar فعلياً (لأن tar غير متوفر في java.util)
            // نستدعي ZipCommand بشكل داخلي
            ctx.writeln("tar: creating tar.gz archives not fully implemented")
            ctx.writeln("tar: use 'zip' command instead for now")
            return 1
        }
        if (extract || fileList) {
            // نحاول فك الضغط كـ ZIP (تبسيط)
            return UnzipCommand().execute(if (fileList) listOf("-l", files[0]) else listOf(files[0]), ctx)
        }
        ctx.writeErrln("tar: must specify -c, -x, or -t")
        return 1
    }
    override fun help() = "tar — archive utility (limited)\nUsage: tar -c|x|t [-v] [-z] -f ARCHIVE [FILE...]"
}

/** base64 — ترميز/فك ترميز Base64 */
class Base64Command : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var decode = false; var wrap = 76
        val files = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-d" || args[i] == "--decode" -> decode = true
                args[i] == "-w" && i + 1 < args.size -> wrap = args[++i].toIntOrNull() ?: 76
                !args[i].startsWith("-") -> files.add(args[i])
            }
            i++
        }
        val input: ByteArray = if (files.isEmpty()) ctx.stdin.readBytes()
                               else (ctx.resolveSafe(files[0]) ?: run {
                                   ctx.writeErrln("base64: ${files[0]}: Access denied"); return 1
                               }).readBytes()
        try {
            if (decode) {
                val decoded = java.util.Base64.getDecoder().decode(input)
                ctx.stdout.write(decoded); ctx.stdout.flush()
            } else {
                val encoded = java.util.Base64.getEncoder().encodeToString(input)
                if (wrap > 0) {
                    var idx = 0
                    while (idx < encoded.length) {
                        val end = minOf(idx + wrap, encoded.length)
                        ctx.writeln(encoded.substring(idx, end))
                        idx += end - idx
                    }
                } else {
                    ctx.write(encoded)
                }
            }
            return 0
        } catch (e: Exception) {
            ctx.writeErrln("base64: ${e.message}"); return 1
        }
    }
    override fun help() = "base64 — encode/decode base64\nUsage: base64 [-d] [-w N] [FILE]"
}

/** md5sum — حساب MD5 */
class Md5SumCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val files = args.filter { !it.startsWith("-") }
        if (files.isEmpty()) {
            val data = ctx.stdin.readBytes()
            val md = java.security.MessageDigest.getInstance("MD5")
            val hash = md.digest(data)
            ctx.writeln(hash.joinToString("") { String.format("%02x", it) } + "  -")
            return 0
        }
        var exit = 0
        for (path in files) {
            val f = ctx.resolveSafe(path) ?: run {
                ctx.writeErrln("md5sum: $path: Access denied"); exit = 1; continue
            }
            if (!f.exists()) { ctx.writeErrln("md5sum: $path: No such file"); exit = 1; continue }
            try {
                val md = java.security.MessageDigest.getInstance("MD5")
                f.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf); if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                }
                val hash = md.digest()
                ctx.writeln(hash.joinToString("") { String.format("%02x", it) } + "  $path")
            } catch (e: Exception) {
                ctx.writeErrln("md5sum: $path: ${e.message}"); exit = 1
            }
        }
        return exit
    }
}

/** sha256sum — حساب SHA-256 */
class Sha256SumCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val files = args.filter { !it.startsWith("-") }
        if (files.isEmpty()) {
            val data = ctx.stdin.readBytes()
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hash = md.digest(data)
            ctx.writeln(hash.joinToString("") { String.format("%02x", it) } + "  -")
            return 0
        }
        var exit = 0
        for (path in files) {
            val f = ctx.resolveSafe(path) ?: run {
                ctx.writeErrln("sha256sum: $path: Access denied"); exit = 1; continue
            }
            if (!f.exists()) { ctx.writeErrln("sha256sum: $path: No such file"); exit = 1; continue }
            try {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                f.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf); if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                }
                val hash = md.digest()
                ctx.writeln(hash.joinToString("") { String.format("%02x", it) } + "  $path")
            } catch (e: Exception) {
                ctx.writeErrln("sha256sum: $path: ${e.message}"); exit = 1
            }
        }
        return exit
    }
}

/** time — قياس زمن تنفيذ أمر */
class TimeCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("time: missing command"); return 1 }
        val start = System.currentTimeMillis()
        val exitCode = com.linuxdroid.commands.CommandRegistry.execute(args[0], args.drop(1), ctx)
        val elapsed = System.currentTimeMillis() - start
        val seconds = elapsed / 1000.0
        ctx.writeErrln(String.format("\nreal    %.3fs", seconds))
        return exitCode
    }
    override fun help() = "time — measure command execution time\nUsage: time COMMAND [ARGS...]"
}

/** xargs — بناء أوامر من stdin */
class XargsCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var delimiter = "\n"; var maxArgs = 1000
        val cmdParts = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-d" && i + 1 < args.size -> delimiter = args[++i]
                args[i] == "-n" && i + 1 < args.size -> maxArgs = args[++i].toIntOrNull() ?: 1000
                args[i].startsWith("-") -> {}
                else -> cmdParts.add(args[i])
            }
            i++
        }
        if (cmdParts.isEmpty()) { ctx.writeErrln("xargs: missing command"); return 1 }
        val cmdName = cmdParts[0]
        val cmdArgs = cmdParts.drop(1).toMutableList()
        val input = ctx.readAll()
        val items = if (delimiter == "\n") input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    else input.split(delimiter)
        var exit = 0
        var batch = mutableListOf<String>()
        for (item in items) {
            batch.add(item)
            if (batch.size >= maxArgs) {
                val fullArgs = (cmdArgs + batch).toList()
                val r = com.linuxdroid.commands.CommandRegistry.execute(cmdName, fullArgs, ctx)
                if (r != 0) exit = r
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) {
            val fullArgs = (cmdArgs + batch).toList()
            val r = com.linuxdroid.commands.CommandRegistry.execute(cmdName, fullArgs, ctx)
            if (r != 0) exit = r
        }
        return exit
    }
    override fun help() = "xargs — build and execute commands from stdin\nUsage: xargs [-d DELIM] [-n MAX] COMMAND [ARGS...]"
}

/** timeout — تشغيل أمر مع مهلة (تبسيط) */
class TimeoutCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("timeout: missing duration"); return 1 }
        val duration = args[0].toDoubleOrNull() ?: run {
            ctx.writeErrln("timeout: invalid duration '${args[0]}'"); return 1
        }
        if (args.size < 2) { ctx.writeErrln("timeout: missing command"); return 1 }
        val cmdName = args[1]
        val cmdArgs = args.drop(2)
        // تبسيط: ننفّذ الأمر في خيط مع مهلة
        val thread = Thread {
            com.linuxdroid.commands.CommandRegistry.execute(cmdName, cmdArgs, ctx)
        }
        thread.start()
        thread.join((duration * 1000).toLong())
        if (thread.isAlive) {
            thread.interrupt()
            ctx.writeErrln("timeout: command timed out after ${duration}s")
            return 124
        }
        return 0
    }
    override fun help() = "timeout — run a command with a time limit\nUsage: timeout DURATION COMMAND [ARGS...]"
}

/** watch — تنفيذ أمر كل N ثانية (مرة واحدة فقط لمنع الحلقات اللانهائية) */
class WatchCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var interval = 2.0
        val cmdArgs = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-n" && i + 1 < args.size -> interval = args[++i].toDoubleOrNull() ?: 2.0
                args[i].startsWith("-") -> {}
                else -> cmdArgs.add(args[i])
            }
            i++
        }
        if (cmdArgs.isEmpty()) { ctx.writeErrln("watch: missing command"); return 1 }
        // نطبع مرة واحدة فقط لمنع الحلقات (المستخدم يمكنه إعادة التشغيل يدوياً)
        ctx.writeln("Every ${interval}s: ${cmdArgs.joinToString(" ")}")
        return com.linuxdroid.commands.CommandRegistry.execute(cmdArgs[0], cmdArgs.drop(1), ctx)
    }
    override fun help() = "watch — execute a program periodically\nUsage: watch [-n SECONDS] COMMAND [ARGS...]"
}

/** history — عرض تاريخ الأوامر (مدمج في ShellInterpreter) */
class HistoryCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        // التاريخ يُدار بواسطة ShellInterpreter مباشرة
        return 0
    }
}
