package com.linuxdroid.ui

import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.linuxdroid.R
import com.linuxdroid.app.LinuxDroidApp
import com.linuxdroid.shell.Environment
import com.linuxdroid.shell.Session
import com.linuxdroid.shell.SessionManager
import com.linuxdroid.terminal.AnsiEscapeParser
import com.linuxdroid.terminal.TerminalBuffer
import com.linuxdroid.terminal.TerminalView
import com.linuxdroid.util.LinuxDroidLogger
import com.linuxdroid.util.PrefUtils

/**
 * النشاط الرئيسي — يدير واجهة الطرفية والجلسة النشطة.
 *
 * التدفق:
 * 1. onCreate → إنشاء الطرفية والجلسة
 * 2. الجلسة تنتج مخرجات → تمريرها إلى AnsiEscapeParser → تحديث العرض
 * 3. إدخال المستخدم → تمريره إلى الجلسة (shell)
 * 4. دورة حياة: onPause/onResume للحفاظ على الجلسة
 */
class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var sessionManager: SessionManager
    private var currentSession: Session? = null
    private lateinit var parser: AnsiEscapeParser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // إبقاء الشاشة مضاءة إذا كان مفعّلاً في الإعدادات
        if (PrefUtils.getKeepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        terminalView = findViewById(R.id.terminal_view)
        terminalView.fontSize = PrefUtils.getFontSize(this)
        terminalView.colorScheme = when (PrefUtils.getColorScheme(this)) {
            "dark" -> TerminalView.ColorScheme.DARK
            "light" -> TerminalView.ColorScheme.LIGHT
            "solarized" -> TerminalView.ColorScheme.SOLARIZED_DARK
            "dracula" -> TerminalView.ColorScheme.DRACULA
            else -> TerminalView.ColorScheme.DARK
        }

        sessionManager = SessionManager.getInstance(this)

        // تهيئة جلسة
        currentSession = sessionManager.createSession(
            cols = terminalView.getVisibleColumns(),
            rows = terminalView.getVisibleRows()
        ).also { session ->
            val buf = TerminalBuffer(terminalView.getVisibleColumns(), terminalView.getVisibleRows())
            parser = AnsiEscapeParser(buf)
            terminalView.buffer = buf
            terminalView.onResizeListener = { cols, rows ->
                session.resize(cols, rows)
            }
            terminalView.inputListener = object : TerminalView.OnInputListener {
                override fun onTextInput(text: String) {
                    session.write(text)
                }

                override fun onSpecialKey(
                    key: TerminalView.SpecialKey,
                    ctrl: Boolean,
                    alt: Boolean,
                    shift: Boolean
                ): Boolean {
                    val seq = when (key) {
                        TerminalView.SpecialKey.ENTER -> "\r"
                        TerminalView.SpecialKey.BACKSPACE -> "\u007F"  // DEL
                        TerminalView.SpecialKey.TAB -> "\t"
                        TerminalView.SpecialKey.ESCAPE -> "\u001B"
                        TerminalView.SpecialKey.ARROW_UP -> if (shift) "\u001B[1;2A" else "\u001B[A"
                        TerminalView.SpecialKey.ARROW_DOWN -> if (shift) "\u001B[1;2B" else "\u001B[B"
                        TerminalView.SpecialKey.ARROW_LEFT -> if (shift) "\u001B[1;2D" else "\u001B[D"
                        TerminalView.SpecialKey.ARROW_RIGHT -> if (shift) "\u001B[1;2C" else "\u001B[C"
                        TerminalView.SpecialKey.HOME -> "\u001B[H"
                        TerminalView.SpecialKey.END -> "\u001B[F"
                        TerminalView.SpecialKey.PAGE_UP -> "\u001B[5~"
                        TerminalView.SpecialKey.PAGE_DOWN -> "\u001B[6~"
                        TerminalView.SpecialKey.DELETE -> "\u001B[3~"
                        TerminalView.SpecialKey.INSERT -> "\u001B[2~"
                        TerminalView.SpecialKey.F1 -> "\u001BOP"
                        TerminalView.SpecialKey.F2 -> "\u001BOQ"
                        TerminalView.SpecialKey.F3 -> "\u001BOR"
                        TerminalView.SpecialKey.F4 -> "\u001BOS"
                        TerminalView.SpecialKey.F5 -> "\u001B[15~"
                        TerminalView.SpecialKey.F6 -> "\u001B[17~"
                        TerminalView.SpecialKey.F7 -> "\u001B[18~"
                        TerminalView.SpecialKey.F8 -> "\u001B[19~"
                        TerminalView.SpecialKey.F9 -> "\u001B[20~"
                        TerminalView.SpecialKey.F10 -> "\u001B[21~"
                        TerminalView.SpecialKey.F11 -> "\u001B[23~"
                        TerminalView.SpecialKey.F12 -> "\u001B[24~"
                    }
                    // ctrl+a .. ctrl+z
                    if (ctrl && key == TerminalView.SpecialKey.ENTER) {
                        // Ctrl+Enter: تجاهل
                        return@onSpecialKey false
                    }
                    session.write(seq)
                    return@onSpecialKey true
                }
            }

            // رسائل الترحيب
            val welcome = "\u001B[1;36m" + Environment.etcDir.let { java.io.File(it, "motd").takeIf { f -> f.exists() }?.readText() ?: "LinuxDroid" } + "\u001B[0m"
            session.emitOutput(welcome)

            // استقبال المخرجات
            session.onOutput = { data ->
                runOnUiThread {
                    parser.feed(data)
                    terminalView.notifyChanged()
                }
            }
            session.start()
        }

        // تطبيق الإعدادات
        applySettings()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_help -> {
                startActivity(Intent(this, HelpActivity::class.java))
                true
            }
            R.id.action_new_session -> {
                currentSession?.stop()
                recreate()
                true
            }
            R.id.action_paste -> {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.primaryClip?.getItemAt(0)?.text?.let { text ->
                    currentSession?.write(text.toString())
                }
                true
            }
            R.id.action_copy -> {
                val text = terminalView.copySelection()
                if (text.isNotEmpty()) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LinuxDroid", text))
                    Toast.makeText(this, R.string.action_copy, Toast.LENGTH_SHORT).show()
                }
                terminalView.clearSelection()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun applySettings() {
        if (PrefUtils.getKeepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onResume() {
        super.onResume()
        currentSession?.resume()
    }

    override fun onPause() {
        super.onPause()
        currentSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            currentSession?.stop()
            currentSession = null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0) {
            // تأكيد الخروج بدلاً من الإغلاق المباشر
            // (لمنع فقدان الجلسة بالخطأ)
            // يمكن إضافة dialog هنا
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
