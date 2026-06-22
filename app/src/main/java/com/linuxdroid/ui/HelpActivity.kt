package com.linuxdroid.ui

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.linuxdroid.R

/**
 * شاشة المساعدة — عرض دليل استخدام التطبيق.
 */
class HelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val text = findViewById<TextView>(R.id.help_text)
        text.movementMethod = LinkMovementMethod.getInstance()
        text.text = """
            ${getString(R.string.help_intro)}

            ${getString(R.string.help_commands_title)}

            • ls, cd, pwd, cat, cp, mv, rm, mkdir, touch, find, tree
            • grep, sed, awk, head, tail, wc, sort, uniq, cut, tr, tee
            • ps, kill, top, whoami, uname, date, env, which, sleep
            • curl, wget, ping, netstat, ifconfig, nslookup
            • pkg install/remove/list/search/update/upgrade
            • nano (محرر نصوص), vi (stub)
            • clear, reset, history, alias, export, source
            • echo, printf, seq, yes, cal, free, df, du, stat

            ${getString(R.string.help_help)}

            مثال:
              pkg update
              pkg install bash-utils
              ls -la /usr/share

            الاختصارات:
              Ctrl+C  → إيقاف الأمر الحالي
              Ctrl+D  → إنهاء الإدخال (EOF)
              Ctrl+L  → مسح الشاشة
              Tab     → إكمال الأمر/الملف
              ↑/↓     → تصفّح التاريخ

            للمساعدة: اكتب 'help' في الطرفية.
            للتبليغ عن أخطاء: https://github.com/divid6055-jpg/linux-droid/issues
        """.trimIndent()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
