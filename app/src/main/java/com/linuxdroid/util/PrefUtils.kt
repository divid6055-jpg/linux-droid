package com.linuxdroid.util

import android.content.Context
import android.content.SharedPreferences

/**
 * أدوات الإعدادات المشتركة (Preferences).
 *
 * المسؤوليات:
 * - توفير واجهة آمنة لقراءة/كتابة الإعدادات
 * - تطبيق القيم الافتراضية
 * - تجنّب تكرار الكود في عدة أنشطة
 */
object PrefUtils {

    private const val PREFS_NAME = "linuxdroid_prefs"

    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_COLOR_SCHEME = "color_scheme"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_VIBRATE_ON_KEY = "vibrate_on_key"
    private const val KEY_BACK_KEY_ACTION = "back_key_action"
    private const val KEY_HOME_DIR = "home_dir"
    private const val KEY_SHELL = "shell"
    private const val KEY_CURSOR_STYLE = "cursor_style"
    private const val KEY_FIRST_RUN = "first_run"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getFontSize(context: Context): Float {
        return prefs(context).getFloat(KEY_FONT_SIZE, 28f)
    }

    fun setFontSize(context: Context, size: Float) {
        prefs(context).edit().putFloat(KEY_FONT_SIZE, size).apply()
    }

    fun getColorScheme(context: Context): String {
        return prefs(context).getString(KEY_COLOR_SCHEME, "dark") ?: "dark"
    }

    fun setColorScheme(context: Context, scheme: String) {
        prefs(context).edit().putString(KEY_COLOR_SCHEME, scheme).apply()
    }

    fun getKeepScreenOn(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, false)
    }

    fun setKeepScreenOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, on).apply()
    }

    fun getVibrateOnKey(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_VIBRATE_ON_KEY, true)
    }

    fun setVibrateOnKey(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBRATE_ON_KEY, on).apply()
    }

    fun getBackKeyAction(context: Context): String {
        return prefs(context).getString(KEY_BACK_KEY_ACTION, "close") ?: "close"
    }

    fun setBackKeyAction(context: Context, action: String) {
        prefs(context).edit().putString(KEY_BACK_KEY_ACTION, action).apply()
    }

    fun getCursorStyle(context: Context): String {
        return prefs(context).getString(KEY_CURSOR_STYLE, "block") ?: "block"
    }

    fun setCursorStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_CURSOR_STYLE, style).apply()
    }

    fun isFirstRun(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FIRST_RUN, true)
    }

    fun setFirstRunDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_FIRST_RUN, false).apply()
    }
}
