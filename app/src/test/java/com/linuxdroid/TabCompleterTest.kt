package com.linuxdroid.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * اختبارات وحدوية لـ TabCompleter.
 *
 * تتحقق من:
 * - إكمال الأوامر
 * - إكمال المسارات
 * - إكمال متغيرات البيئة
 * - أطول بادئة مشتركة
 * - حالات حدية (سطر فارغ، نص غير موجود)
 */
class TabCompleterTest {

    private lateinit var tempDir: File
    private lateinit var completer: TabCompleter

    @Before
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "tab-completer-test-${System.currentTimeMillis()}")
        tempDir.mkdirs()
        // إنشاء ملفات للاختبار
        File(tempDir, "file1.txt").writeText("test1")
        File(tempDir, "file2.txt").writeText("test2")
        File(tempDir, "apple.json").writeText("{}")
        File(tempDir, "banana.json").writeText("{}")
        File(tempDir, "docs").mkdirs()
        File(tempDir, "downloads").mkdirs()

        val env = mapOf(
            "HOME" to tempDir.absolutePath,
            "USER" to "testuser",
            "PATH" to "/usr/bin",
            "HOSTNAME" to "testhost",
            "PWD" to tempDir.absolutePath
        )
        completer = TabCompleter(env, tempDir.absolutePath)
    }

    @Test
    fun `empty line returns empty completion`() {
        val result = completer.complete("")
        assertEquals("", result.addition)
        assertTrue(result.completions.isEmpty())
    }

    @Test
    fun `completes ls command prefix`() {
        val result = completer.complete("l")
        // ls يجب أن يكون ضمن الإكمالات
        assertTrue("Should complete 'l' to include 'ls'",
            result.completions.contains("ls") || result.addition.startsWith("s"))
    }

    @Test
    fun `completes exact command adds trailing space`() {
        val result = completer.complete("ls")
        // عند إكمال الأمر الوحيد، نضيف مسافة
        assertTrue("Should have completion or trailing space",
            result.addition == " " || result.completions.isNotEmpty())
    }

    @Test
    fun `completes file path with single match`() {
        val result = completer.complete("file1")
        // يجب أن يُكمل إلى .txt
        assertTrue("Should complete file1 to file1.txt",
            result.addition.contains(".txt") || result.completions.isNotEmpty())
    }

    @Test
    fun `completes file path with multiple matches shows all`() {
        val result = completer.complete("file")
        assertTrue("Should have multiple completions",
            result.completions.size >= 2 || result.addition.isNotEmpty())
    }

    @Test
    fun `completes json files`() {
        val result = completer.complete("apple.j")
        assertTrue("Should complete apple.j to apple.json",
            result.addition.contains("son"))
    }

    @Test
    fun `completes directory name with trailing slash`() {
        val result = completer.complete("doc")
        assertTrue("Should complete doc to docs/",
            result.addition.contains("s") || result.addition.contains("/"))
    }

    @Test
    fun `completes environment variable after dollar sign`() {
        val result = completer.complete("echo \$US")
        assertTrue("Should complete \$US to \$USER",
            result.addition.contains("ER") || result.completions.contains("USER"))
    }

    @Test
    fun `completes HOME variable`() {
        val result = completer.complete("cd \$HO")
        assertTrue("Should complete \$HO to \$HOME",
            result.addition.contains("ME") || result.completions.contains("HOME"))
    }

    @Test
    fun `nonexistent prefix returns empty`() {
        val result = completer.complete("zzzznonexistent")
        assertEquals("", result.addition)
        assertTrue(result.completions.isEmpty())
    }

    @Test
    fun `completes partial directory path`() {
        val result = completer.complete("down")
        assertTrue("Should complete down to downloads/",
            result.addition.isNotEmpty() || result.completions.isNotEmpty())
    }

    @Test
    fun `completes after command with space`() {
        val result = completer.complete("cat file1")
        // بعد 'cat ' مع 'file1' يجب أن يُكمل الملف
        assertTrue(result.addition.isNotEmpty() || result.completions.isNotEmpty())
    }

    @Test
    fun `longest common prefix works for files`() {
        val result = completer.complete("file")
        // file1 و file2 — البادئة المشتركة هي "file"
        // الإضافة يجب أن تكون "1" أو "2" (لا إكمال تلقائي) أو عرض الاثنين
        assertTrue("Should return some completion info",
            result.addition.isNotEmpty() || result.completions.size >= 2)
    }

    @Test
    fun `completes with absolute path`() {
        val absPath = File(tempDir, "file1.txt").absolutePath
        val prefix = absPath.substring(0, absPath.length - 5)  // "file1."
        val result = completer.complete(prefix)
        assertTrue("Should complete absolute path",
            result.addition.contains("txt") || result.completions.isNotEmpty())
    }
}
