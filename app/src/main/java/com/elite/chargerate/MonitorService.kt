package com.elite.chargerate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.Locale

class MonitorService : Service() {

    companion object {
        const val PREFS = "settings"
        const val KEY_MONITOR_ON = "monitor_on"
        const val KEY_SLOW_ALERT = "slow_alert"
        const val SLOW_CHARGE_MA = 1000
        private const val CH_MONITOR = "monitor"
        private const val CH_ALERT = "alerts"
        private const val ID_MONITOR = 1
        private const val ID_ALERT = 2
        private const val TICK_MS = 2000L
        private const val LOG_EVERY_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var reader: BatteryReader
    private lateinit var nm: NotificationManager
    private var lastLog = 0L
    private var pluggedSince = 0L
    private var alerted = false

    private val tick = object : Runnable {
        override fun run() {
            reader.read()?.let { onReading(it) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        reader = BatteryReader(this)
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "Live charge rate", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Charging alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val first = buildNotification("Starting…", "Reading battery current")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_MONITOR, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_MONITOR, first)
        }
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onReading(r: Reading) {
        Lifetime.update(this, r.mA)

        val title = String.format(Locale.US, "%s mA · %.1f W", Fmt.signedMa(r.mA), kotlin.math.abs(r.watts))
        val state = if (r.charging) "Charging" else if (r.plugged) "Plugged in" else "Discharging"
        val rate = if (r.pctPerHour.isNaN()) "" else String.format(Locale.US, " · %.1f%%/h", r.pctPerHour)
        val eta = if (r.etaHours.isNaN()) "" else
            (if (r.charging) " · full in " else " · ") + Fmt.duration(r.etaHours) + (if (r.charging) "" else " left")
        val body = String.format(Locale.US, "%s · %.0f%%%s%s", state, r.percent, rate, eta)
        nm.notify(ID_MONITOR, buildNotification(title, body))

        if (r.timeMs - lastLog >= LOG_EVERY_MS) {
            lastLog = r.timeMs
            History.append(this, r)
        }

        checkSlowCharge(r)
    }

    private fun checkSlowCharge(r: Reading) {
        val on = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SLOW_ALERT, false)
        if (!r.charging) {
            pluggedSince = 0L
            alerted = false
            return
        }
        if (pluggedSince == 0L) pluggedSince = r.timeMs
        val settled = r.timeMs - pluggedSince > 60_000L
        if (on && settled && !alerted && r.percent < 90f && r.mA in 1 until SLOW_CHARGE_MA) {
            alerted = true
            val n = Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Slow charging")
                .setContentText("Only ${r.mA} mA going in. Check the cable or charger.")
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
            nm.notify(ID_ALERT, n)
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(title: String, text: String): Notification =
        Notification.Builder(this, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()
}
