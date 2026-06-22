package com.linuxdroid.commands.files

import com.linuxdroid.commands.CommandExecutor
import com.linuxdroid.commands.ShellContext
import com.linuxdroid.shell.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ls — قائمة محتويات المجلد.
 *
 * الخيارات المدعومة:
 *  -l  تنسيق طويل (أذونات، حجم، تاريخ)
 *  -a  إظهار الملفات المخفية (تبدأ بـ .)
 *  -A  مثل -a لكن بدون . و ..
 *  -h  أحجام مقروءة (1K, 2M, 3G)
 *  -t  ترتيب حسب وقت التعديل
 *  -r  ترتيب عكسي
 *  -S  ترتيب حسب الحجم
 *  -1  عمود واحد
 *  -R  تكراري
 *  -F  إضافة / للمجلدات، * للمنفّذة
 *  -d  عرض المجلد نفسه لا محتواه
 *  --color=[always|auto|never]
 */
class LsCommand : CommandExecutor {
    private var longFormat = false

    fun setLongFormat(v: Boolean): LsCommand { longFormat = v; return this }

    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var showAll = false
        var showAlmostAll = false
        var humanReadable = false
        var longFmt = longFormat
        var sortByTime = false
        var reverse = false
        var sortBySize = false
        var oneColumn = false
        var recursive = false
        var classify = false
        var dirOnly = false
        val paths = mutableListOf<String>()

        for (arg in args) {
            when {
                arg == "--help" -> { ctx.write(help()); return 0 }
                arg == "--color" -> {}
                arg.startsWith("--color=") -> {}
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.drop(1)) {
                        when (c) {
                            'l' -> longFmt = true
                            'a' -> showAll = true
                            'A' -> showAlmostAll = true
                            'h' -> humanReadable = true
                            't' -> sortByTime = true
                            'r' -> reverse = true
                            'S' -> sortBySize = true
                            '1' -> oneColumn = true
                            'R' -> recursive = true
                            'F' -> classify = true
                            'd' -> dirOnly = true
                            else -> { ctx.writeErrln("ls: invalid option -- '$c'"); return 1 }
                        }
                    }
                }
                else -> paths.add(arg)
            }
        }
        if (paths.isEmpty()) paths.add(ctx.workingDirectory)

        var exit = 0
        for ((i, path) in paths.withIndex()) {
            val file = File(path).let {
                if (!it.isAbsolute) File(ctx.workingDirectory, path) else it
            }
            if (!file.exists()) {
                ctx.writeErrln("ls: cannot access '$path': No such file or directory")
                exit = 1
                continue
            }
            if (i > 0) ctx.write("\r\n")
            if (paths.size > 1 || recursive) ctx.write("${path}:\r\n")

            if (dirOnly && file.isDirectory) {
                printFileInfo(file, longFmt, humanReadable, classify, ctx)
                ctx.write("\r\n")
                continue
            }

            if (file.isFile) {
                printFileInfo(file, longFmt, humanReadable, classify, ctx)
                ctx.write("\r\n")
            } else {
                val children = file.listFiles()?.toList() ?: emptyList()
                var visible = children
                if (!showAll && !showAlmostAll) {
                    visible = visible.filter { !it.name.startsWith(".") }
                } else if (showAlmostAll) {
                    visible = visible.filter { it.name != "." && it.name != ".." }
                }
                visible = when {
                    sortByTime -> visible.sortedByDescending { it.lastModified() }
                    sortBySize -> visible.sortedByDescending { it.length() }
                    else -> visible.sortedBy { it.name.lowercase() }
                }
                if (reverse) visible = visible.reversed()

                if (longFmt) {
                    printLongListing(visible, humanReadable, classify, ctx)
                } else if (oneColumn) {
                    visible.forEach { c ->
                        val name = if (classify) classifyName(c) else c.name
                        ctx.writeln(colorize(name, c))
                    }
                } else {
                    printColumnar(visible, classify, ctx)
                }

                if (recursive) {
                    visible.filter { it.isDirectory }.forEach { sub ->
                        ctx.write("\r\n${sub.absolutePath}:\r\n")
                        val subList = sub.listFiles()?.toList() ?: emptyList()
                        if (longFmt) printLongListing(subList, humanReadable, classify, ctx)
                        else printColumnar(subList, classify, ctx)
                    }
                }
            }
        }
        return exit
    }

    private fun printFileInfo(file: File, longFmt: Boolean, human: Boolean, classify: Boolean, ctx: ShellContext) {
        if (longFmt) {
            val perms = permissions(file)
            val size = if (human) humanReadableSize(file.length()) else file.length().toString()
            val date = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(file.lastModified()))
            val name = if (classify) classifyName(file) else file.name
            ctx.writeln("$perms 1 ${ctx.environment["USER"] ?: "user"} ${ctx.environment["USER"] ?: "user"} $size $date ${colorize(name, file)}")
        } else {
            ctx.write(colorize(if (classify) classifyName(file) else file.name, file))
        }
    }

    private fun printLongListing(files: List<File>, human: Boolean, classify: Boolean, ctx: ShellContext) {
        if (files.isEmpty()) return
        val total = files.sumOf { it.length() / 1024 }
        ctx.writeln("total $total")
        val dateFmt = SimpleDateFormat("MMM dd HH:mm", Locale.US)
        for (f in files) {
            val perms = permissions(f)
            val size = if (human) humanReadableSize(f.length()) else f.length().toString()
            val date = dateFmt.format(Date(f.lastModified()))
            val name = if (classify) classifyName(f) else f.name
            ctx.writeln("$perms 1 ${ctx.environment["USER"] ?: "user"} ${ctx.environment["USER"] ?: "user"} $size $date ${colorize(name, f)}")
        }
    }

    private fun printColumnar(files: List<File>, classify: Boolean, ctx: ShellContext) {
        if (files.isEmpty()) return
        val cols = ctx.columns.coerceAtLeast(40)
        val maxLen = files.maxOf { it.name.length } + 2
        val perLine = (cols / maxLen).coerceAtLeast(1)
        val sb = StringBuilder()
        for ((i, f) in files.withIndex()) {
            val name = if (classify) classifyName(f) else f.name
            val colored = colorize(name, f)
            sb.append(colored)
            val pad = maxLen - name.length
            repeat(pad) { sb.append(' ') }
            if ((i + 1) % perLine == 0) sb.append("\r\n")
        }
        if (sb.isNotEmpty() && !sb.endsWith("\r\n")) sb.append("\r\n")
        ctx.write(sb.toString())
    }

    private fun permissions(f: File): String {
        val sb = StringBuilder()
        sb.append(if (f.isDirectory) 'd' else '-')
        sb.append(if (f.canRead()) 'r' else '-')
        sb.append(if (f.canWrite()) 'w' else '-')
        sb.append(if (f.canExecute()) 'x' else '-')
        sb.append("rwxrwxrwx".take(6))
        return sb.toString().take(10)
    }

    private fun humanReadableSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}K"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}M"
        else -> "${bytes / (1024 * 1024 * 1024)}G"
    }

    private fun classifyName(f: File): String = when {
        f.isDirectory -> f.name + "/"
        f.canExecute() -> f.name + "*"
        f.absolutePath.endsWith(".symlink") || f.absolutePath.endsWith(".lnk") -> f.name + "@"
        else -> f.name
    }

    private fun colorize(name: String, f: File): String {
        return when {
            f.isDirectory -> "\u001B[1;34m$name\u001B[0m"
            f.canExecute() -> "\u001B[1;32m$name\u001B[0m"
            name.startsWith(".") -> "\u001B[90m$name\u001B[0m"
            else -> name
        }
    }

    override fun help() = """
        ls — list directory contents
        Usage: ls [-l] [-a] [-A] [-h] [-t] [-r] [-S] [-1] [-R] [-F] [-d] [FILE...]
        Options:
          -l  long listing format
          -a  show all (including hidden)
          -A  almost all (skip . and ..)
          -h  human-readable sizes
          -t  sort by modification time
          -r  reverse sort
          -S  sort by size
          -1  one column
          -R  recursive
          -F  classify (/ for dirs, * for exec)
          -d  list directory itself, not contents
    """.trimIndent()
}

/** cat — عرض محتوى الملفات */
class CatCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var showLineNumbers = false
        var showEnds = false
        var squeezeBlank = false
        val files = mutableListOf<String>()

        for (arg in args) {
            when {
                arg == "--help" -> { ctx.write(help()); return 0 }
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.drop(1)) when (c) {
                        'n' -> showLineNumbers = true
                        'E' -> showEnds = true
                        's' -> squeezeBlank = true
                        else -> { ctx.writeErrln("cat: invalid option -- '$c'"); return 1 }
                    }
                }
                else -> files.add(arg)
            }
        }

        if (files.isEmpty()) {
            return readStream(ctx.stdin, ctx, showLineNumbers, showEnds, squeezeBlank, 0)
        }

        var exit = 0
        var lineno = 0
        for (path in files) {
            val f = File(if (path.startsWith("/")) path else File(ctx.workingDirectory, path).absolutePath)
            if (!f.exists()) { ctx.writeErrln("cat: $path: No such file or directory"); exit = 1; continue }
            if (f.isDirectory) { ctx.writeErrln("cat: $path: Is a directory"); exit = 1; continue }
            try {
                lineno = readStream(f.inputStream(), ctx, showLineNumbers, showEnds, squeezeBlank, lineno)
            } catch (e: Exception) { ctx.writeErrln("cat: $path: ${e.message}"); exit = 1 }
        }
        return exit
    }

    private fun readStream(stream: java.io.InputStream, ctx: ShellContext, num: Boolean, ends: Boolean, squeeze: Boolean, startLine: Int): Int {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        var lineno = startLine
        var prevBlank = false
        var line = reader.readLine()
        while (line != null) {
            val isBlank = line.isEmpty()
            if (squeeze && isBlank && prevBlank) { line = reader.readLine(); continue }
            prevBlank = isBlank
            lineno++
            val sb = StringBuilder()
            if (num) sb.append(String.format("%6d  ", lineno))
            sb.append(line)
            if (ends) sb.append('$')
            sb.append("\r\n")
            ctx.write(sb.toString())
            line = reader.readLine()
        }
        return lineno
    }

    override fun help() = "cat — concatenate files and print on stdout\nUsage: cat [-n] [-E] [-s] [FILE...]"
}

/** cp — نسخ الملفات */
class CpCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var recursive = false
        var force = false
        var verbose = false
        val targets = mutableListOf<String>()

        for (arg in args) {
            when {
                arg == "--help" -> { ctx.write(help()); return 0 }
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.drop(1)) when (c) {
                        'r', 'R' -> recursive = true
                        'f' -> force = true
                        'v' -> verbose = true
                        else -> { ctx.writeErrln("cp: invalid option -- '$c'"); return 1 }
                    }
                }
                else -> targets.add(arg)
            }
        }
        if (targets.size < 2) { ctx.writeErrln("cp: missing file operand"); return 1 }
        val dest = resolve(targets.last(), ctx)
        val sources = targets.dropLast(1).map { resolve(it, ctx) }
        var exit = 0
        for ((i, src) in sources.withIndex()) {
            if (!src.exists()) { ctx.writeErrln("cp: cannot stat '${targets[i]}': No such file or directory"); exit = 1; continue }
            try {
                if (src.isDirectory && !recursive) { ctx.writeErrln("cp: omitting directory '${src.name}'"); exit = 1; continue }
                val target = if (dest.isDirectory) File(dest, src.name) else dest
                copyRecursive(src, target)
                if (verbose) ctx.writeln("'${src.name}' -> '${target.absolutePath}'")
            } catch (e: Exception) { ctx.writeErrln("cp: ${e.message}"); exit = 1 }
        }
        return exit
    }

    private fun copyRecursive(src: File, dest: File) {
        if (src.isDirectory) { dest.mkdirs(); src.listFiles()?.forEach { copyRecursive(it, File(dest, it.name)) } }
        else src.inputStream().use { it.copyTo(dest.outputStream()) }
    }

    private fun resolve(p: String, ctx: ShellContext) = if (p.startsWith("/")) File(p) else File(ctx.workingDirectory, p)
    override fun help() = "cp — copy files and directories\nUsage: cp [-r] [-f] [-v] SOURCE... DEST"
}

/** mv — نقل/إعادة تسمية الملفات */
class MvCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var force = false; var verbose = false
        val targets = mutableListOf<String>()
        for (arg in args) {
            when {
                arg == "--help" -> { ctx.write(help()); return 0 }
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.drop(1)) when (c) { 'f' -> force = true; 'v' -> verbose = true; else -> { ctx.writeErrln("mv: invalid option -- '$c'"); return 1 } }
                }
                else -> targets.add(arg)
            }
        }
        if (targets.size < 2) { ctx.writeErrln("mv: missing file operand"); return 1 }
        val dest = resolve(targets.last(), ctx)
        val sources = targets.dropLast(1).map { resolve(it, ctx) }
        var exit = 0
        for (src in sources) {
            if (!src.exists()) { ctx.writeErrln("mv: cannot stat '${src.name}': No such file or directory"); exit = 1; continue }
            val target = if (dest.isDirectory) File(dest, src.name) else dest
            if (!force && target.exists()) { ctx.writeErrln("mv: '${target.name}': File exists"); exit = 1; continue }
            if (!src.renameTo(target)) {
                try { src.inputStream().use { it.copyTo(target.outputStream()) }; src.delete() }
                catch (e: Exception) { ctx.writeErrln("mv: ${e.message}"); exit = 1 }
            }
            if (verbose) ctx.writeln("'${src.name}' -> '${target.absolutePath}'")
        }
        return exit
    }
    private fun resolve(p: String, ctx: ShellContext) = if (p.startsWith("/")) File(p) else File(ctx.workingDirectory, p)
    override fun help() = "mv — move/rename files\nUsage: mv [-f] [-v] SOURCE... DEST"
}

/** rm — حذف الملفات/المجلدات */
class RmCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var recursive = false; var force = false; var verbose = false
        val targets = mutableListOf<String>()
        for (arg in args) {
            when {
                arg == "--help" -> { ctx.write(help()); return 0 }
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.drop(1)) when (c) { 'r', 'R' -> recursive = true; 'f' -> force = true; 'v' -> verbose = true; else -> { ctx.writeErrln("rm: invalid option -- '$c'"); return 1 } }
                }
                else -> targets.add(arg)
            }
        }
        if (targets.isEmpty()) { if (!force) ctx.writeErrln("rm: missing operand"); return if (force) 0 else 1 }
        var exit = 0
        for (t in targets) {
            val f = resolve(t, ctx)
            if (!f.exists()) { if (!force) { ctx.writeErrln("rm: cannot remove '$t': No such file or directory"); exit = 1 } continue }
            if (f.isDirectory && !recursive) { ctx.writeErrln("rm: cannot remove '$t': Is a directory"); exit = 1; continue }
            try { deleteRecursive(f); if (verbose) ctx.writeln("removed '$t'") }
            catch (e: Exception) { if (!force) { ctx.writeErrln("rm: cannot remove '$t': ${e.message}"); exit = 1 } }
        }
        return exit
    }
    private fun deleteRecursive(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        if (!f.delete()) throw java.io.IOException("Failed to delete ${f.name}")
    }
    private fun resolve(p: String, ctx: ShellContext) = if (p.startsWith("/")) File(p) else File(ctx.workingDirectory, p)
    override fun help() = "rm — remove files or directories\nUsage: rm [-r] [-f] [-v] FILE..."
}

/** mkdir — إنشاء مجلدات */
class MkdirCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var makeParents = false; var verbose = false
        val targets = mutableListOf<String>()
        for (arg in args) {
            when {
                arg == "--help" -> { ctx.write(help()); return 0 }
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.drop(1)) when (c) { 'p' -> makeParents = true; 'v' -> verbose = true; else -> { ctx.writeErrln("mkdir: invalid option -- '$c'"); return 1 } }
                }
                else -> targets.add(arg)
            }
        }
        if (targets.isEmpty()) { ctx.writeErrln("mkdir: missing operand"); return 1 }
        var exit = 0
        for (t in targets) {
            val f = if (t.startsWith("/")) File(t) else File(ctx.workingDirectory, t)
            val ok = if (makeParents) f.mkdirs() else f.mkdir()
            if (!ok && !f.exists()) { ctx.writeErrln("mkdir: cannot create directory '$t'"); exit = 1 }
            else if (verbose) ctx.writeln("mkdir: created directory '$t'")
        }
        return exit
    }
    override fun help() = "mkdir — make directories\nUsage: mkdir [-p] [-v] DIRECTORY..."
}

/** rmdir — حذف مجلد فارغ */
class RmdirCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var exit = 0
        for (t in args) {
            val f = if (t.startsWith("/")) File(t) else File(ctx.workingDirectory, t)
            if (!f.exists()) { ctx.writeErrln("rmdir: '$t': No such file or directory"); exit = 1; continue }
            if (!f.isDirectory) { ctx.writeErrln("rmdir: '$t': Not a directory"); exit = 1; continue }
            if (f.listFiles()?.isNotEmpty() == true) { ctx.writeErrln("rmdir: '$t': Directory not empty"); exit = 1; continue }
            if (!f.delete()) { ctx.writeErrln("rmdir: '$t': Failed"); exit = 1 }
        }
        return exit
    }
}

/** touch — إنشاء ملف فارغ أو تحديث وقت التعديل */
class TouchCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        for (t in args) {
            val f = if (t.startsWith("/")) File(t) else File(ctx.workingDirectory, t)
            if (!f.exists()) f.createNewFile() else f.setLastModified(System.currentTimeMillis())
        }
        return 0
    }
}

/** ln — إنشاء روابط (نرمزية فقط في أندرويد) */
class LnCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var symbolic = false; var force = false
        val targets = mutableListOf<String>()
        for (arg in args) {
            when { arg == "-s" -> symbolic = true; arg == "-f" -> force = true; arg.startsWith("-") -> {}; else -> targets.add(arg) }
        }
        if (targets.size < 2) { ctx.writeErrln("ln: missing operand"); return 1 }
        val target = resolve(targets[0], ctx)
        val link = resolve(targets[1], ctx)
        if (force && link.exists()) link.delete()
        return if (symbolic) { link.writeText(target.absolutePath); 0 }
        else { ctx.writeErrln("ln: hard links not supported on Android"); 1 }
    }
    private fun resolve(p: String, ctx: ShellContext) = if (p.startsWith("/")) File(p) else File(ctx.workingDirectory, p)
}

/** find — البحث في شجرة المجلدات */
class FindCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var path = ctx.workingDirectory
        val predicates = mutableListOf<String>()
        for (arg in args) {
            when {
                arg == "--help" -> { ctx.write(help()); return 0 }
                arg.startsWith("-") -> predicates.add(arg)
                predicates.isEmpty() && path == ctx.workingDirectory -> path = if (arg.startsWith("/")) arg else File(ctx.workingDirectory, arg).absolutePath
                else -> predicates.add(arg)
            }
        }
        val root = File(path)
        if (!root.exists()) { ctx.writeErrln("find: '$path': No such file or directory"); return 1 }

        var namePattern: String? = null
        var typeFilter: Char? = null
        var maxDepth = -1
        val pIter = predicates.iterator()
        while (pIter.hasNext()) {
            when (val p = pIter.next()) {
                "-name" -> namePattern = pIter.next()
                "-type" -> typeFilter = pIter.next()[0]
                "-maxdepth" -> maxDepth = pIter.next().toIntOrNull() ?: -1
                "-print", "-print0" -> {}
                else -> {}
            }
        }
        walkFind(root, namePattern, typeFilter, maxDepth, 0, ctx)
        return 0
    }

    private fun walkFind(f: File, name: String?, type: Char?, maxDepth: Int, depth: Int, ctx: ShellContext) {
        if (maxDepth >= 0 && depth > maxDepth) return
        val matches = (name == null || matchesGlob(f.name, name)) &&
            (type == null || (type == 'f' && f.isFile) || (type == 'd' && f.isDirectory))
        if (matches) ctx.writeln(f.absolutePath)
        if (f.isDirectory) f.listFiles()?.forEach { walkFind(it, name, type, maxDepth, depth + 1, ctx) }
    }

    private fun matchesGlob(name: String, pattern: String): Boolean {
        val regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".").toRegex()
        return regex.matches(name)
    }

    override fun help() = "find — search for files in a directory hierarchy\nUsage: find [PATH] [-name PATTERN] [-type TYPE] [-maxdepth N]"
}

/** stat — معلومات مفصّلة عن الملف */
class StatCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        for (t in args) {
            val f = if (t.startsWith("/")) File(t) else File(ctx.workingDirectory, t)
            if (!f.exists()) { ctx.writeErrln("stat: cannot stat '$t': No such file or directory"); continue }
            ctx.writeln("  File: ${f.name}")
            ctx.writeln("  Size: ${f.length()}       Blocks: ${f.length() / 512 + 1}       ${if (f.isDirectory) "Directory" else "Regular File"}")
            ctx.writeln("Access: (${if (f.canRead()) "r" else "-"}${if (f.canWrite()) "w" else "-"}${if (f.canExecute()) "x" else "-"})  Uid: ${ctx.environment["USER"]}  Gid: ${ctx.environment["USER"]}")
            ctx.writeln("Modify: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(f.lastModified()))}")
        }
        return 0
    }
}

/** file — نوع الملف */
class FileCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        for (t in args) {
            val f = if (t.startsWith("/")) File(t) else File(ctx.workingDirectory, t)
            if (!f.exists()) { ctx.writeErrln("file: '$t': No such file or directory"); continue }
            val type = when {
                f.isDirectory -> "directory"
                f.length() == 0L -> "empty"
                else -> detectType(f)
            }
            ctx.writeln("$t: $type")
        }
        return 0
    }

    private fun detectType(f: File): String {
        try {
            val first = f.inputStream().use { it.read() }
            return when {
                first == 0x7F -> "ELF binary"
                first == '{'.code || first == '['.code -> "JSON data"
                first == '<'.code -> "HTML/XML document"
                first == 0xFF -> "binary"
                first == '#'.code -> "text/script"
                else -> "ASCII text"
            }
        } catch (e: Exception) { return "unknown" }
    }
}

/** tree — عرض شجرة المجلدات */
class TreeCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val path = if (args.isEmpty()) ctx.workingDirectory else if (args[0].startsWith("/")) args[0] else File(ctx.workingDirectory, args[0]).absolutePath
        val root = File(path)
        if (!root.exists()) { ctx.writeErrln("tree: '$path': No such file or directory"); return 1 }
        ctx.writeln(root.absolutePath)
        printTree(root, "", ctx)
        return 0
    }
    private fun printTree(f: File, prefix: String, ctx: ShellContext) {
        val children = f.listFiles()?.sortedBy { it.name } ?: return
        for ((i, c) in children.withIndex()) {
            val isLast = i == children.size - 1
            ctx.writeln("$prefix${if (isLast) "└── " else "├── "}${c.name}")
            if (c.isDirectory) printTree(c, prefix + if (isLast) "    " else "│   ", ctx)
        }
    }
}

/** du — استخدام القرص */
class DuCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val path = if (args.isEmpty()) ctx.workingDirectory else if (args[0].startsWith("/")) args[0] else File(ctx.workingDirectory, args[0]).absolutePath
        val f = File(path)
        if (!f.exists()) { ctx.writeErrln("du: '$path': No such file or directory"); return 1 }
        ctx.writeln("${sizeOf(f) / 1024}\t${f.absolutePath}")
        return 0
    }
    private fun sizeOf(f: File): Long = if (f.isDirectory) f.listFiles()?.sumOf { sizeOf(it) } ?: 0 else f.length()
}

/** df — مساحة القرص الحرة */
class DfCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        ctx.writeln("Filesystem      Size  Used Avail Use% Mounted on")
        val stat = android.os.StatFs(ctx.workingDirectory)
        val total = stat.totalBytes
        val free = stat.freeBytes
        val used = total - free
        val pct = if (total > 0) (used * 100 / total).toInt() else 0
        ctx.writeln(String.format("local          %4dM %4dM %4dM  %2d%% %s", total / 1048576, used / 1048576, free / 1048576, pct, ctx.workingDirectory))
        return 0
    }
}

/** chmod — تغيير الأذونات (شكلي على أندرويد) */
class ChmodCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.size < 2) { ctx.writeErrln("chmod: missing operand"); return 1 }
        val mode = args[0]
        val target = if (args[1].startsWith("/")) File(args[1]) else File(ctx.workingDirectory, args[1])
        if (!target.exists()) { ctx.writeErrln("chmod: cannot access '${args[1]}': No such file or directory"); return 1 }
        val canExec = mode.endsWith("1") || mode.endsWith("3") || mode.endsWith("5") || mode.endsWith("7")
        target.setExecutable(canExec)
        return 0
    }
}

/** chown — تغيير المالك (شكلي) */
class ChownCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.size < 2) { ctx.writeErrln("chown: missing operand"); return 1 }
        ctx.writeErrln("chown: changing ownership of '${args[1]}': Operation not permitted")
        return 1
    }
}
