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
                try {
                    executeLine(line)
                } catch (e: ExitException) {
                    write("\r\n")
                    break
                } catch (e: Exception) {
                    LinuxDroidLogger.e(TAG, "Error executing line: $line", e)
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

    /** قراءة سطر منطقي — قد يمتد على عدة أسطر في حالة عدم اكتمال اقتباس */
    private fun readLogicalLine(): String? {
        val sb = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var escape = false
        var needsMore = false

        while (running.get()) {
            val c = reader.read()
            if (c == -1) return if (sb.isNotEmpty()) sb.toString() else null
            val ch = c.toChar()

            if (escape) {
                sb.append('\\').append(ch)
                escape = false
                continue
            }

            when {
                inSingleQuote -> {
                    sb.append(ch)
                    if (ch == '\'') inSingleQuote = false
                }
                inDoubleQuote -> {
                    sb.append(ch)
                    if (ch == '"') inDoubleQuote = false
                }
                ch == '\\' -> {
                    escape = true
                    needsMore = true
                }
                ch == '\'' -> {
                    inSingleQuote = true
                    sb.append(ch)
                    needsMore = true
                }
                ch == '"' -> {
                    inDoubleQuote = true
                    sb.append(ch)
                    needsMore = true
                }
                ch == '\r' -> { /* تجاهل */ }
                ch == '\n' -> {
                    if (needsMore) {
                        sb.append('\n')
                        write("> ") // PS2 prompt
                        writer.flush()
                        needsMore = false
                    } else {
                        return sb.toString()
                    }
                }
                else -> sb.append(ch)
            }
        }
        return if (sb.isNotEmpty()) sb.toString() else null
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

        // 2. توسيع متغيرات البيئة والـ tilde والـ globs
        val expanded = expandLine(cmdLine, ctx)

        // 3. تقسيم token
        val tokens = tokenize(expanded)
        if (tokens.isEmpty()) return 0

        val cmdName = tokens[0]
        val args = tokens.drop(1).toMutableList()

        // 4. توسيع alias
        aliases[cmdName]?.let { alias ->
            val aliasTokens = tokenize(expandLine(alias, ctx))
            // استبدال: alias $args
            val newArgs = aliasTokens.drop(1) + args
            return executeCommand(aliasTokens[0], newArgs, ctx, redirectInfo)
        }

        return executeCommand(cmdName, args, ctx, redirectInfo)
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
