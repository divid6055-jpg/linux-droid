package com.linuxdroid.shell

import android.content.Context
import com.linuxdroid.commands.CommandExecutor
import com.linuxdroid.commands.CommandRegistry
import com.linuxdroid.commands.ShellContext
import com.linuxdroid.util.LinuxDroidLogger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * مفسّر Shell شبيه بـ Bash.
 *
 * الميزات المدعومة:
 * 1. تنفيذ أوامر متعددة مفصولة بـ ; أو && أو ||
 * 2. الأنابيب (pipes): cmd1 | cmd2 | cmd3
 * 3. إعادة التوجيه (redirection): > >> < 2> &>
 * 4. متغيرات البيئة: export, unset, $VAR, ${VAR}, $? $!
 * 5. الأوامر المدمجة (builtins): cd, pwd, export, unset, alias, source, exit
 * 6. الاقتباس: '...' و "..." و \escape
 * 7. البدل (globbing): * ? [abc]
 * 8. التوسع: ~, $VAR, $(cmd), `cmd`
 * 9. التعليقات: #
 * 10. التاريخ (history): سهم ↑/↓ (مُدار من الطبقة الأعلى)
 * 11. الإكمال (Tab completion): يُدعى من الطبقة الأعلى
 * 12. التحكم في الوظائف: &, jobs, fg, bg, ctrl+z (تبسيط)
 *
 * الخيط: يعمل في خيط منفصل ويقرأ سطراً سطراً من [inputStream].
 */
class ShellInterpreter(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private var columns: Int,
    private var rows: Int,
    private val context: Context,
    private val sessionId: String
) {
    private val running = AtomicBoolean(false)
    private val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))

    /** سياق تنفيذ الأوامر */
    val shellContext = ShellContext(
        context = context,
        environment = Environment.defaultEnv.toMutableMap(),
        workingDirectory = Environment.homeDir.absolutePath,
        stdin = inputStream,
        stdout = outputStream,
        stderr = outputStream,
        sessionId = sessionId
    )

    /** مدير التاريخ (يدعم التنقل بالأسهم والبحث) */
    private val historyManager = HistoryManager(java.io.File(Environment.homeDir, ".bash_history"))

    /** مُكمل Tab */
    private val tabCompleter = TabCompleter(shellContext.environment, shellContext.workingDirectory)

    private val history = mutableListOf<String>()
    private val aliases = mutableMapOf<String, String>()

    fun start() {
        if (running.getAndSet(true)) return
        LinuxDroidLogger.i(TAG, "Shell started for session $sessionId")
        try {
            printPrompt()
            writer.flush()
            var line: String?
            while (running.get()) {
                line = readLogicalLine()
                if (line == null) break
                if (line.isBlank()) {
                    printPrompt()
                    writer.flush()
                    continue
                }
                history.add(line)
                historyManager.add(line) // SECURITY: تم فلترة الأوامر الحساسة
                try {
                    executeLine(line)
                } catch (e: ExitException) {
                    write("\r\n")
                    break
                } catch (e: Exception) {
                    // SECURITY: نسجّل اسم الأمر فقط دون المعطيات (قد تحوي أسراراً)
                    val cmdName = line.trim().split(Regex("\\s+")).firstOrNull() ?: "unknown"
                    LinuxDroidLogger.e(TAG, "Error executing command '$cmdName': ${e.message}", e)
                    writeError("bash: ${e.message}\r\n")
                }
                printPrompt()
                writer.flush()
            }
        } catch (e: IOException) {
            if (running.get()) LinuxDroidLogger.w(TAG, "Shell I/O error: ${e.message}")
        } finally {
            running.set(false)
            LinuxDroidLogger.i(TAG, "Shell stopped for session $sessionId")
        }
    }

    /** قراءة سطر منطقي — قد يمتد على عدة أسطر في حالة عدم اكتمال اقتباس.
     *
     * يدعم:
     *  - Tab (0x09) للإكمال التلقائي
     *  - أسهم ↑/↓ لتاريخ الأوامر
     *  - أسهم ←/→ للحركة داخل السطر (تبسيط: الحركة فقط)
     *  - Ctrl+R للبحث العكسي (تبسيط)
     *  - Ctrl+C لإلغاء السطر الحالي
     *  - Ctrl+D للإنهاء (EOF) إذا كان السطر فارغاً
     */
    private fun readLogicalLine(): String? {
        val sb = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var escape = false
        var needsMore = false
        var cursorPos = 0

        while (running.get()) {
            val c = reader.read()
            if (c == -1) return if (sb.isNotEmpty()) sb.toString() else null
            val ch = c.toChar()

            // معالجة تسلسلات ESC (ANSI)
            if (ch == 0x1B.toChar()) {
                val seq = readEscapeSequence()
                handleAnsiInput(seq, sb, cursorPos)
                when (seq) {
                    "ARROW_UP" -> {
                        val prev = historyManager.previous()
                        if (prev != null) {
                            // امسح السطر الحالي واكتب السابق
                            clearLine(sb.length)
                            sb.clear()
                            sb.append(prev)
                            cursorPos = sb.length
                            write(prev)
                            writer.flush()
                        }
                    }
                    "ARROW_DOWN" -> {
                        val next = historyManager.next()
                        clearLine(sb.length)
                        sb.clear()
                        if (next != null) {
                            sb.append(next)
                            cursorPos = sb.length
                            write(next)
                        } else {
                            cursorPos = 0
                        }
                        writer.flush()
                    }
                }
                continue
            }

            // Ctrl+C (0x03)
            if (c == 0x03) {
                write("^C\r\n")
                return ""
            }

            // Ctrl+D (0x04) عند سطر فارغ
            if (c == 0x04 && sb.isEmpty()) {
                write("\r\n")
                throw ExitException()
            }

            // Tab (0x09) للإكمال
            if (c == 0x09) {
                val line = sb.toString()
                val result = tabCompleter.complete(line, cursorPos)
                if (result.addition.isNotEmpty()) {
                    sb.insert(cursorPos, result.addition)
                    cursorPos += result.addition.length
                    // أعد رسم السطر
                    clearLine(line.length)
                    write(sb.toString())
                    // حرك المؤشر إلى الموضع الصحيح (تبسيط: نهاية السطر)
                    writer.flush()
                } else if (result.completions.isNotEmpty()) {
                    // اعرض الخيارات في سطر جديد ثم أعد رسم السطر
                    write("\r\n")
                    val display = result.completions.joinToString("  ")
                    // لف الخيارات حسب عرض الشاشة
                    val cols = shellContext.columns.coerceAtLeast(40)
                    val sb2 = StringBuilder()
                    var lineLen = 0
                    for (comp in result.completions) {
                        if (lineLen + comp.length + 2 > cols) {
                            sb2.append("\r\n")
                            lineLen = 0
                        }
                        sb2.append(comp).append("  ")
                        lineLen += comp.length + 2
                    }
                    write(sb2.toString() + "\r\n")
                    printPrompt()
                    write(sb.toString())
                    writer.flush()
                }
                continue
            }

            if (escape) {
                sb.append('\\').append(ch)
                escape = false
                continue
            }

            when {
                inSingleQuote -> {
                    sb.append(ch)
                    if (ch == '\'') inSingleQuote = false
                    cursorPos++
                }
                inDoubleQuote -> {
                    sb.append(ch)
                    if (ch == '"') inDoubleQuote = false
                    cursorPos++
                }
                ch == '\\' -> {
                    escape = true
                    needsMore = true
                }
                ch == '\'' -> {
                    inSingleQuote = true
                    sb.append(ch)
                    cursorPos++
                    needsMore = true
                }
                ch == '"' -> {
                    inDoubleQuote = true
                    sb.append(ch)
                    cursorPos++
                    needsMore = true
                }
                ch == '\r' -> { /* تجاهل */ }
                ch == '\n' -> {
                    if (needsMore) {
                        sb.append('\n')
                        write("> ") // PS2 prompt
                        writer.flush()
                        needsMore = false
                        cursorPos++
                    } else {
                        return sb.toString()
                    }
                }
                ch == 0x7F.toChar() || ch == 0x08.toChar() -> {
                    // Backspace
                    if (cursorPos > 0) {
                        sb.deleteCharAt(cursorPos - 1)
                        cursorPos--
                        clearLine(sb.length + 1)
                        write(sb.toString())
                        writer.flush()
                    }
                }
                else -> {
                    sb.append(ch)
                    cursorPos++
                }
            }
        }
        return if (sb.isNotEmpty()) sb.toString() else null
    }

    /** يقرأ تسلسل ESC ويُعيد رمزاً بسيطاً */
    private fun readEscapeSequence(): String {
        // ESC [ A/B/C/D  ←/→/↑/↓
        // ESC O A/B/C/D  ←/→/↑/↓ (في وضع التطبيق)
        val c1 = reader.read()
        if (c1 == -1) return ""
        when (c1.toChar()) {
            '[' -> {
                val c2 = reader.read()
                if (c2 == -1) return ""
                when (c2.toChar()) {
                    'A' -> return "ARROW_UP"
                    'B' -> return "ARROW_DOWN"
                    'C' -> return "ARROW_RIGHT"
                    'D' -> return "ARROW_LEFT"
                    'H' -> return "HOME"
                    'F' -> return "END"
                    '1', '2', '3', '4', '5', '6', '7', '8' -> {
                        // ESC [ N ~ (Page Up/Down, Insert, Delete, etc.)
                        val tilde = reader.read()
                        if (tilde == '~'.code) {
                            return when (c2.toChar()) {
                                '5' -> "PAGE_UP"
                                '6' -> "PAGE_DOWN"
                                '2' -> "INSERT"
                                '3' -> "DELETE"
                                '1' -> "HOME"
                                '4' -> "END"
                                else -> ""
                            }
                        }
                        return ""
                    }
                    else -> return ""
                }
            }
            'O' -> {
                val c2 = reader.read()
                if (c2 == -1) return ""
                return when (c2.toChar()) {
                    'A' -> "ARROW_UP"
                    'B' -> "ARROW_DOWN"
                    'C' -> "ARROW_RIGHT"
                    'D' -> "ARROW_LEFT"
                    'H' -> "HOME"
                    'F' -> "END"
                    else -> ""
                }
            }
            else -> return ""
        }
    }

    /** معالجة إدخال ANSI (تبسيط: فقط أسهم اليسار/اليمين/Home/End/Delete) */
    private fun handleAnsiInput(seq: String, sb: StringBuilder, cursorPos: Int): Int {
        // تبسيط: نتجاهلها حالياً (التنقل الكامل داخل السطر معقد)
        return cursorPos
    }

    /** يمسح السطر الحالي ويعيد المؤشر إلى بدايته */
    private fun clearLine(currentLength: Int) {
        write("\r\u001B[K")  // CR + erase to end of line
    }

    private fun printPrompt() {
        val user = shellContext.environment["USER"] ?: "user"
        val host = shellContext.environment["HOSTNAME"] ?: "linuxdroid"
        val cwd = shellContext.workingDirectory
        val displayCwd = if (cwd.startsWith(Environment.homeDir.absolutePath)) {
            "~" + cwd.removePrefix(Environment.homeDir.absolutePath)
        } else cwd
        val prompt = "\u001B[1;32m$user@$host\u001B[0m:\u001B[1;34m$displayCwd\u001B[0m$ "
        write(prompt)
    }

    /** تنفيذ سطر أوامر كامل */
    fun executeLine(line: String) {
        // 1. تقسيم حسب ; & && || مع الحفاظ على الفواصل
        val commandChains = splitByLogicalOperators(line)
        for (chain in commandChains) {
            if (!running.get()) break
            val (operator, cmd) = chain
            if (cmd.isBlank()) continue
            val exitCode = executePipeline(cmd)
            shellContext.environment["?"] = exitCode.toString()
            // اقطع السلسلة إذا لزم
            if (operator == "&&" && exitCode != 0) break
            if (operator == "||" && exitCode == 0) {
                // || يكمل فقط إذا فشل السابق — نتجاوز لـ التالي
            }
        }
    }

    private data class CommandChain(val operator: String, val command: String)

    private fun splitByLogicalOperators(line: String): List<CommandChain> {
        val result = mutableListOf<CommandChain>()
        val sb = StringBuilder()
        var i = 0
        var inSingle = false; var inDouble = false; var escape = false
        var currentOperator = ";"

        while (i < line.length) {
            val c = line[i]
            if (escape) { sb.append(c); escape = false; i++; continue }
            when {
                inSingle -> { sb.append(c); if (c == '\'') inSingle = false }
                inDouble -> { sb.append(c); if (c == '"') inDouble = false }
                c == '\\' -> { sb.append(c); escape = true }
                c == '\'' -> { sb.append(c); inSingle = true }
                c == '"' -> { sb.append(c); inDouble = true }
                c == '&' && i + 1 < line.length && line[i + 1] == '&' -> {
                    result.add(CommandChain(currentOperator, sb.toString().trim()))
                    currentOperator = "&&"
                    sb.clear()
                    i += 2; continue
                }
                c == '|' && i + 1 < line.length && line[i + 1] == '|' -> {
                    result.add(CommandChain(currentOperator, sb.toString().trim()))
                    currentOperator = "||"
                    sb.clear()
                    i += 2; continue
                }
                c == ';' -> {
                    result.add(CommandChain(currentOperator, sb.toString().trim()))
                    currentOperator = ";"
                    sb.clear()
                    i++; continue
                }
                c == '&' -> {
                    // & في نهاية الأمر — تشغيل في الخلفية (تبسيط: نتجاهل)
                    i++; continue
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.toString().trim().isNotEmpty()) {
            result.add(CommandChain(currentOperator, sb.toString().trim()))
        }
        return result
    }

    /** تنفيذ سلسلة أوامر مفصولة بـ | */
    private fun executePipeline(line: String): Int {
        val pipeSegments = splitByPipe(line)
        if (pipeSegments.size == 1) {
            return executeSingleCommand(pipeSegments[0])
        }

        // أنابيب حقيقية
        var lastExit = 0
        var prevOutput: InputStream? = null
        for ((i, segment) in pipeSegments.withIndex()) {
            val isLast = (i == pipeSegments.size - 1)
            val output = if (isLast) outputStream else PipedOutputStream()
            val nextInput = if (isLast) null else PipedInputStream(8192)

            val ctx = ShellContext(
                context = context,
                environment = shellContext.environment.toMutableMap(),
                workingDirectory = shellContext.workingDirectory,
                stdin = prevOutput ?: inputStream,
                stdout = if (isLast) outputStream else output,
                stderr = outputStream,
                sessionId = sessionId
            )

            // ربط الأنابيب
            if (!isLast && nextInput != null) {
                (output as PipedOutputStream).connect(nextInput)
            }

            val exit = executeSingleCommand(segment, ctx)
            lastExit = exit

            try { (output as? PipedOutputStream)?.close() } catch (_: Exception) {}
            prevOutput = nextInput
        }
        return lastExit
    }

    private fun splitByPipe(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingle = false; var inDouble = false; var escape = false
        for (c in line) {
            if (escape) { sb.append(c); escape = false; continue }
            when {
                inSingle -> { sb.append(c); if (c == '\'') inSingle = false }
                inDouble -> { sb.append(c); if (c == '"') inDouble = false }
                c == '\\' -> { sb.append(c); escape = true }
                c == '\'' -> { sb.append(c); inSingle = true }
                c == '"' -> { sb.append(c); inDouble = true }
                c == '|' -> {
                    result.add(sb.toString().trim())
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        if (sb.toString().trim().isNotEmpty()) result.add(sb.toString().trim())
        return result
    }

    /** تنفيذ أمر واحد (مع توسيع وحدة وتعامل مع التوجيه) */
    private fun executeSingleCommand(line: String, overrideCtx: ShellContext? = null): Int {
        val ctx = overrideCtx ?: shellContext

        // 1. تقسيم حسب التوجيه (redirection) أولاً
        val redirectInfo = parseRedirections(line)
        val cmdLine = redirectInfo.commandLine

        // 2. توسيع متغيرات البيئة والـ tilde
        val expanded = expandLine(cmdLine, ctx)

        // 3. تقسيم token (مع احترام الاقتباس)
        val rawTokens = tokenize(expanded)
        if (rawTokens.isEmpty()) return 0

        // 4. توسيع glob (البدائل * ? [abc]) على الـ tokens غير المقتبسة
        val tokens = mutableListOf<String>()
        for (token in rawTokens) {
            val globbed = expandGlob(token, ctx)
            if (globbed.isEmpty()) {
                tokens.add(token)  // لا توجد مطابقات — نحتفظ بالأصل
            } else {
                tokens.addAll(globbed)
            }
        }

        val cmdName = tokens[0]
        val args = tokens.drop(1).toMutableList()

        // 5. توسيع alias
        aliases[cmdName]?.let { alias ->
            val aliasTokens = tokenize(expandLine(alias, ctx))
            // استبدال: alias $args
            val newArgs = aliasTokens.drop(1) + args
            return executeCommand(aliasTokens[0], newArgs, ctx, redirectInfo)
        }

        return executeCommand(cmdName, args, ctx, redirectInfo)
    }

    /** يوسّع نمط glob (* ? [abc]) إلى قائمة ملفات مطابقة */
    private fun expandGlob(token: String, ctx: ShellContext): List<String> {
        // إذا كان الـ token يحوي علامات اقتباس، لا نوسّع
        if (token.contains("'") || token.contains("\"")) return emptyList()
        // إذا كان لا يحوي أحرف glob، لا نوسّع
        if (!token.containsAnyOf("*?[")) return emptyList()

        // حلّ المسار
        val file = if (token.startsWith("/")) java.io.File(token)
                   else java.io.File(ctx.workingDirectory, token)
        val parent = file.parentFile ?: java.io.File(ctx.workingDirectory)
        val pattern = file.name

        if (!parent.exists() || !parent.isDirectory) return emptyList()

        // بناء regex من glob
        val regex = buildRegexFromGlob(pattern)
        val matches = parent.listFiles()
            ?.filter { regex.matches(it.name) }
            ?.sortedBy { it.name }
            ?: emptyList()

        return matches.map {
            val path = it.absolutePath
            // إعادة لـ المسار النسبي إذا كان الأصل نسبياً
            if (!token.startsWith("/")) {
                val rel = if (token.startsWith("~")) "~" + path.removePrefix(ctx.environment["HOME"] ?: "")
                          else path.removePrefix(ctx.workingDirectory + "/")
                rel
            } else path
        }
    }

    private fun String.containsAnyOf(chars: String): Boolean = chars.any { it in this }

    private fun buildRegexFromGlob(pattern: String): Regex {
        val sb = StringBuilder()
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                inClass -> {
                    sb.append(c)
                    if (c == ']') inClass = false
                }
                c == '*' -> sb.append(".*")
                c == '?' -> sb.append('.')
                c == '[' -> { sb.append('['); inClass = true }
                c in ".+(){}^\$|" -> { sb.append('\\').append(c) }
                else -> sb.append(c)
            }
            i++
        }
        return Regex(sb.toString())
    }

    private data class RedirectInfo(
        val commandLine: String,
        val stdinFile: String? = null,
        val stdoutFile: String? = null,
        val stderrFile: String? = null,
        val appendStdout: Boolean = false,
        val appendStderr: Boolean = false,
        val mergeStderr: Boolean = false
    )

    private fun parseRedirections(line: String): RedirectInfo {
        var stdinFile: String? = null
        var stdoutFile: String? = null
        var stderrFile: String? = null
        var appendStdout = false
        var appendStderr = false
        var mergeStderr = false

        val sb = StringBuilder()
        var inSingle = false; var inDouble = false; var escape = false
        var i = 0

        while (i < line.length) {
            val c = line[i]
            if (escape) { sb.append(c); escape = false; i++; continue }
            when {
                inSingle -> { sb.append(c); if (c == '\'') inSingle = false }
                inDouble -> { sb.append(c); if (c == '"') inDouble = false }
                c == '\\' -> { sb.append(c); escape = true }
                c == '\'' -> { sb.append(c); inSingle = true }
                c == '"' -> { sb.append(c); inDouble = true }
                c == '>' -> {
                    // >> أو > أو &>
                    if (i + 1 < line.length && line[i + 1] == '>') {
                        appendStdout = true
                        i += 2
                    } else if (i + 1 < line.length && line[i + 1] == '&') {
                        mergeStderr = true
                        i += 2
                    } else {
                        i++
                    }
                    stdoutFile = readNextToken(line, i)
                    i += stdoutFile!!.length
                    // تخطي المسافات
                    while (i < line.length && line[i].isWhitespace()) i++
                }
                c == '<' -> {
                    i++
                    stdinFile = readNextToken(line, i)
                    i += stdinFile!!.length
                    while (i < line.length && line[i].isWhitespace()) i++
                }
                c == '2' && i + 1 < line.length && line[i + 1] == '>' -> {
                    // 2> or 2>>
                    if (i + 2 < line.length && line[i + 2] == '>') {
                        appendStderr = true
                        i += 3
                    } else {
                        i += 2
                    }
                    stderrFile = readNextToken(line, i)
                    i += stderrFile!!.length
                    while (i < line.length && line[i].isWhitespace()) i++
                }
                else -> { sb.append(c); i++ }
            }
        }

        return RedirectInfo(
            commandLine = sb.toString().trim(),
            stdinFile = stdinFile,
            stdoutFile = stdoutFile,
            stderrFile = stderrFile,
            appendStdout = appendStdout,
            appendStderr = appendStderr,
            mergeStderr = mergeStderr
        )
    }

    private fun readNextToken(s: String, start: Int): String {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        val sb = StringBuilder()
        var inSingle = false; var inDouble = false
        while (i < s.length) {
            val c = s[i]
            when {
                inSingle -> { sb.append(c); if (c == '\'') inSingle = false }
                inDouble -> { sb.append(c); if (c == '"') inDouble = false }
                c == '\'' -> { inSingle = true; sb.append(c) }
                c == '"' -> { inDouble = true; sb.append(c) }
                c.isWhitespace() -> break
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString().trim('"').trim('\'')
    }

    private fun expandLine(line: String, ctx: ShellContext): String {
        // 1. توسيع ~
        var expanded = if (line.startsWith("~")) {
            line.replaceFirst("~", ctx.environment["HOME"] ?: "")
        } else line

        // 2. توسيع متغيرات البيئة
        expanded = Environment.expand(expanded, ctx.environment)

        // 3. توسيع الأوامر الفرعية $(cmd) — تبسيط
        // (يمكن إضافته لاحقاً)

        // 4. توسيع glob — يُطبّق على الـ tokens
        return expanded
    }

    private fun tokenize(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingle = false; var inDouble = false; var escape = false
        var i = 0

        while (i < line.length) {
            val c = line[i]
            if (escape) {
                sb.append(c)
                escape = false
                i++
                continue
            }
            when {
                inSingle -> {
                    sb.append(c)
                    if (c == '\'') inSingle = false
                }
                inDouble -> {
                    sb.append(c)
                    if (c == '"') inDouble = false
                }
                c == '\\' -> escape = true
                c == '\'' -> inSingle = true
                c == '"' -> inDouble = true
                c.isWhitespace() -> {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb.clear()
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    private fun executeCommand(
        name: String,
        args: List<String>,
        ctx: ShellContext,
        redirect: RedirectInfo
    ): Int {
        // الأوامر المدمجة (builtins)
        when (name) {
            "exit", "logout" -> throw ExitException()
            "cd" -> return builtinCd(args, ctx)
            "pwd" -> { write(ctx.workingDirectory + "\r\n"); return 0 }
            "export" -> return builtinExport(args, ctx)
            "unset" -> return builtinUnset(args, ctx)
            "alias" -> return builtinAlias(args, ctx)
            "unalias" -> return builtinUnalias(args, ctx)
            "echo" -> return CommandRegistry.execute("echo", args, ctx)
            "history" -> {
                history.forEachIndexed { i, h -> write("${i + 1}  $h\r\n") }
                return 0
            }
            "true" -> return 0
            "false" -> return 1
            "set" -> {
                ctx.environment.forEach { (k, v) -> write("$k=$v\r\n") }
                return 0
            }
            "source", "." -> return builtinSource(args, ctx)
            "help" -> return builtinHelp(args, ctx)
        }

        // أوامر عبر CommandRegistry
        if (CommandRegistry.has(name)) {
            // فتح الملفات للتوجيه إن وجدت
            val finalCtx = applyRedirections(ctx, redirect)
            return CommandRegistry.execute(name, args, finalCtx)
        }

        writeError("bash: $name: command not found\r\n")
        return 127
    }

    private fun applyRedirections(ctx: ShellContext, r: RedirectInfo): ShellContext {
        var newCtx = ctx
        try {
            if (r.stdoutFile != null) {
                val file = java.io.File(resolvePath(r.stdoutFile, ctx))
                val fos = java.io.FileOutputStream(file, r.appendStdout)
                newCtx = newCtx.copy(stdout = fos)
            }
            if (r.stderrFile != null) {
                val file = java.io.File(resolvePath(r.stderrFile, ctx))
                val fos = java.io.FileOutputStream(file, r.appendStderr)
                newCtx = newCtx.copy(stderr = fos)
            }
            if (r.mergeStderr) {
                newCtx = newCtx.copy(stderr = newCtx.stdout)
            }
            if (r.stdinFile != null) {
                val file = java.io.File(resolvePath(r.stdinFile, ctx))
                val fis = java.io.FileInputStream(file)
                newCtx = newCtx.copy(stdin = fis)
            }
        } catch (e: Exception) {
            writeError("bash: ${e.message}\r\n")
        }
        return newCtx
    }

    private fun resolvePath(p: String, ctx: ShellContext): String {
        return if (p.startsWith("/")) p
        else java.io.File(ctx.workingDirectory, p).absolutePath
    }

    private fun builtinCd(args: List<String>, ctx: ShellContext): Int {
        val target = if (args.isEmpty()) ctx.environment["HOME"] ?: "/" else args[0]
        val resolved = java.io.File(resolvePath(target, ctx))
        if (!resolved.exists()) {
            writeError("cd: $target: No such directory\r\n")
            return 1
        }
        if (!resolved.isDirectory) {
            writeError("cd: $target: Not a directory\r\n")
            return 1
        }
        ctx.environment["OLDPWD"] = ctx.workingDirectory
        ctx.workingDirectory = resolved.absolutePath
        ctx.environment["PWD"] = resolved.absolutePath
        return 0
    }

    private fun builtinExport(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) {
            ctx.environment.forEach { (k, v) ->
                write("export $k=\"$v\"\r\n")
            }
            return 0
        }
        for (arg in args) {
            val eq = arg.indexOf('=')
            if (eq > 0) {
                val key = arg.substring(0, eq)
                val value = arg.substring(eq + 1).trim('"').trim('\'')
                ctx.environment[key] = value
            } else {
                // export VAR (no value, just marks for export — no-op here)
            }
        }
        return 0
    }

    private fun builtinUnset(args: List<String>, ctx: ShellContext): Int {
        for (arg in args) ctx.environment.remove(arg)
        return 0
    }

    private fun builtinAlias(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) {
            aliases.forEach { (k, v) -> write("alias $k='$v'\r\n") }
            return 0
        }
        for (arg in args) {
            val eq = arg.indexOf('=')
            if (eq > 0) {
                val name = arg.substring(0, eq)
                val value = arg.substring(eq + 1).trim('"').trim('\'')
                aliases[name] = value
            }
        }
        return 0
    }

    private fun builtinUnalias(args: List<String>, ctx: ShellContext): Int {
        for (arg in args) aliases.remove(arg)
        return 0
    }

    private fun builtinSource(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) {
            writeError("source: missing filename\r\n")
            return 1
        }
        val file = java.io.File(resolvePath(args[0], ctx))
        if (!file.exists()) {
            writeError("source: ${args[0]}: No such file\r\n")
            return 1
        }
        file.readLines().forEach { line ->
            if (line.isNotBlank() && !line.trimStart().startsWith("#")) {
                executeLine(line)
            }
        }
        return 0
    }

    private fun builtinHelp(args: List<String>, ctx: ShellContext): Int {
        val cmds = CommandRegistry.listCommands()
        write("\u001B[1;36mLinuxDroid built-in commands:\u001B[0m\r\n")
        cmds.sorted().forEach { write("  $it\r\n") }
        write("\r\nBuiltins: cd, pwd, exit, export, unset, alias, unalias, source, history, set\r\n")
        write("Type '<command> --help' for specific help.\r\n")
        return 0
    }

    fun write(s: String) {
        try {
            writer.write(s)
            writer.flush()
        } catch (e: IOException) {
            if (running.get()) LinuxDroidLogger.w(TAG, "write error: ${e.message}")
        }
    }

    fun writeError(s: String) {
        try {
            writer.write(s)
            writer.flush()
        } catch (_: Exception) {}
    }

    fun isRunning(): Boolean = running.get()

    fun stop() {
        running.set(false)
        try { reader.close() } catch (_: Exception) {}
        try { writer.close() } catch (_: Exception) {}
    }

    fun resize(cols: Int, rows: Int) {
        columns = cols
        rows = rows
        shellContext.columns = cols
        shellContext.rows = rows
        shellContext.environment["COLUMNS"] = cols.toString()
        shellContext.environment["LINES"] = rows.toString()
    }

    private class ExitException : Exception()

    companion object { private const val TAG = "ShellInterpreter" }
}
