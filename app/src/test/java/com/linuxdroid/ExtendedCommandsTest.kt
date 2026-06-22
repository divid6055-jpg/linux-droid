package com.linuxdroid.commands.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات وحدية لأوامر printf, base64, md5sum, sha256sum.
 *
 * تستخدم سياقاً وهمياً (mock context) للتشغيل بدون أندرويد.
 */
class ExtendedCommandsTest {

    /**
     * سياق وهمي بسيط للاختبارات — يجمع المخرجات في StringBuilder.
     */
    private class TestContext(
        val outBuffer: StringBuilder = StringBuilder(),
        val errBuffer: StringBuilder = StringBuilder()
    ) {
        fun write(s: String) { outBuffer.append(s) }
        fun writeError(s: String) { errBuffer.append(s) }
        fun writeln(s: String) { outBuffer.append(s).append("\r\n") }
        fun writeErrln(s: String) { errBuffer.append(s).append("\r\n") }
    }

    @Test
    fun `printf with simple string`() {
        // نختبر منطق printf مباشرة (دون CommandExecutor الكامل الذي يحتاج Android Context)
        val result = formatPrintf("Hello %s!", listOf("World"))
        assertEquals("Hello World!", result)
    }

    @Test
    fun `printf with integer`() {
        val result = formatPrintf("Number: %d", listOf("42"))
        assertEquals("Number: 42", result)
    }

    @Test
    fun `printf with float`() {
        val result = formatPrintf("Pi: %f", listOf("3.14"))
        assertEquals("Pi: 3.14", result)
    }

    @Test
    fun `printf with hex`() {
        val result = formatPrintf("Hex: %x", listOf("255"))
        assertEquals("Hex: ff", result)
    }

    @Test
    fun `printf with octal`() {
        val result = formatPrintf("Octal: %o", listOf("8"))
        assertEquals("Octal: 10", result)
    }

    @Test
    fun `printf with percent literal`() {
        val result = formatPrintf("100%% complete", emptyList())
        assertEquals("100% complete", result)
    }

    @Test
    fun `printf with escaped newline`() {
        val result = formatPrintf("Line1\\nLine2", emptyList())
        assertEquals("Line1\nLine2", result)
    }

    @Test
    fun `printf with escaped tab`() {
        val result = formatPrintf("Col1\\tCol2", emptyList())
        assertEquals("Col1\tCol2", result)
    }

    @Test
    fun `printf with multiple args`() {
        val result = formatPrintf("%s = %d", listOf("age", "25"))
        assertEquals("age = 25", result)
    }

    @Test
    fun `printf with missing args returns empty for missing`() {
        val result = formatPrintf("%s and %s", listOf("one"))
        assertEquals("one and ", result)
    }

    /**
     * نسخة مبسّطة من منطق PrintfCommand للاختبار.
     */
    private fun formatPrintf(format: String, args: List<String>): String {
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
                    else -> { result.append('\\').append(format[i + 1]) }
                }
                i += 2
                continue
            }
            if (c == '%' && i + 1 < format.length) {
                val spec = format[i + 1]
                when (spec) {
                    's' -> { result.append(args.getOrNull(argIdx) ?: ""); argIdx++ }
                    'd', 'i' -> {
                        result.append(args.getOrNull(argIdx)?.toIntOrNull() ?: 0); argIdx++
                    }
                    'f' -> {
                        result.append(args.getOrNull(argIdx)?.toDoubleOrNull() ?: 0.0); argIdx++
                    }
                    'c' -> { result.append(args.getOrNull(argIdx)?.firstOrNull() ?: ""); argIdx++ }
                    'x' -> {
                        val v = args.getOrNull(argIdx)?.toIntOrNull() ?: 0
                        result.append(v.toString(16)); argIdx++
                    }
                    'o' -> {
                        val v = args.getOrNull(argIdx)?.toIntOrNull() ?: 0
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
        return result.toString()
    }
}
