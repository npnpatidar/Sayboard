package com.elishaazaria.sayboard.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.elishaazaria.sayboard.Constants
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.SettingsActivity
import com.elishaazaria.sayboard.sayboardPreferenceModel
import com.elishaazaria.sayboard.utils.AppLog

/**
 * Opt-in keep-alive: a low-priority foreground service whose only job is to
 * keep the process (and the loaded speech model) alive when the keyboard is
 * not in the foreground, e.g. after switching to another keyboard. The mic
 * is never touched here; idle cost is RAM only.
 */
class KeepAliveService : Service() {
    private val prefs by sayboardPreferenceModel()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.d(TAG, "service created pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLog.d(TAG, "stop action")
            prefs.logicKeepAliveService.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        // I-13: a null intent is a process-restart resurrection, not a user
        // start — re-read prefs and stop when the toggle is off.
        if (intent == null && !runCatching { prefs.logicKeepAliveService.get() }.getOrDefault(false)) {
            AppLog.d(TAG, "null intent while disabled, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        AppLog.d(TAG, "promoting to foreground")
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // I-13/B6: sticky only while the opt-in is on.
        return if (runCatching { prefs.logicKeepAliveService.get() }.getOrDefault(false)) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        AppLog.d(TAG, "service destroyed")
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(Constants.KEEP_ALIVE_CHANNEL_ID)
        // Importance is immutable once created: only delete/recreate when it
        // differs, so we never wipe the user's mute/block customization.
        if (existing != null && existing.importance != NotificationManager.IMPORTANCE_MIN) {
            manager.deleteNotificationChannel(Constants.KEEP_ALIVE_CHANNEL_ID)
        }
        if (manager.getNotificationChannel(Constants.KEEP_ALIVE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    Constants.KEEP_ALIVE_CHANNEL_ID,
                    getString(R.string.notification_keep_alive_channel),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openSettings = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val turnOff = PendingIntent.getService(
            this,
            1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Constants.KEEP_ALIVE_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_keep_alive_title))
            .setContentText(getString(R.string.notification_keep_alive_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openSettings)
            .addAction(0, getString(R.string.notification_keep_alive_stop), turnOff)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.elishaazaria.sayboard.keepalive.STOP"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "KeepAlive"
    }
}

/** Starts the keep-alive service when opted in, stops it otherwise. */
object KeepAlive {
    fun sync(context: Context, keepAlive: Boolean) {
        val intent = Intent(context, KeepAliveService::class.java)
        if (keepAlive) {
            try {
                ContextCompat.startForegroundService(context, intent)
                AppLog.d(TAG, "sync: start requested")
            } catch (e: Exception) {
                // Background-start restriction (Android 12+), e.g. from the
                // boot receiver: surface a tap-to-enable notification so the
                // toggle does not show "on" with nothing running.
                AppLog.e(TAG, "sync: start failed", e)
                showReEnableNotification(context)
            }
        } else {
            AppLog.d(TAG, "sync: stop requested")
            context.stopService(intent)
        }
    }

    /**
     * Best-effort regular notification (allowed from background, unlike a
     * foreground-service start) pointing back at Settings. Silent if the
     * user denied POST_NOTIFICATIONS.
     */
    private fun showReEnableNotification(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager =
                context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(Constants.KEEP_ALIVE_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        Constants.KEEP_ALIVE_CHANNEL_ID,
                        context.getString(R.string.notification_keep_alive_channel),
                        NotificationManager.IMPORTANCE_MIN
                    )
                )
            }
            val openSettings = PendingIntent.getActivity(
                context,
                2,
                Intent(context, SettingsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(
                context, Constants.KEEP_ALIVE_CHANNEL_ID
            )
                .setContentTitle(context.getString(R.string.notification_keep_alive_title))
                .setContentText(context.getString(R.string.notification_keep_alive_reenable))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(openSettings)
                .setAutoCancel(true)
                .build()
            manager.notify(REENABLE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            AppLog.e(TAG, "re-enable notification failed", e)
        }
    }

    private const val TAG = "KeepAlive"
    private const val REENABLE_NOTIFICATION_ID = 3
}
