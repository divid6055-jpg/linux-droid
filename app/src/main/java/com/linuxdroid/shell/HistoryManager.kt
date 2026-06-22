package com.linuxdroid.shell

import com.linuxdroid.util.LinuxDroidLogger
import java.io.File

/**
 * مدير تاريخ الأوامر — يحفظ ويعيد الأوامر السابقة.
 *
 * الميزات:
 * - حفظ التاريخ في ملف (~/.bash_history)
 * - التنقل بالأسهم ↑/↓
 * - البحث العكسي بـ Ctrl+R
 * - حد أقصى للحجم (10000 سطر)
 * - منع التكرار المتجاوز
 *
 * الأمان: لا نحفظ الأوامر التي تبدأ بمسافة (متوافق مع سلوك bash HISTCONTROL=ignorespace)
 *       ولا نحفظ الأوامر التي تحتوي على كلمات سرية (password=, token=, etc.)
 */
class HistoryManager(private val historyFile: File) {

    private val history = mutableListOf<String>()
    private var currentPosition = -1  // -1 = after last entry
    private val maxEntries = 10000

    init {
        loadHistory()
    }

    private fun loadHistory() {
        try {
            if (historyFile.exists()) {
                historyFile.useLines { lines ->
                    lines.take(maxEntries).forEach { history.add(it) }
                }
            }
        } catch (e: Exception) {
            LinuxDroidLogger.w(TAG, "Failed to load history: ${e.message}")
        }
    }

    /** يضيف أمراً إلى التاريخ */
    fun add(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        // SECURITY: لا نحفظ الأوامر التي تبدأ بمسافة (متوافق مع HISTCONTROL=ignorespace)
        if (command.startsWith(" ")) return

        // SECURITY: لا نحفظ الأوامر التي تحتوي على أنماط حساسة
        if (containsSensitiveData(trimmed)) {
            LinuxDroidLogger.d(TAG, "Skipped history entry with sensitive data")
            return
        }

        // منع التكرار المتجاور
        if (history.isNotEmpty() && history.last() == trimmed) return

        history.add(trimmed)
        if (history.size > maxEntries) {
            history.removeAt(0)
        }
        save()
    }

    private fun containsSensitiveData(s: String): Boolean {
        val lower = s.lowercase()
        val sensitivePatterns = listOf(
            "password=", "passwd=", "pwd=",
            "token=", "api_key=", "apikey=",
            "secret=", "private_key=",
            "authorization:"
        )
        return sensitivePatterns.any { it in lower }
    }

    private fun save() {
        try {
            historyFile.bufferedWriter().use { w ->
                history.forEach { w.write(it); w.newLine() }
            }
        } catch (e: Exception) {
            LinuxDroidLogger.w(TAG, "Failed to save history: ${e.message}")
        }
    }

    /** ينتقل للأمر الأقدم (↑) */
    fun previous(): String? {
        if (history.isEmpty()) return null
        if (currentPosition < 0) {
            currentPosition = history.size - 1
        } else if (currentPosition > 0) {
            currentPosition--
        } else {
            return history[0]
        }
        return history[currentPosition]
    }

    /** ينتقل للأمر الأحدث (↓) */
    fun next(): String? {
        if (history.isEmpty() || currentPosition < 0) return null
        currentPosition++
        if (currentPosition >= history.size) {
            currentPosition = -1
            return null  // إعادة سطر فارغ
        }
        return history[currentPosition]
    }

    /** يعيد ضبط موضع التنقل */
    fun resetPosition() {
        currentPosition = -1
    }

    /** يبحث عكسي في التاريخ (Ctrl+R) */
    fun searchReverse(query: String): String? {
        for (i in history.indices.reversed()) {
            if (history[i].contains(query, ignoreCase = true)) {
                currentPosition = i
                return history[i]
            }
        }
        return null
    }

    fun getAll(): List<String> = history.toList()

    fun clear() {
        history.clear()
        save()
    }

    companion object { private const val TAG = "HistoryManager" }
}
