package com.linuxdroid.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.linuxdroid.R
import com.linuxdroid.security.SecurityManager
import com.linuxdroid.shell.Environment
import com.linuxdroid.util.LinuxDroidLogger

/**
 * نقطة دخول التطبيق الرئيسية.
 *
 * المسؤوليات:
 * 1. تهيئة القنوات الإشعارية للجلسات الخلفية
 * 2. إنشاء وإعداد البيئة الافتراضية (HOME، PATH، إلخ)
 * 3. تهيئة مدير الأمان (منع التصحيح في الإصدار النهائي، فحص السلامة)
 * 4. تهيئة نظام التسجيل
 *
 * الأمان: نتحقق من حالة التصحيح ونمنع التشغيل في بيئات خطرة.
 */
class LinuxDroidApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. نظام التسجيل أولاً
        LinuxDroidLogger.init(this)

        // 2. فحص الأمان قبل أي شيء آخر
        SecurityManager.initialize(this)

        // 3. قنوات الإشعارات للجلسات الخلفية
        createNotificationChannels()

        // 4. تهيئة البيئة (متغيرات shell)
        Environment.initialize(this)

        LinuxDroidLogger.i(TAG, "LinuxDroid Application initialized successfully")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_SESSION,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "LinuxDroidApp"
        const val CHANNEL_SESSION = "session_channel"

        @Volatile
        private lateinit var instance: LinuxDroidApp

        @JvmStatic
        fun get(): LinuxDroidApp = instance
    }
}
