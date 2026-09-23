package com.elishaazaria.sayboard.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elishaazaria.sayboard.sayboardPreferenceModel

/** Re-applies the keep-alive opt-in after a reboot. */
class KeepAliveBootReceiver : BroadcastReceiver() {
    private val prefs by sayboardPreferenceModel()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Prefs read is disk I/O: stay off the broadcast thread.
        val pending = goAsync()
        Thread {
            try {
                KeepAlive.sync(context, prefs.logicKeepAliveService.get())
            } finally {
                pending.finish()
            }
        }.start()
    }
}
