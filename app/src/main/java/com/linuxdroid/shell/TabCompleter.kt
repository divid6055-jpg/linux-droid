package com.linuxdroid.shell

import com.linuxdroid.commands.CommandRegistry
import com.linuxdroid.util.LinuxDroidLogger
import java.io.File

/**
 * إكمال Tab — يكمل الأوامر وأسماء الملفات والمتغيرات.
 *
 * عند ضغط Tab:
 * 1. إذا كان في بداية السطر → أكمل اسم أمر
 * 2. إذا كان بعد $ → أكمل متغير بيئة
 * 3. وإلا → أكمل مسار ملف
 *
 * إذا كان هناك إكمال واحد → يطبّقه
 * إذا كان هناك عدة → يعرض الخيارات
 */
class TabCompleter(
    private val environment: Map<String, String>,
    private val workingDirectory: String
) {
    /**
     * يحاول إكمال السطر المعطى.
     *
     * @param line السطر الحالي
     * @param cursorPos موضع المؤشر (نستخدم نهاية السطر حالياً)
     * @return نتيجة الإكمال
     */
    fun complete(line: String, cursorPos: Int = line.length): CompletionResult {
        if (line.isEmpty()) return CompletionResult("", emptyList())

        // إكمال متغيرات البيئة (بعد $)
        val dollarIdx = line.lastIndexOf('$', cursorPos)
        if (dollarIdx >= 0 && dollarIdx < cursorPos) {
            val afterDollar = line.substring(dollarIdx + 1, cursorPos)
            // تأكد أنه ليس ${VAR}
            if (afterDollar.all { it.isLetterOrDigit() || it == '_' }) {
                return completeVariable(afterDollar, dollarIdx)
            }
        }

        // إيجاد الكلمة الحالية (token)
        val beforeCursor = line.substring(0, cursorPos)
        val tokens = beforeCursor.split(Regex("\\s+"))
        val currentToken = tokens.lastOrNull() ?: ""

        // إذا كان هناك فراغ قبل المؤشر أو سطر فارغ — لا إكمال
        if (currentToken.isEmpty() && beforeCursor.isNotEmpty() && !beforeCursor.endsWith(' ')) {
            return CompletionResult("", emptyList())
        }

        // أول token → إكمال أمر
        val isFirstToken = tokens.size == 1 || (tokens.size == 2 && tokens[0].isEmpty())

        // إكمال أمر
        if (isFirstToken && currentToken.isNotEmpty() && !currentToken.startsWith("-") &&
            !currentToken.startsWith("/") && !currentToken.startsWith("~")) {
            val cmdCompletions = completeCommand(currentToken)
            if (cmdCompletions.completions.isNotEmpty()) {
                return cmdCompletions
            }
        }

        // إكمال مسار ملف
        return completePath(currentToken)
    }

    private fun completeCommand(prefix: String): CompletionResult {
        val matches = CommandRegistry.listCommands().filter { it.startsWith(prefix) }.sorted()
        return when {
            matches.isEmpty() -> CompletionResult("", emptyList())
            matches.size == 1 -> CompletionResult(matches[0].substring(prefix.length) + " ", emptyList())
            else -> {
                // إيجاد أطول بادئة مشتركة
                val common = longestCommonPrefix(matches)
                CompletionResult(common.substring(prefix.length), matches)
            }
        }
    }

    private fun completeVariable(prefix: String, dollarIdx: Int): CompletionResult {
        val matches = environment.keys.filter { it.startsWith(prefix) }.sorted()
        return when {
            matches.isEmpty() -> CompletionResult("", emptyList())
            matches.size == 1 -> CompletionResult(matches[0].substring(prefix.length), emptyList())
            else -> {
                val common = longestCommonPrefix(matches)
                CompletionResult(common.substring(prefix.length), matches)
            }
        }
    }

    private fun completePath(prefix: String): CompletionResult {
        // توسيع ~ و $
        val expanded = if (prefix.startsWith("~")) {
            prefix.replaceFirst("~", environment["HOME"] ?: "")
        } else {
            Environment.expand(prefix, environment)
        }

        val file = if (expanded.startsWith("/")) File(expanded)
                   else File(workingDirectory, expanded)

        val dir: File
        val namePrefix: String

        if (file.isDirectory && expanded.endsWith("/")) {
            dir = file
            namePrefix = ""
        } else {
            dir = file.parentFile ?: File(workingDirectory)
            namePrefix = file.name
        }

        if (!dir.exists() || !dir.isDirectory) {
            return CompletionResult("", emptyList())
        }

        val children = dir.listFiles()?.toList()?.sortedBy { it.name } ?: emptyList()
        val matches = children.filter { it.name.startsWith(namePrefix) }

        return when {
            matches.isEmpty() -> CompletionResult("", emptyList())
            matches.size == 1 -> {
                val m = matches[0]
                val suffix = m.name.substring(namePrefix.length)
                val addition = if (m.isDirectory) "$suffix/" else "$suffix "
                CompletionResult(addition, emptyList())
            }
            else -> {
                // إذا كانت كل النتائج مجلدات، نكمل البادئة المشتركة
                val common = longestCommonPrefix(matches.map { it.name })
                val display = matches.map { if (it.isDirectory) "${it.name}/" else it.name }
                CompletionResult(common.substring(namePrefix.length), display)
            }
        }
    }

    private fun longestCommonPrefix(strings: List<String>): String {
        if (strings.isEmpty()) return ""
        var minLen = strings.minOf { it.length }
        var i = 0
        while (i < minLen) {
            val c = strings[0][i]
            if (strings.all { it[i] == c }) i++
            else break
        }
        return strings[0].substring(0, i)
    }

    data class CompletionResult(
        val addition: String,
        val completions: List<String>
    )
}
