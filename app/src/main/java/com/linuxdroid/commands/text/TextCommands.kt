package com.linuxdroid.commands.text

import com.linuxdroid.commands.CommandExecutor
import com.linuxdroid.commands.ShellContext
import java.io.File
import java.util.regex.Pattern

/**
 * أوامر معالجة النصوص.
 *
 * يشمل: echo, grep, sed, awk, head, tail, wc, sort, uniq, cut, tr, tee,
 *       diff, paste, rev, nl, tac, basename, dirname, realpath, xxd, od,
 *       column, fold, expand, unexpand
 */

/** echo — طباعة نص */
class EchoCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var interpretEscapes = false
        var noNewline = false
        val text = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--help" -> { ctx.write(help()); return 0 }
                arg.startsWith("-") && arg.length > 1 && arg.all { it == '-' || it == 'n' || it == 'e' || it == 'E' } -> {
                    for (c in arg.drop(1)) when (c) { 'n' -> noNewline = true; 'e' -> interpretEscapes = true; 'E' -> interpretEscapes = false }
                }
                else -> text.add(arg)
            }
            i++
        }
        val joined = text.joinToString(" ")
        val final = if (interpretEscapes) interpretEscapes(joined) else joined
        ctx.write(if (noNewline) final else final + "\r\n")
        return 0
    }

    private fun interpretEscapes(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '0' -> sb.append(0.toChar())
                    else -> { sb.append('\\'); sb.append(s[i + 1]) }
                }
                i += 2
            } else { sb.append(s[i]); i++ }
        }
        return sb.toString()
    }

    override fun help() = "echo — display a line of text\nUsage: echo [-n] [-e] [STRING...]"
}

/** grep — البحث في النص */
class GrepCommand : CommandExecutor {
    var extendedRegex = false
    var fixedString = false
    var recursive = false

    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var ignoreCase = false
        var invert = false
        var lineNumber = false
        var countOnly = false
        var wordMatch = false
        var showFilename = false
        var noFilename = false
        var color = "auto"
        val nonFlagArgs = mutableListOf<String>()
        var recursiveLocal = recursive

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--help" -> { ctx.write(help()); return 0 }
                arg.startsWith("--color=") -> color = arg.substring(8)
                arg == "--color" -> color = "always"
                arg == "-i" || arg == "--ignore-case" -> ignoreCase = true
                arg == "-v" || arg == "--invert-match" -> invert = true
                arg == "-n" || arg == "--line-number" -> lineNumber = true
                arg == "-c" || arg == "--count" -> countOnly = true
                arg == "-w" || arg == "--word-regexp" -> wordMatch = true
                arg == "-r" || arg == "-R" || arg == "--recursive" -> recursiveLocal = true
                arg == "-H" -> showFilename = true
                arg == "-h" -> noFilename = true
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.drop(1)) when (c) {
                        'i' -> ignoreCase = true
                        'v' -> invert = true
                        'n' -> lineNumber = true
                        'c' -> countOnly = true
                        'w' -> wordMatch = true
                        'r', 'R' -> recursiveLocal = true
                        'H' -> showFilename = true
                        'h' -> noFilename = true
                        'E' -> extendedRegex = true
                        'F' -> fixedString = true
                        else -> { ctx.writeErrln("grep: invalid option -- '$c'"); return 2 }
                    }
                }
                else -> nonFlagArgs.add(arg)
            }
            i++
        }

        if (nonFlagArgs.isEmpty()) { ctx.writeErrln("grep: missing pattern"); return 2 }
        val pattern = nonFlagArgs[0]
        val files = nonFlagArgs.drop(1).ifEmpty { listOf("-") }

        // بناء الـ regex
        val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE else 0
        val finalPattern = when {
            fixedString -> Pattern.quote(pattern)
            wordMatch -> "\\b" + pattern + "\\b"
            else -> pattern
        }
        val regex = try {
            Pattern.compile(finalPattern, flags)
        } catch (e: Exception) {
            ctx.writeErrln("grep: invalid pattern: ${e.message}")
            return 2
        }

        var totalMatches = 0
        var anyMatched = false

        for (filePath in files) {
            val (filename, stream) = if (filePath == "-") "-" to ctx.stdin else filePath to null
            val reader: java.io.BufferedReader = if (stream != null) stream.bufferedReader(Charsets.UTF_8)
            else File(if (filePath.startsWith("/")) filePath else File(ctx.workingDirectory, filePath).absolutePath).bufferedReader(Charsets.UTF_8)

            try {
                var line = reader.readLine()
                var lineNum = 0
                var fileMatches = 0
                while (line != null) {
                    lineNum++
                    val matched = regex.matcher(line).find()
                    val shouldPrint = if (invert) !matched else matched
                    if (shouldPrint) {
                        anyMatched = true
                        totalMatches++
                        fileMatches++
                        if (!countOnly) {
                            val sb = StringBuilder()
                            if (showFilename || (files.size > 1 && !noFilename && !recursiveLocal)) sb.append("$filename:")
                            if (lineNumber) sb.append("$lineNum:")
                            sb.append(highlightMatch(line, regex, color))
                            sb.append("\r\n")
                            ctx.write(sb.toString())
                        }
                    }
                    line = reader.readLine()
                }
                if (countOnly) ctx.writeln("$filename:$fileMatches")
            } catch (e: Exception) {
                ctx.writeErrln("grep: $filePath: ${e.message}")
            } finally {
                try { reader.close() } catch (_: Exception) {}
            }
        }

        return if (anyMatched) 0 else 1
    }

    private fun highlightMatch(line: String, regex: Pattern, color: String): String {
        if (color == "never") return line
        val m = regex.matcher(line)
        val sb = StringBuilder()
        var last = 0
        while (m.find()) {
            sb.append(line, last, m.start())
            sb.append("\u001B[1;31m")
            sb.append(line, m.start(), m.end())
            sb.append("\u001B[0m")
            last = m.end()
        }
        sb.append(line, last, line.length)
        return sb.toString()
    }

    override fun help() = "grep — print lines matching a pattern\nUsage: grep [OPTIONS] PATTERN [FILE...]"
}

/** sed — محرّر تيار (stream editor) */
class SedCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("sed: missing script"); return 1 }
        var script = ""
        val files = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-e" && i + 1 < args.size -> { script += ";" + args[++i] }
                args[i] == "-n" -> {}
                args[i].startsWith("-") -> {}
                script.isEmpty() -> script = args[i]
                else -> files.add(args[i])
            }
            i++
        }

        // تبسيط: فقط s/pattern/replacement/[g]
        val sRegex = Regex("""^s(.)(.+?)\1(.+?)\1([gpi]*)$""")
        val match = sRegex.find(script) ?: run {
            ctx.writeErrln("sed: only s/// command supported in this version")
            return 1
        }
        val (delim, search, replace, flags) = match.destructured
        val global = flags.contains('g')
        val ignoreCase = flags.contains('i')
        val pattern = try {
            Pattern.compile(search, if (ignoreCase) Pattern.CASE_INSENSITIVE else 0)
        } catch (e: Exception) {
            ctx.writeErrln("sed: invalid regex: ${e.message}")
            return 1
        }

        val sources = if (files.isEmpty()) listOf("-") else files
        for (src in sources) {
            try {
                val reader: java.io.BufferedReader = when (src) {
                    "-" -> ctx.stdin.bufferedReader(Charsets.UTF_8)
                    else -> File(if (src.startsWith("/")) src else File(ctx.workingDirectory, src).absolutePath).bufferedReader(Charsets.UTF_8)
                }
                var line = reader.readLine()
                while (line != null) {
                    ctx.writeln(applySed(line, pattern, replace, global))
                    line = reader.readLine()
                }
                reader.close()
            } catch (e: Exception) {
                ctx.writeErrln("sed: $src: ${e.message}")
            }
        }
        return 0
    }

    private fun applySed(line: String, pattern: Pattern, replacement: String, global: Boolean): String {
        val m = pattern.matcher(line)
        if (global) return m.replaceAll(replacement)
        return m.replaceFirst(replacement)
    }

    override fun help() = "sed — stream editor\nUsage: sed 's/PATTERN/REPLACE/[g]' [FILE...]"
}

/** awk — لغة معالجة نصوص (تبسيط) */
class AwkCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("awk: missing program"); return 1 }
        val program = args[0]
        val files = args.drop(1).ifEmpty { listOf("-") }

        // تبسيط: ندعم {print $1, $2, ...} و {print NR, $0} فقط
        val printRegex = Regex("""\{?\s*print\s+(.*)\s*\}?""")
        val match = printRegex.find(program)
        if (match == null) {
            ctx.writeErrln("awk: this version only supports '{print ...}'")
            return 1
        }
        val printArgs = match.groupValues[1].split(",").map { it.trim() }

        for (src in files) {
            try {
                val reader: java.io.BufferedReader = when (src) {
                    "-" -> ctx.stdin.bufferedReader(Charsets.UTF_8)
                    else -> File(if (src.startsWith("/")) src else File(ctx.workingDirectory, src).absolutePath).bufferedReader(Charsets.UTF_8)
                }
                var nr = 0
                var line = reader.readLine()
                while (line != null) {
                    nr++
                    val fields = line.split(Regex("\\s+"))
                    val sb = StringBuilder()
                    for (arg in printArgs) {
                        val v = when {
                            arg == "$0" -> line
                            arg == "NR" -> nr.toString()
                            arg.startsWith("$") -> {
                                val n = arg.substring(1).toIntOrNull() ?: 0
                                if (n == 0) line else fields.getOrNull(n - 1) ?: ""
                            }
                            arg.startsWith("\"") && arg.endsWith("\"") -> arg.substring(1, arg.length - 1)
                            else -> arg
                        }
                        sb.append(v).append(" ")
                    }
                    ctx.writeln(sb.toString().trimEnd())
                    line = reader.readLine()
                }
                reader.close()
            } catch (e: Exception) {
                ctx.writeErrln("awk: $src: ${e.message}")
            }
        }
        return 0
    }
}

/** head — أول N سطر */
class HeadCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var n = 10
        val files = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-n" && i + 1 < args.size -> n = args[++i].toIntOrNull() ?: 10
                args[i].startsWith("-n") -> n = args[i].substring(2).toIntOrNull() ?: 10
                args[i].startsWith("-") && args[i].length > 1 && args[i].all { it == '-' || it.isDigit() } ->
                    n = args[i].substring(1).toIntOrNull() ?: 10
                else -> files.add(args[i])
            }
            i++
        }
        val sources = if (files.isEmpty()) listOf("-") else files
        for ((idx, src) in sources.withIndex()) {
            try {
                if (sources.size > 1) ctx.writeln("${if (idx > 0) "\r\n" else ""}==> $src <==")
                val reader: java.io.BufferedReader = when (src) {
                    "-" -> ctx.stdin.bufferedReader(Charsets.UTF_8)
                    else -> File(if (src.startsWith("/")) src else File(ctx.workingDirectory, src).absolutePath).bufferedReader(Charsets.UTF_8)
                }
                var count = 0
                var line = reader.readLine()
                while (line != null && count < n) {
                    ctx.writeln(line)
                    count++
                    line = reader.readLine()
                }
                reader.close()
            } catch (e: Exception) { ctx.writeErrln("head: $src: ${e.message}") }
        }
        return 0
    }
}

/** tail — آخر N سطر */
class TailCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var n = 10
        var follow = false
        val files = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-n" && i + 1 < args.size -> n = args[++i].toIntOrNull() ?: 10
                args[i].startsWith("-n") -> n = args[i].substring(2).toIntOrNull() ?: 10
                args[i] == "-f" -> follow = true
                args[i].startsWith("-") && args[i].length > 1 && args[i].all { it == '-' || it.isDigit() } ->
                    n = args[i].substring(1).toIntOrNull() ?: 10
                else -> files.add(args[i])
            }
            i++
        }
        val sources = if (files.isEmpty()) listOf("-") else files
        for (src in sources) {
            try {
                val reader: java.io.BufferedReader = when (src) {
                    "-" -> ctx.stdin.bufferedReader(Charsets.UTF_8)
                    else -> File(if (src.startsWith("/")) src else File(ctx.workingDirectory, src).absolutePath).bufferedReader(Charsets.UTF_8)
                }
                val lines = reader.readLines()
                val start = (lines.size - n).coerceAtLeast(0)
                for (line in lines.subList(start, lines.size)) ctx.writeln(line)
                reader.close()
            } catch (e: Exception) { ctx.writeErrln("tail: $src: ${e.message}") }
        }
        return 0
    }
}

/** wc — عدّاد الكلمات */
class WcCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var countLines = false; var countWords = false; var countChars = false; var countBytes = false
        val files = mutableListOf<String>()
        for (arg in args) {
            when {
                arg == "--help" -> { ctx.write(help()); return 0 }
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.drop(1)) when (c) { 'l' -> countLines = true; 'w' -> countWords = true; 'c' -> countBytes = true; 'm' -> countChars = true; else -> {} }
                }
                else -> files.add(arg)
            }
        }
        if (!countLines && !countWords && !countChars && !countBytes) { countLines = true; countWords = true; countBytes = true }
        val sources = if (files.isEmpty()) listOf("-") else files
        for (src in sources) {
            try {
                val reader: java.io.BufferedReader = when (src) {
                    "-" -> ctx.stdin.bufferedReader(Charsets.UTF_8)
                    else -> File(if (src.startsWith("/")) src else File(ctx.workingDirectory, src).absolutePath).bufferedReader(Charsets.UTF_8)
                }
                val content = reader.readText()
                reader.close()
                val lines = content.count { it == '\n' }
                val words = content.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                val bytes = content.toByteArray(Charsets.UTF_8).size
                val sb = StringBuilder()
                if (countLines) sb.append(String.format("%8d", lines))
                if (countWords) sb.append(String.format(" %8d", words))
                if (countBytes) sb.append(String.format(" %8d", bytes))
                if (countChars) sb.append(String.format(" %8d", content.length))
                if (src != "-") sb.append(" $src")
                ctx.writeln(sb.toString())
            } catch (e: Exception) { ctx.writeErrln("wc: $src: ${e.message}") }
        }
        return 0
    }
    override fun help() = "wc — word count\nUsage: wc [-l] [-w] [-c] [FILE...]"
}

/** sort — فرز الأسطر */
class SortCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var reverse = false; var numeric = false; var unique = false; var ignoreCase = false
        val files = mutableListOf<String>()
        for (arg in args) {
            when {
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.drop(1)) when (c) { 'r' -> reverse = true; 'n' -> numeric = true; 'u' -> unique = true; 'f' -> ignoreCase = true; else -> {} }
                }
                else -> files.add(arg)
            }
        }
        val lines = mutableListOf<String>()
        if (files.isEmpty()) {
            ctx.stdin.bufferedReader(Charsets.UTF_8).use { lines.addAll(it.readLines()) }
        } else {
            for (src in files) {
                try {
                    File(if (src.startsWith("/")) src else File(ctx.workingDirectory, src).absolutePath).bufferedReader(Charsets.UTF_8).use { lines.addAll(it.readLines()) }
                } catch (e: Exception) { ctx.writeErrln("sort: $src: ${e.message}") }
            }
        }
        var sorted = if (numeric) lines.sortedBy { it.toDoubleOrNull() ?: 0.0 }
                     else if (ignoreCase) lines.sortedBy { it.lowercase() }
                     else lines.sorted()
        if (reverse) sorted = sorted.reversed()
        if (unique) sorted = sorted.distinct()
        sorted.forEach { ctx.writeln(it) }
        return 0
    }
}

/** uniq — حذف الأسطر المكررة المتجاورة */
class UniqCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var countOnly = false; var onlyDup = false; var onlyUnique = false
        val files = mutableListOf<String>()
        for (arg in args) {
            when {
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.drop(1)) when (c) { 'c' -> countOnly = true; 'd' -> onlyDup = true; 'u' -> onlyUnique = true; else -> {} }
                }
                else -> files.add(arg)
            }
        }
        val lines = if (files.isEmpty()) ctx.stdin.bufferedReader(Charsets.UTF_8).use { it.readLines() }
                    else File(if (files[0].startsWith("/")) files[0] else File(ctx.workingDirectory, files[0]).absolutePath).bufferedReader(Charsets.UTF_8).use { it.readLines() }
        var prev: String? = null
        var count = 0
        for (line in lines) {
            if (line == prev) count++
            else {
                if (prev != null) {
                    val show = (count == 1 && !onlyDup) || (count > 1 && !onlyUnique)
                    if (show) ctx.writeln(if (countOnly) "${String.format("%7d", count)} $prev" else prev)
                }
                prev = line; count = 1
            }
        }
        if (prev != null) {
            val show = (count == 1 && !onlyDup) || (count > 1 && !onlyUnique)
            if (show) ctx.writeln(if (countOnly) "${String.format("%7d", count)} $prev" else prev)
        }
        return 0
    }
}

/** cut — استخراج أعمدة */
class CutCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var delim = "\t"; var fields: List<Int>? = null; var chars: List<Int>? = null
        val files = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-d" && i + 1 < args.size -> delim = args[++i]
                args[i].startsWith("-d") -> delim = args[i].substring(2)
                args[i] == "-f" && i + 1 < args.size -> fields = parseRange(args[++i])
                args[i].startsWith("-f") -> fields = parseRange(args[i].substring(2))
                args[i] == "-c" && i + 1 < args.size -> chars = parseRange(args[++i])
                args[i].startsWith("-c") -> chars = parseRange(args[i].substring(2))
                else -> files.add(args[i])
            }
            i++
        }
        val sources = if (files.isEmpty()) listOf("-") else files
        for (src in sources) {
            try {
                val reader: java.io.BufferedReader = when (src) {
                    "-" -> ctx.stdin.bufferedReader(Charsets.UTF_8)
                    else -> File(if (src.startsWith("/")) src else File(ctx.workingDirectory, src).absolutePath).bufferedReader(Charsets.UTF_8)
                }
                var line = reader.readLine()
                while (line != null) {
                    if (fields != null) {
                        val parts = line.split(delim)
                        val result = fields.filter { it in 1..parts.size }.map { parts[it - 1] }
                        ctx.writeln(result.joinToString(delim))
                    } else if (chars != null) {
                        val sb = StringBuilder()
                        for (c in chars) if (c in 1..line.length) sb.append(line[c - 1])
                        ctx.writeln(sb.toString())
                    } else ctx.writeln(line)
                    line = reader.readLine()
                }
                reader.close()
            } catch (e: Exception) { ctx.writeErrln("cut: $src: ${e.message}") }
        }
        return 0
    }

    private fun parseRange(s: String): List<Int> {
        val result = mutableListOf<Int>()
        for (part in s.split(",")) {
            if (part.contains("-")) {
                val (a, b) = part.split("-").let { it[0].toInt() to (it.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE) }
                result.addAll((a..b).toList())
            } else result.add(part.toInt())
        }
        return result
    }
}

/** tr — تحويل/حذف أحرف */
class TrCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var delete = false; var squeeze = false
        val sets = mutableListOf<String>()
        for (arg in args) {
            when {
                arg == "-d" -> delete = true
                arg == "-s" -> squeeze = true
                !arg.startsWith("-") -> sets.add(arg)
            }
        }
        if (sets.isEmpty()) { ctx.writeErrln("tr: missing operand"); return 1 }
        val input = ctx.stdin.bufferedReader(Charsets.UTF_8).readText()
        if (delete) {
            val toDelete = sets[0].toSet()
            ctx.write(input.filter { it !in toDelete })
        } else if (sets.size >= 2) {
            val from = sets[0]
            val to = sets[1]
            val sb = StringBuilder()
            for (c in input) {
                val idx = from.indexOf(c)
                if (idx >= 0 && idx < to.length) sb.append(to[idx])
                else if (idx >= 0 && to.isNotEmpty()) sb.append(to.last())
                else sb.append(c)
            }
            ctx.write(sb.toString())
        } else ctx.write(input)
        return 0
    }
}

/** tee — كتابة إلى stdout وملف */
class TeeCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var append = false
        val files = mutableListOf<String>()
        for (arg in args) { if (arg == "-a") append = true else files.add(arg) }
        val data = ctx.stdin.bufferedReader(Charsets.UTF_8).readText()
        ctx.write(data)
        for (path in files) {
            try {
                val f = if (path.startsWith("/")) File(path) else File(ctx.workingDirectory, path)
                f.parentFile?.mkdirs()
                f.appendText(data)
            } catch (e: Exception) { ctx.writeErrln("tee: $path: ${e.message}") }
        }
        return 0
    }
}

/** diff — الفرق بين ملفين (تبسيط) */
class DiffCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.size < 2) { ctx.writeErrln("diff: need two files"); return 2 }
        val f1 = File(if (args[0].startsWith("/")) args[0] else File(ctx.workingDirectory, args[0]).absolutePath)
        val f2 = File(if (args[1].startsWith("/")) args[1] else File(ctx.workingDirectory, args[1]).absolutePath)
        if (!f1.exists() || !f2.exists()) { ctx.writeErrln("diff: file not found"); return 2 }
        val lines1 = f1.readLines()
        val lines2 = f2.readLines()
        var different = false
        val maxLen = maxOf(lines1.size, lines2.size)
        for (i in 0 until maxLen) {
            val l1 = lines1.getOrNull(i)
            val l2 = lines2.getOrNull(i)
            if (l1 != l2) {
                different = true
                l1?.let { ctx.writeln("< $it") }
                ctx.writeln("---")
                l2?.let { ctx.writeln("> $it") }
            }
        }
        return if (different) 1 else 0
    }
}

/** paste — دمج الملفات عمودياً */
class PasteCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val files = if (args.isEmpty()) listOf("-") else args
        val readers = files.map { src ->
            when (src) {
                "-" -> ctx.stdin.bufferedReader(Charsets.UTF_8)
                else -> File(if (src.startsWith("/")) src else File(ctx.workingDirectory, src).absolutePath).bufferedReader(Charsets.UTF_8)
            }
        }
        while (true) {
            val lines = readers.map { it.readLine() }
            if (lines.all { it == null }) break
            ctx.writeln(lines.map { it ?: "" }.joinToString("\t"))
        }
        readers.forEach { it.close() }
        return 0
    }
}

/** rev — عكس الأحرف في كل سطر */
class RevCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val input = if (args.isEmpty()) ctx.stdin.bufferedReader(Charsets.UTF_8)
                    else File(if (args[0].startsWith("/")) args[0] else File(ctx.workingDirectory, args[0]).absolutePath).bufferedReader(Charsets.UTF_8)
        var line = input.readLine()
        while (line != null) { ctx.writeln(line.reversed()); line = input.readLine() }
        input.close()
        return 0
    }
}

/** nl — ترقيم الأسطر */
class NlCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val input = if (args.isEmpty()) ctx.stdin.bufferedReader(Charsets.UTF_8)
                    else File(if (args[0].startsWith("/")) args[0] else File(ctx.workingDirectory, args[0]).absolutePath).bufferedReader(Charsets.UTF_8)
        var n = 0
        var line = input.readLine()
        while (line != null) {
            n++
            ctx.writeln(String.format("%6d  %s", n, line))
            line = input.readLine()
        }
        input.close()
        return 0
    }
}

/** tac — cat معكوس (آخر سطر أولاً) */
class TacCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val input = if (args.isEmpty()) ctx.stdin.bufferedReader(Charsets.UTF_8)
                    else File(if (args[0].startsWith("/")) args[0] else File(ctx.workingDirectory, args[0]).absolutePath).bufferedReader(Charsets.UTF_8)
        val lines = input.readLines().asReversed()
        lines.forEach { ctx.writeln(it) }
        input.close()
        return 0
    }
}

/** basename — استخراج اسم الملف من المسار */
class BasenameCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("basename: missing operand"); return 1 }
        var name = File(args[0]).name
        if (args.size > 1) name = name.removeSuffix(args[1])
        ctx.writeln(name)
        return 0
    }
}

/** dirname — استخراج اسم المجلد من المسار */
class DirnameCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.writeErrln("dirname: missing operand"); return 1 }
        ctx.writeln(File(args[0]).parent ?: ".")
        return 0
    }
}

/** realpath — المسار المطلق */
class RealpathCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        for (arg in args) {
            val f = if (arg.startsWith("/")) File(arg) else File(ctx.workingDirectory, arg)
            ctx.writeln(f.absolutePath)
        }
        return 0
    }
}

/** xxd — dump سداسي عشري */
class XxdCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val data = if (args.isEmpty()) ctx.stdin.readBytes()
                   else File(if (args[0].startsWith("/")) args[0] else File(ctx.workingDirectory, args[0]).absolutePath).readBytes()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + 16, data.size)
            val hex = (offset until end).joinToString(" ") { String.format("%02x", data[it]) }
            val ascii = (offset until end).joinToString("") {
                val c = data[it].toInt().toChar()
                if (c in ' '..'~') c.toString() else "."
            }
            ctx.writeln(String.format("%08x: %-48s %s", offset, hex, ascii))
            offset += 16
        }
        return 0
    }
}

/** od — dump ثماني */
class OdCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val data = if (args.isEmpty()) ctx.stdin.readBytes()
                   else File(if (args[0].startsWith("/")) args[0] else File(ctx.workingDirectory, args[0]).absolutePath).readBytes()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + 16, data.size)
            val oct = (offset until end).joinToString(" ") { String.format("%03o", data[it]) }
            ctx.writeln(String.format("%07o %s", offset, oct))
            offset += 16
        }
        return 0
    }
}

/** column — تنسيق في أعمدة */
class ColumnCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var delim = "\t"
        val files = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-s" && i + 1 < args.size -> delim = args[++i]
                args[i].startsWith("-") -> {}
                else -> files.add(args[i])
            }
            i++
        }
        val input = if (files.isEmpty()) ctx.stdin.bufferedReader(Charsets.UTF_8)
                    else File(if (files[0].startsWith("/")) files[0] else File(ctx.workingDirectory, files[0]).absolutePath).bufferedReader(Charsets.UTF_8)
        val rows = input.readLines().map { it.split(delim) }
        input.close()
        val maxCols = rows.maxOf { it.size }
        val colWidths = IntArray(maxCols) { c -> rows.maxOf { it.getOrNull(c)?.length ?: 0 } }
        for (row in rows) {
            val sb = StringBuilder()
            for ((i, cell) in row.withIndex()) {
                sb.append(cell)
                if (i < row.size - 1) sb.append(" ".repeat(colWidths[i] - cell.length + 2))
            }
            ctx.writeln(sb.toString())
        }
        return 0
    }
}

/** fold — طي الأسطر */
class FoldCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        var width = 80
        val files = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-w" && i + 1 < args.size -> width = args[++i].toIntOrNull() ?: 80
                args[i].startsWith("-w") -> width = args[i].substring(2).toIntOrNull() ?: 80
                args[i].startsWith("-") -> {}
                else -> files.add(args[i])
            }
            i++
        }
        val input = if (files.isEmpty()) ctx.stdin.bufferedReader(Charsets.UTF_8)
                    else File(if (files[0].startsWith("/")) files[0] else File(ctx.workingDirectory, files[0]).absolutePath).bufferedReader(Charsets.UTF_8)
        var line = input.readLine()
        while (line != null) {
            var i2 = 0
            while (i2 < line.length) {
                ctx.writeln(line.substring(i2, minOf(i2 + width, line.length)))
                i2 += width
            }
            line = input.readLine()
        }
        input.close()
        return 0
    }
}

/** expand — تحويل tabs إلى مسافات */
class ExpandCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val input = if (args.isEmpty()) ctx.stdin.bufferedReader(Charsets.UTF_8)
                    else File(if (args[0].startsWith("/")) args[0] else File(ctx.workingDirectory, args[0]).absolutePath).bufferedReader(Charsets.UTF_8)
        var line = input.readLine()
        while (line != null) { ctx.writeln(line.replace("\t", "    ")); line = input.readLine() }
        input.close()
        return 0
    }
}

/** unexpand — تحويل المسافات إلى tabs */
class UnexpandCommand : CommandExecutor {
    override fun execute(args: List<String>, ctx: ShellContext): Int {
        val input = if (args.isEmpty()) ctx.stdin.bufferedReader(Charsets.UTF_8)
                    else File(if (args[0].startsWith("/")) args[0] else File(ctx.workingDirectory, args[0]).absolutePath).bufferedReader(Charsets.UTF_8)
        var line = input.readLine()
        while (line != null) { ctx.writeln(line.replace("    ", "\t")); line = input.readLine() }
        input.close()
        return 0
    }
}
