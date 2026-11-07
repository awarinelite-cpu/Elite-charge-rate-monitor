package com.elite.chargerate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val on = context.getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(MonitorService.KEY_MONITOR_ON, true)
        if (!on) return
        try {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        } catch (_: Exception) {
            // Some OS versions block starting from boot; opening the app restarts it.
        }
    }
}
