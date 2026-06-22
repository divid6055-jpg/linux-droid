package com.linuxdroid.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات Environment.
 *
 * تتحقق من:
 * - توسيع متغيرات البيئة ($VAR, ${VAR}, $?)
 * - التوسيع الآمن (إذا المتغير غير موجود → سلسلة فارغة)
 * - التعامل مع الحالات الحدية
 */
class EnvironmentTest {

    @Test
    fun `expand simple variable`() {
        val env = mapOf("HOME" to "/data/home", "USER" to "test")
        val result = Environment.expand("echo \$HOME", env)
        assertEquals("echo /data/home", result)
    }

    @Test
    fun `expand braced variable`() {
        val env = mapOf("HOME" to "/data/home")
        val result = Environment.expand("echo \${HOME}/sub", env)
        assertEquals("echo /data/home/sub", result)
    }

    @Test
    fun `expand unknown variable returns empty`() {
        val env = emptyMap<String, String>()
        val result = Environment.expand("echo \$UNKNOWN", env)
        assertEquals("echo ", result)
    }

    @Test
    fun `expand multiple variables in one string`() {
        val env = mapOf("USER" to "alice", "HOST" to "linuxdroid")
        val result = Environment.expand("\$USER@\$HOST", env)
        assertEquals("alice@linuxdroid", result)
    }

    @Test
    fun `expand dollar question mark`() {
        val env = mapOf("?" to "0")
        val result = Environment.expand("exit \$?", env)
        assertEquals("exit 0", result)
    }

    @Test
    fun `expand dollar sign alone is preserved if not followed by valid char`() {
        val env = emptyMap<String, String>()
        val result = Environment.expand("price: \$5", env)
        assertEquals("price: \$5", result)
    }

    @Test
    fun `expand with underscores in variable names`() {
        val env = mapOf("MY_VAR" to "value")
        val result = Environment.expand("\$MY_VAR_test", env)
        // يجب أن يتوسع MY_VAR ثم "test" كنص عادي
        assertEquals("value_test", result)
    }
}
