package com.linuxdroid.shell

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * اختبارات وحدوية لـ HistoryManager.
 *
 * تتحقق من:
 * - إضافة أوامر للتاريخ
 * - التنقل بالأسهم
 * - البحث العكسي
 * - منع تكرار الأوامر المتجاورة
 * - تخطّي الأوامر الحساسة (password, token)
 * - تخطّي الأوامر التي تبدأ بمسافة
 */
class HistoryManagerTest {

    private lateinit var tempFile: File
    private lateinit var manager: HistoryManager

    @Before
    fun setup() {
        tempFile = File(System.getProperty("java.io.tmpdir"), "history-test-${System.currentTimeMillis()}.txt")
        manager = HistoryManager(tempFile)
    }

    @After
    fun cleanup() {
        tempFile.delete()
    }

    @Test
    fun `add command and retrieve it`() {
        manager.add("ls -la")
        val prev = manager.previous()
        assertEquals("ls -la", prev)
    }

    @Test
    fun `multiple commands navigated in reverse order`() {
        manager.add("first")
        manager.add("second")
        manager.add("third")

        assertEquals("third", manager.previous())
        assertEquals("second", manager.previous())
        assertEquals("first", manager.previous())
    }

    @Test
    fun `next returns null at end of history`() {
        manager.add("only")
        manager.previous()
        assertNull("Should return null when going past end", manager.next())
    }

    @Test
    fun `next after previous returns later command`() {
        manager.add("first")
        manager.add("second")

        manager.previous()  // -> "second"
        manager.previous()  // -> "first"
        val next = manager.next()  // -> "second"
        assertEquals("second", next)
    }

    @Test
    fun `duplicate adjacent commands are skipped`() {
        manager.add("ls")
        manager.add("ls")  // تكرار متجاور
        manager.add("ls")  // تكرار متجاور

        manager.previous()
        // يجب أن يكون هناك أمر واحد فقط
        assertNull(manager.previous())
    }

    @Test
    fun `empty commands are not added`() {
        manager.add("")
        manager.add("   ")  // whitespace only

        assertNull(manager.previous())
    }

    @Test
    fun `commands starting with space are not added`() {
        manager.add(" ls -la")  // يبدأ بمسافة — HISTCONTROL=ignorespace

        assertNull(manager.previous())
    }

    @Test
    fun `commands with password are not added`() {
        manager.add("export PASSWORD=secret123")
        assertNull("Commands with PASSWORD should be skipped", manager.previous())
    }

    @Test
    fun `commands with token are not added`() {
        manager.add("curl -H \"Authorization: Bearer xyz\" https://api.example.com")
        assertNull("Commands with sensitive tokens should be skipped", manager.previous())
    }

    @Test
    fun `commands with api_key are not added`() {
        manager.add("export api_key=abc123")
        assertNull("Commands with api_key should be skipped", manager.previous())
    }

    @Test
    fun `searchReverse finds matching command`() {
        manager.add("ls -la")
        manager.add("cat /etc/passwd")
        manager.add("echo hello")

        val result = manager.searchReverse("echo")
        assertEquals("echo hello", result)
    }

    @Test
    fun `searchReverse finds most recent match first`() {
        manager.add("grep error log1")
        manager.add("grep error log2")
        manager.add("grep error log3")

        val result = manager.searchReverse("error")
        assertEquals("grep error log3", result)
    }

    @Test
    fun `searchReverse returns null when no match`() {
        manager.add("ls")
        manager.add("pwd")

        assertNull(manager.searchReverse("nonexistent"))
    }

    @Test
    fun `history persists to file`() {
        manager.add("persistent command")

        // أنشئ مديراً جديداً يقرأ من نفس الملف
        val newManager = HistoryManager(tempFile)
        val prev = newManager.previous()
        assertEquals("persistent command", prev)
    }

    @Test
    fun `clear empties history`() {
        manager.add("to be cleared")
        manager.clear()

        assertNull(manager.previous())
    }

    @Test
    fun `getAll returns all entries`() {
        manager.add("cmd1")
        manager.add("cmd2")
        manager.add("cmd3")

        val all = manager.getAll()
        assertEquals(3, all.size)
        assertEquals("cmd1", all[0])
        assertEquals("cmd2", all[1])
        assertEquals("cmd3", all[2])
    }

    @Test
    fun `resetPosition allows starting navigation from latest`() {
        manager.add("first")
        manager.add("second")
        manager.previous()  // -> "second"
        manager.previous()  // -> "first"
        manager.resetPosition()
        assertEquals("second", manager.previous())
    }
}
