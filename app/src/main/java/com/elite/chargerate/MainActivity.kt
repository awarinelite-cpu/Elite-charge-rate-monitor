package com.elite.chargerate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var reader: BatteryReader
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var bigMa: TextView
    private lateinit var stateLine: TextView
    private lateinit var rateLine: TextView
    private lateinit var etaLine: TextView
    private lateinit var statsLine: TextView
    private lateinit var lifeLine: TextView
    private lateinit var graph: GraphView
    private lateinit var monitorBtn: Button
    private var ticks = 0

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private val prefs get() = getSharedPreferences(MonitorService.PREFS, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = BatteryReader(this)
        buildUi()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (prefs.getBoolean(MonitorService.KEY_MONITOR_ON, true)) startMonitor()
    }

    override fun onResume() {
        super.onResume()
        ticks = 0
        handler.post(tick)
        updateMonitorButton()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        bigMa = tv(56f, "#F8FAFC", true).apply { gravity = Gravity.CENTER_HORIZONTAL }
        stateLine = tv(16f, "#94A3B8").apply { gravity = Gravity.CENTER_HORIZONTAL }
        rateLine = tv(18f, "#F8FAFC", true).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        etaLine = tv(16f, "#CBD5E1").apply { gravity = Gravity.CENTER_HORIZONTAL }
        statsLine = tv(14f, "#94A3B8").apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        lifeLine = tv(14f, "#94A3B8").apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(4), 0, dp(12)) }

        graph = GraphView(this)
        val graphLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180))

        monitorBtn = btn("") { toggleMonitor() }

        val slow = Switch(this).apply {
            text = "Alert when charging is slow (under ${MonitorService.SLOW_CHARGE_MA} mA)"
            setTextColor(Color.parseColor("#CBD5E1"))
            isChecked = prefs.getBoolean(MonitorService.KEY_SLOW_ALERT, false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(MonitorService.KEY_SLOW_ALERT, on).apply()
            }
            setPadding(0, dp(12), 0, dp(12))
        }

        col.addView(bigMa)
        col.addView(stateLine)
        col.addView(rateLine)
        col.addView(etaLine)
        col.addView(statsLine)
        col.addView(lifeLine)
        col.addView(graph, graphLp)
        col.addView(slow)
        col.addView(monitorBtn)
        col.addView(btn("Keep running in background") { requestUnrestricted() })
        col.addView(btn("Reset lifetime peaks") {
            Lifetime.reset(this)
            refresh()
        })
        col.addView(btn("Clear history log") {
            History.clear(this)
            graph.setData(emptyList())
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            addView(col)
        })
    }

    private fun refresh() {
        val r = reader.read()
        if (r == null) {
            bigMa.text = "—"
            stateLine.text = "Battery current is not available on this device"
            return
        }
        Lifetime.update(this, r.mA)

        bigMa.text = Fmt.signedMa(r.mA)
        bigMa.setTextColor(
            Color.parseColor(if (r.charging) "#22C55E" else if (r.mA < 0) "#F87171" else "#F8FAFC")
        )
        stateLine.text = "mA · " + when {
            r.charging -> "Charging"
            r.plugged -> "Plugged in, not charging"
            else -> "Discharging"
        }

        rateLine.text = if (r.pctPerHour.isNaN()) "%/hour: —" else
            String.format(Locale.US, "%.1f%% per hour · %.1f W", r.pctPerHour, abs(r.watts))
        etaLine.text = if (r.charging) "Full in ${Fmt.duration(r.etaHours)}" else "Lasts ${Fmt.duration(r.etaHours)}"

        statsLine.text = String.format(
            Locale.US, "%.1f%% · %.2f V · %.1f °C", r.percent, r.mV / 1000.0, r.tempC
        )
        lifeLine.text = "Lifetime peak: +${Lifetime.maxCharge(this)} mA / ${Lifetime.maxDischarge(this)} mA"

        if (ticks % 10 == 0) graph.setData(History.load(this))
        ticks++
    }

    private fun startMonitor() {
        prefs.edit().putBoolean(MonitorService.KEY_MONITOR_ON, true).apply()
        try {
            startForegroundService(Intent(this, MonitorService::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start monitor: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMonitor() {
        prefs.edit().putBoolean(MonitorService.KEY_MONITOR_ON, false).apply()
        stopService(Intent(this, MonitorService::class.java))
    }

    private fun toggleMonitor() {
        if (prefs.getBoolean(MonitorService.KEY_MONITOR_ON, true)) stopMonitor() else startMonitor()
        updateMonitorButton()
    }

    private fun updateMonitorButton() {
        monitorBtn.text = if (prefs.getBoolean(MonitorService.KEY_MONITOR_ON, true))
            "Stop background monitor" else "Start background monitor"
    }

    private fun requestUnrestricted() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already unrestricted", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
    }
}
