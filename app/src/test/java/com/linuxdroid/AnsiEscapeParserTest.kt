package com.linuxdroid.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * اختبارات AnsiEscapeParser.
 *
 * تتحقق من معالجة:
 * - أحرف التحكم (BS, HT, LF, CR)
 * - تسلسلات CSI (cursor movement, erase)
 * - SGR (ألوان وسمات)
 * - DECSC/DECRC (حفظ/استعادة المؤشر)
 */
class AnsiEscapeParserTest {

    @Test
    fun `LF moves cursor down`() {
        val buf = TerminalBuffer(80, 24)
        val parser = AnsiEscapeParser(buf)
        parser.feed("hello\n")
        assertEquals(1, buf.cursorRow)
        assertEquals(0, buf.cursorCol)
    }

    @Test
    fun `CR returns cursor to column 0`() {
        val buf = TerminalBuffer(80, 24)
        val parser = AnsiEscapeParser(buf)
        parser.feed("abc\r")
        assertEquals(0, buf.cursorCol)
    }

    @Test
    fun `BS moves cursor back`() {
        val buf = TerminalBuffer(80, 24)
        val parser = AnsiEscapeParser(buf)
        parser.feed("abc\b")
        assertEquals(2, buf.cursorCol)
    }

    @Test
    fun `HT moves to next tab stop`() {
        val buf = TerminalBuffer(80, 24)
        val parser = AnsiEscapeParser(buf)
        parser.feed("\t")
        assertEquals(8, buf.cursorCol)
    }

    @Test
    fun `CSI A moves cursor up`() {
        val buf = TerminalBuffer(80, 24)
        buf.setCursor(5, 5)
        val parser = AnsiEscapeParser(buf)
        parser.feed("\u001B[2A")
        assertEquals(3, buf.cursorRow)
    }

    @Test
    fun `CSI B moves cursor down`() {
        val buf = TerminalBuffer(80, 24)
        val parser = AnsiEscapeParser(buf)
        parser.feed("\u001B[3B")
        assertEquals(3, buf.cursorRow)
    }

    @Test
    fun `CSI H sets cursor position`() {
        val buf = TerminalBuffer(80, 24)
        val parser = AnsiEscapeParser(buf)
        parser.feed("\u001B[10;20H")
        assertEquals(9, buf.cursorRow)
        assertEquals(19, buf.cursorCol)
    }

    @Test
    fun `CSI 2J clears screen`() {
        val buf = TerminalBuffer(80, 24)
        buf.putString("hello world")
        val parser = AnsiEscapeParser(buf)
        parser.feed("\u001B[2J")
        assertEquals(' ', buf.getCell(0, 0)?.char)
    }

    @Test
    fun `CSI K clears line from cursor`() {
        val buf = TerminalBuffer(80, 24)
        buf.putString("hello world")
        buf.setCursor(0, 5)
        val parser = AnsiEscapeParser(buf)
        parser.feed("\u001B[K")
        assertEquals('h', buf.getCell(0, 0)?.char)
        assertEquals(' ', buf.getCell(0, 5)?.char)
    }

    @Test
    fun `SGR sets bold and color`() {
        val buf = TerminalBuffer(80, 24)
        val parser = AnsiEscapeParser(buf)
        parser.feed("\u001B[1;31mA")
        val cell = buf.getCell(0, 0)!!
        assertEquals(true, cell.bold)
        assertEquals(TerminalBuffer.COLOR_RED, cell.fgColor)
    }

    @Test
    fun `SGR 0 resets attributes`() {
        val buf = TerminalBuffer(80, 24)
        val parser = AnsiEscapeParser(buf)
        parser.feed("\u001B[1;31mA\u001B[0mB")
        val cellB = buf.getCell(0, 1)!!
        assertEquals(false, cellB.bold)
        assertEquals(TerminalBuffer.COLOR_DEFAULT, cellB.fgColor)
    }

    @Test
    fun `ESC 7 saves cursor and ESC 8 restores`() {
        val buf = TerminalBuffer(80, 24)
        buf.setCursor(5, 10)
        val parser = AnsiEscapeParser(buf)
        parser.feed("\u001B7")  // save
        buf.setCursor(0, 0)
        parser.feed("\u001B8")  // restore
        assertEquals(5, buf.cursorRow)
        assertEquals(10, buf.cursorCol)
    }

    @Test
    fun `multiple SGR sequences in one string`() {
        val buf = TerminalBuffer(80, 24)
        val parser = AnsiEscapeParser(buf)
        parser.feed("\u001B[1m\u001B[31m\u001B[4mHello")
        val cell = buf.getCell(0, 0)!!
        assertEquals(true, cell.bold)
        assertEquals(TerminalBuffer.COLOR_RED, cell.fgColor)
        assertEquals(true, cell.underline)
    }
}
