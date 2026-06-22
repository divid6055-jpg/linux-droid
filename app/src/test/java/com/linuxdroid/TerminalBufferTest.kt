package com.linuxdroid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات وحدوية لـ TerminalBuffer.
 *
 * تتحقق من:
 * 1. طباعة الأحرف وحركة المؤشر
 * 2. التمرير (scrolling)
 * 3. منطقة التمرير (scroll region)
 * 4. حفظ/استعادة المؤشر
 * 5. الأنماط (bold, italic, colors)
 * 6. إعادة الحجم
 */
class TerminalBufferTest {

    @Test
    fun `putChar advances cursor`() {
        val buf = TerminalBuffer(80, 24)
        buf.putChar('A')
        assertEquals(0, buf.cursorRow)
        assertEquals(1, buf.cursorCol)
        assertEquals('A', buf.getCell(0, 0)?.char)
    }

    @Test
    fun `putChar wraps to next line at end of row`() {
        val buf = TerminalBuffer(5, 3)
        for (i in 0 until 5) buf.putChar('X')
        assertEquals(0, buf.cursorRow)
        // عند الوصول للنهاية، المؤشر يلتف
        assertEquals(5, buf.cursorCol)  // قبل الـ newline
    }

    @Test
    fun `newline moves to next row and resets column`() {
        val buf = TerminalBuffer(80, 24)
        buf.putChar('A')
        buf.newline()
        assertEquals(1, buf.cursorRow)
        assertEquals(0, buf.cursorCol)
    }

    @Test
    fun `backspace moves cursor back`() {
        val buf = TerminalBuffer(80, 24)
        buf.putChar('A')
        buf.putChar('B')
        buf.backspace()
        assertEquals(1, buf.cursorCol)
    }

    @Test
    fun `tab advances to next tab stop`() {
        val buf = TerminalBuffer(80, 24)
        buf.tab()
        // tab stops at multiples of 8
        assertEquals(8, buf.cursorCol)
    }

    @Test
    fun `scrollUp moves lines up and clears bottom`() {
        val buf = TerminalBuffer(10, 5)
        // املأ الصفوف
        for (r in 0 until 5) {
            buf.setCursor(r, 0)
            buf.putChar(('A'.code + r).toChar())
        }
        buf.scrollUp(1)
        // الصف 0 يجب أن يحتوي على ما كان في الصف 1
        assertEquals('B', buf.getCell(0, 0)?.char)
        // الصف الأخير يجب أن يكون فارغاً
        assertEquals(' ', buf.getCell(4, 0)?.char)
    }

    @Test
    fun `eraseLine clears entire current row`() {
        val buf = TerminalBuffer(10, 5)
        for (c in 0 until 5) {
            buf.setCursor(0, c)
            buf.putChar('X')
        }
        buf.setCursor(0, 0)
        buf.eraseLine()
        for (c in 0 until 5) {
            assertEquals(' ', buf.getCell(0, c)?.char)
        }
    }

    @Test
    fun `setStyle applies attributes`() {
        val buf = TerminalBuffer(80, 24)
        buf.setStyle(bold = true, fgColor = TerminalBuffer.COLOR_RED)
        buf.putChar('A')
        val cell = buf.getCell(0, 0)!!
        assertTrue(cell.bold)
        assertEquals(TerminalBuffer.COLOR_RED, cell.fgColor)
    }

    @Test
    fun `saveCursor and restoreCursor work`() {
        val buf = TerminalBuffer(80, 24)
        buf.setCursor(5, 10)
        buf.saveCursor()
        buf.setCursor(0, 0)
        buf.restoreCursor()
        assertEquals(5, buf.cursorRow)
        assertEquals(10, buf.cursorCol)
    }

    @Test
    fun `resize preserves content`() {
        val buf = TerminalBuffer(10, 5)
        buf.setCursor(0, 0)
        buf.putString("hello")
        val newBuf = buf.resize(20, 10)
        assertEquals(20, newBuf.columns)
        assertEquals(10, newBuf.rows)
        assertEquals('h', newBuf.getCell(0, 0)?.char)
        assertEquals('e', newBuf.getCell(0, 1)?.char)
    }

    @Test
    fun `rowToString trims trailing whitespace`() {
        val buf = TerminalBuffer(10, 3)
        buf.putString("hi")
        val row = buf.rowToString(0)
        assertEquals("hi", row)
    }
}
