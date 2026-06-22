package com.linuxdroid.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات وحدوية لأدوات الأمان.
 *
 * تتحقق من:
 * - فلترة الأسرار في الـ logs
 * - تحقق URL (منع SSRF)
 * - تحقق أسماء الحزم والإصدارات
 * - تحقق SHA-256
 */
class SecurityUtilsTest {

    // --- Sanitize Log Message ---

    @Test
    fun `sanitize redacts password from log`() {
        val input = "user entered: password=secret123"
        val result = SecurityUtils.sanitizeLogMessage(input)
        assertTrue("Password should be redacted", result.contains("[REDACTED]"))
        assertFalse("Original password should not appear", result.contains("secret123"))
    }

    @Test
    fun `sanitize redacts API key from log`() {
        val input = "export API_KEY=sk-abc123"
        val result = SecurityUtils.sanitizeLogMessage(input)
        assertTrue(result.contains("[REDACTED]"))
        assertFalse(result.contains("sk-abc123"))
    }

    @Test
    fun `sanitize redacts Authorization header`() {
        val input = "curl -H \"Authorization: Bearer xyz123\" https://api.example.com"
        val result = SecurityUtils.sanitizeLogMessage(input)
        assertTrue(result.contains("[REDACTED]"))
        assertFalse(result.contains("xyz123"))
    }

    @Test
    fun `sanitize preserves non-sensitive content`() {
        val input = "ls -la /home/user"
        val result = SecurityUtils.sanitizeLogMessage(input)
        assertEquals(input, result)
    }

    // --- URL Validation (SSRF Prevention) ---

    @Test
    fun `validateUrl blocks file protocol`() {
        val result = SecurityUtils.validateUrl("file:///etc/passwd")
        assertFalse(result.ok)
    }

    @Test
    fun `validateUrl blocks ftp protocol`() {
        val result = SecurityUtils.validateUrl("ftp://example.com/file")
        assertFalse(result.ok)
    }

    @Test
    fun `validateUrl blocks loopback address`() {
        val result = SecurityUtils.validateUrl("http://127.0.0.1/admin")
        assertFalse("127.0.0.1 should be blocked", result.ok)
    }

    @Test
    fun `validateUrl blocks localhost`() {
        val result = SecurityUtils.validateUrl("http://localhost:8080/")
        assertFalse("localhost should be blocked", result.ok)
    }

    @Test
    fun `validateUrl blocks private network`() {
        val result = SecurityUtils.validateUrl("http://192.168.1.1/admin")
        assertFalse("192.168.x.x should be blocked", result.ok)
    }

    @Test
    fun `validateUrl blocks cloud metadata IP`() {
        val result = SecurityUtils.validateUrl("http://169.254.169.254/latest/meta-data/")
        assertFalse("169.254.169.254 should be blocked", result.ok)
    }

    @Test
    fun `validateUrl blocks 0 dot 0 dot 0 dot 0`() {
        val result = SecurityUtils.validateUrl("http://0.0.0.0/")
        assertFalse(result.ok)
    }

    // --- Package Name Validation ---

    @Test
    fun `valid package name accepted`() {
        assertTrue(SecurityUtils.isValidPackageName("bash-utils"))
        assertTrue(SecurityUtils.isValidPackageName("python3"))
        assertTrue(SecurityUtils.isValidPackageName("org.gnu.bash"))
    }

    @Test
    fun `package name with path traversal blocked`() {
        assertFalse(SecurityUtils.isValidPackageName("../../etc/passwd"))
        assertFalse(SecurityUtils.isValidPackageName("/etc/passwd"))
    }

    @Test
    fun `empty package name rejected`() {
        assertFalse(SecurityUtils.isValidPackageName(""))
    }

    @Test
    fun `package name with special chars rejected`() {
        assertFalse(SecurityUtils.isValidPackageName("pkg\$name"))
        assertFalse(SecurityUtils.isValidPackageName("pkg;rm"))
        assertFalse(SecurityUtils.isValidPackageName("pkg`whoami`"))
    }

    // --- Version Validation ---

    @Test
    fun `valid version accepted`() {
        assertTrue(SecurityUtils.isValidVersion("1.0.0"))
        assertTrue(SecurityUtils.isValidVersion("2.5.3-beta"))
        assertTrue(SecurityUtils.isValidVersion("1.0+build1"))
    }

    @Test
    fun `version with path separator rejected`() {
        assertFalse(SecurityUtils.isValidVersion("1.0/../etc"))
    }

    // --- SHA-256 Validation ---

    @Test
    fun `valid sha256 accepted`() {
        val valid = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertTrue(SecurityUtils.isValidSha256(valid))
    }

    @Test
    fun `invalid sha256 rejected`() {
        assertFalse(SecurityUtils.isValidSha256("abc123"))
        assertFalse(SecurityUtils.isValidSha256(""))
        assertFalse(SecurityUtils.isValidSha256("g".repeat(64)))  // non-hex
    }
}
