package com.linuxdroid.process

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.linuxdroid.R
import com.linuxdroid.app.LinuxDroidApp
import com.linuxdroid.shell.SessionManager
import com.linuxdroid.ui.MainActivity
import com.linuxdroid.util.LinuxDroidLogger

/**
 * خدمة أمامية (foreground service) تحافظ على الجلسات نشطة في الخلفية.
 *
 * تبدأ عند تشغيل أول جلسة وتتوقف عند إغلاق آخر جلسة.
 * هذا يمنع النظام من قتل التطبيق أثناء تنفيذ أوامر طويلة.
 */
class SessionService : Service() {

    override fun onCreate() {
        super.onCreate()
        LinuxDroidLogger.i(TAG, "SessionService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, LinuxDroidApp.CHANNEL_SESSION)
            .setContentTitle(getString(R.string.notif_session_title))
            .setContentText(getString(R.string.notif_session_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        LinuxDroidLogger.i(TAG, "SessionService destroyed")
        SessionManager.getInstance(this).closeAll()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SessionService"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, SessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionService::class.java))
        }
    }
}
