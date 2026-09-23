package com.elishaazaria.sayboard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import com.elishaazaria.sayboard.AppCtx.setAppCtx
import com.elishaazaria.sayboard.utils.AppLog
import com.elishaazaria.sayboard.utils.Key
import com.elishaazaria.sayboard.utils.defaultCustomKeysList
import dev.patrickgold.jetpref.datastore.JetPref
import org.greenrobot.eventbus.EventBus
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class SayboardApplication : Application() {
    private val prefs by sayboardPreferenceModel()
    override fun onCreate() {
        super.onCreate()

        // U4: the crash-report activity runs in :crash_report — skip all
        // non-crash init there (prefs, bus, logging need the main process).
        if (isCrashReportProcess()) return

        AppLog.init(this)
        installCrashReporter()

        // Optionally initialize global JetPref configs. This must be done before
        // any preference datastore is initialized!
        JetPref.configure(
            saveIntervalMs = 500,
            encodeDefaultValues = true,
        )

        // Initialize your datastore here (required)
        prefs.initializeBlocking(this)

        // U1 opt-out: file logging follows the pref from here on.
        AppLog.fileLoggingEnabled = runCatching { prefs.logicFileLogging.get() }.getOrDefault(true)

        migrateCustomKeysDefault()

        EventBus.builder().logNoSubscriberMessages(false).sendNoSubscriberEvent(false)
            .installDefaultEventBus()
        setAppCtx(this)
    }

    /**
     * One-time migration: installs that still carry a previous built-in
     * custom row get the current dual-action built-ins. User-edited rows
     * are left untouched.
     */
    private fun migrateCustomKeysDefault() {
        fun single(label: String, text: String) = Key(label, text)
        val legacyDefaults = listOf(
            // Original built-ins
            listOf(
                single("-", "-"),
                single(",", ","),
                single("?", "?"),
                single("\"", "\""),
                single("'", "'")
            ),
            // Later single-action sets
            listOf(
                single(".", "."),
                single(",", ","),
                single("?", "?"),
                single("-", "-")
            ),
            listOf(
                single(".", "."),
                single(",", ","),
                single("?", "?"),
                single("-", "-"),
                single("!", "!")
            )
        )
        try {
            if (legacyDefaults.contains(prefs.keyboardKeysCustom.get())) {
                prefs.keyboardKeysCustom.set(defaultCustomKeysList)
            }
        } catch (e: Exception) {
            AppLog.e("SayboardApplication", "custom keys migration failed", e)
        }
    }

    /**
     * Catches fatal crashes and shows [CrashReportActivity] (separate process,
     * framework views only) with copy/share actions, so users without adb can
     * still report the stack trace.
     */
    private fun installCrashReporter() {
        // U4: chain the prior handler instead of swallowing it.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (!crashReported) {
                crashReported = true
                try {
                    val report = buildCrashReport(thread, throwable)
                    AppLog.eNow("Crash", report)
                    val intent = Intent(this, CrashReportActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(CrashReportActivity.EXTRA_TRACE, report)
                    }
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {
                        // Background activity launch is blocked on Android
                        // 10+ exactly when the crash happens off-screen.
                    }
                    // Reliable path: a crash notification survives the
                    // background-launch ban (best-effort; dropped silently
                    // when notifications are denied).
                    postCrashNotification(report)
                } catch (_: Exception) {
                }
            }
            try {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                    return@setDefaultUncaughtExceptionHandler
                }
            } catch (_: Exception) {
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    private fun postCrashNotification(report: String) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CRASH_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CRASH_CHANNEL_ID,
                        "Sayboard crash reports",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val intent = Intent(this, CrashReportActivity::class.java).apply {
                putExtra(CrashReportActivity.EXTRA_TRACE, report)
            }
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CRASH_CHANNEL_ID)
                .setContentTitle("Sayboard crashed")
                .setContentText("Tap to view and share the crash report")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            manager.notify(CRASH_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        // U3: device model/manufacturer stripped (SDK+release only).
        return buildString {
            appendLine("Sayboard ${BuildConfig.VERSION_NAME} v${BuildConfig.VERSION_CODE}")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(writer.toString())
        }.take(100_000).toString()
    }

    /** U4: CrashReportActivity runs in a `:crash_report` process. */
    private fun isCrashReportProcess(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName().endsWith(":crash_report")
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        @Volatile
        private var crashReported = false
        private const val CRASH_CHANNEL_ID = "crash"
        private const val CRASH_NOTIFICATION_ID = 4
    }
}