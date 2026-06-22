package com.linuxdroid.ui

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.linuxdroid.R

/**
 * شاشة الإعدادات.
 *
 * تتيح للمستخدم تخصيص:
 *  - حجم الخط
 *  - نظام الألوان
 *  - شكل المؤشر
 *  - إبقاء الشاشة
 *  - الاهتزاز عند الكتابة
 *  - سلوك زر الرجوع
 *  - المجلد الرئيسي
 *  - المفسّر الافتراضي
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // ربط الإجراءات المخصصة
            findPreference<SeekBarPreference>("font_size")?.let { pref ->
                pref.min = 12
                pref.max = 48
            }
        }
    }
}
