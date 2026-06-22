package com.linuxdroid.terminal

import com.linuxdroid.util.LinuxDroidLogger

/**
 * معالج تسلسلات ANSI escape.
 *
 * يستهلك دفق بايتات/أحرف ويحدّث [TerminalBuffer] وفقاً لـ:
 *  - أحرف التحكم (BS, HT, LF, VT, FF, CR, BEL)
 *  - CSI: Control Sequence Introducer  (ESC [ ...)
 *  - OSC: Operating System Command     (ESC ] ...)
 *  - تسلسلات بسيطة: ESC 7/8 (save/restore), ESC M (reverse LF), ESC c (reset)
 *
 * تنفيذ شامل لأهم تسلسلات xterm المستخدمة في تطبيقات الطرفية الشائعة.
 */
class AnsiEscapeParser(private val buffer: TerminalBuffer) {

    private enum class State {
        GROUND,           // الوضع العادي
        ESC,              // بعد ESC
        CSI,              // بعد ESC [
        CSI_PARAM,        // في معطيات CSI (أرقام أو ; أو ?)
        OSC,              // بعد ESC ]
        OSC_ESC           // داخل OSC وانتظار BEL أو ST
    }

    // SECURITY: حدود قصوى لمنع DoS عبر تسلسلات شاذّة (OOM)
    private val MAX_PARAM_SIZE = 256
    private val MAX_OSC_SIZE = 4096

    private var state = State.GROUND
    private val paramBuf = StringBuilder()
    private val oscBuf = StringBuilder()

    /** معالجة سلسلة كاملة */
    fun feed(input: String) {
        for (c in input) feedChar(c)
    }

    /** معالجة دفعة بايتات */
    fun feed(input: ByteArray) {
        feed(String(input, Charsets.UTF_8))
    }

    fun feedChar(c: Char) {
        when (state) {
            State.GROUND -> handleGround(c)
            State.ESC -> handleEsc(c)
            State.CSI, State.CSI_PARAM -> handleCsi(c)
            State.OSC, State.OSC_ESC -> handleOsc(c)
        }
    }

    private fun handleGround(c: Char) {
        when (c) {
            0x07.toChar() -> { } // BEL — لا شيء (يمكن إصدار صوت)
            0x08.toChar() -> buffer.backspace()        // BS
            0x09.toChar() -> buffer.tab()              // HT
            0x0A.toChar() -> buffer.newline()          // LF
            0x0B.toChar() -> buffer.newline()          // VT (نفس LF)
            0x0C.toChar() -> buffer.newline()          // FF (نفس LF)
            0x0D.toChar() -> buffer.carriageReturn()   // CR
            0x1B.toChar() -> state = State.ESC         // ESC
            in ' '..'~' -> buffer.putChar(c)           // حرف عادي قابل للطباعة
            else -> { /* تجاهل باقات UTF-8 متعددة البايت */ }
        }
    }

    private fun handleEsc(c: Char) {
        when (c) {
            '[' -> { state = State.CSI; paramBuf.clear() }
            ']' -> { state = State.OSC; paramBuf.clear(); oscBuf.clear() }
            '7' -> { buffer.saveCursor(); state = State.GROUND }
            '8' -> { buffer.restoreCursor(); state = State.GROUND }
            'M' -> { // Reverse line feed
                if (buffer.cursorRow == buffer.let { it.scrollTop }) {
                    buffer.scrollDown(1)
                } else {
                    buffer.moveCursor(-1, 0)
                }
                state = State.GROUND
            }
            'D' -> { // Index (down one line, scroll if needed)
                buffer.moveCursor(1, 0)
                state = State.GROUND
            }
            'E' -> { // Next line
                buffer.newline()
                state = State.GROUND
            }
            'c' -> { // RIS — full reset
                buffer.resetStyle()
                buffer.setCursor(0, 0)
                buffer.eraseDisplay()
                state = State.GROUND
            }
            '(' -> { state = State.GROUND } // G0 charset (تجاهل)
            ')' -> { state = State.GROUND } // G1 charset (تجاهل)
            '=' -> { state = State.GROUND } // Application keypad mode
            '>' -> { state = State.GROUND } // Normal keypad mode
            else -> {
                LinuxDroidLogger.d(TAG, "Unhandled ESC sequence: ESC $c")
                state = State.GROUND
            }
        }
    }

    private fun handleCsi(c: Char) {
        if (c.isDigit() || c == ';' || c == '?' || c == '>' || c == '=' || c == '!' || c == ' ') {
            // SECURITY: منع نموّ paramBuf بلا حدود (DoS)
            if (paramBuf.length >= MAX_PARAM_SIZE) {
                state = State.GROUND
                paramBuf.clear()
                return
            }
            paramBuf.append(c)
            return
        }
        // نهاية التسلسل — نفّذ الأمر
        executeCsi(c)
        state = State.GROUND
        paramBuf.clear()
    }

    private fun executeCsi(final: Char) {
        val raw = paramBuf.toString()
        val isPrivate = raw.startsWith("?")
        val params = raw.trimStart('?', '>', '=', '!', ' ')
            .split(';')
            .filter { it.isNotEmpty() }
            .map { it.toIntOrNull() ?: 0 }

        when (final) {
            // Cursor movement
            'A' -> buffer.moveCursor(-(params.getOrNull(0) ?: 1), 0) // up
            'B' -> buffer.moveCursor(params.getOrNull(0) ?: 1, 0)    // down
            'C' -> buffer.moveCursor(0, params.getOrNull(0) ?: 1)    // forward
            'D' -> buffer.moveCursor(0, -(params.getOrNull(0) ?: 1)) // back
            'E' -> { // CNL — Cursor next line
                buffer.moveCursor(params.getOrNull(0) ?: 1, 0)
                buffer.carriageReturn()
            }
            'F' -> { // CPL — Cursor previous line
                buffer.moveCursor(-(params.getOrNull(0) ?: 1), 0)
                buffer.carriageReturn()
            }
            'G' -> buffer.setCursor(buffer.cursorRow, (params.getOrNull(0) ?: 1) - 1) // CHA
            'H', 'f' -> { // CUP — Cursor position
                val row = (params.getOrNull(0) ?: 1) - 1
                val col = (params.getOrNull(1) ?: 1) - 1
                buffer.setCursor(row, col)
            }
            'd' -> buffer.setCursor((params.getOrNull(0) ?: 1) - 1, buffer.cursorCol) // VPA
            'J' -> { // ED — Erase display
                when (params.getOrNull(0) ?: 0) {
                    0 -> buffer.eraseDisplayFromCursor(true)
                    1 -> buffer.eraseDisplayFromCursor(false)
                    2, 3 -> buffer.eraseDisplay()
                }
            }
            'K' -> { // EL — Erase line
                when (params.getOrNull(0) ?: 0) {
                    0 -> buffer.eraseLineFromCursor(true)
                    1 -> buffer.eraseLineFromCursor(false)
                    2 -> buffer.eraseLine()
                }
            }
            'L' -> { // IL — Insert lines
                val n = params.getOrNull(0) ?: 1
                repeat(n) { buffer.scrollDown(1) }
            }
            'M' -> { // DL — Delete lines
                val n = params.getOrNull(0) ?: 1
                repeat(n) { buffer.scrollUp(1) }
            }
            'P' -> { // DCH — Delete characters
                val n = params.getOrNull(0) ?: 1
                // تبسيط: نحذف n حرفاً من موضع المؤشر ونملأ بالفراغات
                for (i in 0 until n) buffer.putChar(' ')
                buffer.moveCursor(0, -n)
            }
            '@' -> { // ICH — Insert characters (نفس تبسيط DCH)
                val n = params.getOrNull(0) ?: 1
                buffer.moveCursor(0, n)
            }
            'S' -> buffer.scrollUp(params.getOrNull(0) ?: 1) // SU
            'T' -> buffer.scrollDown(params.getOrNull(0) ?: 1) // SD
            'm' -> handleSgr(params) // SGR — Select graphic rendition
            'r' -> { // DECSTBM — Set scroll region
                val top = (params.getOrNull(0) ?: 1) - 1
                val bottom = (params.getOrNull(1) ?: buffer.rows) - 1
                buffer.setScrollRegion(top, bottom)
                buffer.setCursor(0, 0)
            }
            'h' -> { // SM — Set mode
                if (isPrivate) {
                    when (params.getOrNull(0)) {
                        // ?1049 — alternate screen (تبسيط: فقط نمسح)
                        1049, 47, 1047 -> buffer.eraseDisplay()
                        // ?25 — hide/show cursor (يتعامل به العرض)
                        25 -> { }
                    }
                }
            }
            'l' -> { // RM — Reset mode (تماثل h)
                if (isPrivate) {
                    when (params.getOrNull(0)) {
                        1049, 47, 1047 -> buffer.eraseDisplay()
                        25 -> { }
                    }
                }
            }
            'n' -> { // DSR — Device status report
                // يتطلب رد على المدخلات — يُترك للطبقة الأعلى
            }
            's' -> buffer.saveCursor()
            'u' -> buffer.restoreCursor()
            else -> {
                LinuxDroidLogger.d(TAG, "Unhandled CSI: CSI$raw$final")
            }
        }
    }

    /** SGR — Select Graphic Rendition (ألوان وسمات) */
    private fun handleSgr(params: List<Int>) {
        if (params.isEmpty()) {
            buffer.resetStyle()
            return
        }
        var i = 0
        while (i < params.size) {
            val p = params[i]
            when {
                p == 0 -> buffer.resetStyle()
                p == 1 -> buffer.setStyle(bold = true)
                p == 3 -> buffer.setStyle(italic = true)
                p == 4 -> buffer.setStyle(underline = true)
                p == 5 -> buffer.setStyle(blink = true)
                p == 7 -> buffer.setStyle(inverse = true)
                p == 22 -> buffer.setStyle(bold = false)
                p == 23 -> buffer.setStyle(italic = false)
                p == 24 -> buffer.setStyle(underline = false)
                p == 25 -> buffer.setStyle(blink = false)
                p == 27 -> buffer.setStyle(inverse = false)
                p in 30..37 -> buffer.setStyle(fg = p - 30)
                p == 38 -> {
                    // 38;5;<n> أو 38;2;r;g;b
                    if (i + 1 < params.size && params[i + 1] == 5) {
                        buffer.setStyle(fg = mapAnsi256(params.getOrNull(i + 2) ?: 0))
                        i += 2
                    } else if (i + 1 < params.size && params[i + 1] == 2) {
                        // RGB → نقرب إلى أقرب 16 لون
                        buffer.setStyle(fg = rgbToAnsi16(
                            params.getOrNull(i + 2) ?: 0,
                            params.getOrNull(i + 3) ?: 0,
                            params.getOrNull(i + 4) ?: 0
                        ))
                        i += 4
                    }
                }
                p == 39 -> buffer.setStyle(fg = TerminalBuffer.COLOR_DEFAULT)
                p in 40..47 -> buffer.setStyle(bg = p - 40)
                p == 48 -> {
                    if (i + 1 < params.size && params[i + 1] == 5) {
                        buffer.setStyle(bg = mapAnsi256(params.getOrNull(i + 2) ?: 0))
                        i += 2
                    } else if (i + 1 < params.size && params[i + 1] == 2) {
                        buffer.setStyle(bg = rgbToAnsi16(
                            params.getOrNull(i + 2) ?: 0,
                            params.getOrNull(i + 3) ?: 0,
                            params.getOrNull(i + 4) ?: 0
                        ))
                        i += 4
                    }
                }
                p == 49 -> buffer.setStyle(bg = TerminalBuffer.COLOR_DEFAULT)
                p in 90..97 -> buffer.setStyle(fg = p - 90 + 8)
                p in 100..107 -> buffer.setStyle(bg = p - 100 + 8)
            }
            i++
        }
    }

    /** خريطة لوحة 256 لوناً إلى 16 لوناً (تبسيط) */
    private fun mapAnsi256(n: Int): Int {
        return when {
            n < 8 -> n
            n < 16 -> n
            n < 232 -> {
                // 6×6×6 مكعب RGB — نقرب إلى 8 ألوان
                val i = n - 16
                val r = (i / 36) % 6
                val g = (i / 6) % 6
                val b = i % 6
                rgbToAnsi16(r * 51, g * 51, b * 51)
            }
            else -> { // تدرج رمادي
                if (n < 244) TerminalBuffer.COLOR_BRIGHT_BLACK else TerminalBuffer.COLOR_WHITE
            }
        }
    }

    private fun rgbToAnsi16(r: Int, g: Int, b: Int): Int {
        // تبسيط جداً: نختار أقرب لون من 8
        return when {
            r > 200 && g > 200 && b > 200 -> TerminalBuffer.COLOR_WHITE
            r > 150 && g < 100 && b < 100 -> TerminalBuffer.COLOR_RED
            r < 100 && g > 150 && b < 100 -> TerminalBuffer.COLOR_GREEN
            r > 150 && g > 150 && b < 100 -> TerminalBuffer.COLOR_YELLOW
            r < 100 && g < 100 && b > 150 -> TerminalBuffer.COLOR_BLUE
            r > 100 && g < 100 && b > 100 -> TerminalBuffer.COLOR_MAGENTA
            r < 100 && g > 100 && b > 100 -> TerminalBuffer.COLOR_CYAN
            else -> TerminalBuffer.COLOR_BRIGHT_BLACK
        }
    }

    private fun handleOsc(c: Char) {
        when (c) {
            0x07.toChar() -> { // BEL — نهاية OSC
                executeOsc(oscBuf.toString())
                state = State.GROUND
                oscBuf.clear()
            }
            0x1B.toChar() -> state = State.OSC_ESC
            else -> {
                // SECURITY: منع نموّ oscBuf بلا حدود (DoS)
                if (oscBuf.length < MAX_OSC_SIZE) oscBuf.append(c)
                else { state = State.GROUND; oscBuf.clear() }
            }
        }
        if (state == State.OSC_ESC) {
            if (c == '\\') {
                executeOsc(oscBuf.toString())
                state = State.GROUND
                oscBuf.clear()
            } else if (c != 0x1B.toChar()) {
                state = State.OSC
                if (oscBuf.length < MAX_OSC_SIZE) oscBuf.append(c)
            }
        }
    }

    private fun executeOsc(s: String) {
        // OSC 0;title  → window title
        // OSC 2;title  → window title
        // OSC 4;n;rgb  → palette (تجاهل)
        // OSC 11;rgb   → background (تجاهل)
        // نتجاهل كل ذلك في هذه المرحلة
        // SECURITY: نسجّل الطول فقط لمنع تضخيم الـ log
        if (s.length <= 64) LinuxDroidLogger.d(TAG, "OSC: $s")
        else LinuxDroidLogger.d(TAG, "OSC: (len=${s.length}, truncated)")
    }

    companion object { private const val TAG = "AnsiEscapeParser" }
}
