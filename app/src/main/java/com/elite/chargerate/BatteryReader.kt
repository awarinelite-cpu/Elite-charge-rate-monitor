package com.elite.chargerate

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs
import kotlin.math.roundToInt

data class Reading(
    val timeMs: Long,
    val mA: Int,            // signed: + charging, - discharging (smoothed)
    val rawMa: Int,         // signed, unsmoothed
    val percent: Float,
    val mV: Int,
    val tempC: Float,
    val charging: Boolean,
    val plugged: Boolean,
    val fullMah: Float,
    val pctPerHour: Float,  // NaN when unknown
    val etaHours: Float,    // NaN when unknown
    val statusText: String = "Unknown",
    val plugName: String = "On battery",
    val healthText: String = "Unknown",
    val tech: String = "—",
    val cycles: Int = -1,   // -1 when the device does not report it
    val soh: Int = -1,      // state of health percent, -1 when not reported
) {
    val watts: Double get() = mA / 1000.0 * mV / 1000.0
}

class BatteryReader(private val ctx: Context) {
    private val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private var ema: Double? = null

    fun read(): Reading? {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null

        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) level * 100f / scale else 0f
        val mV = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING

        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            else -> "Unknown"
        }
        val plugName = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            0 -> "On battery"
            else -> "Plugged in"
        }
        val healthText = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }
        val tech = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() } ?: "—"
        val cycles = try {
            if (Build.VERSION.SDK_INT >= 34) i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) else -1
        } catch (_: Throwable) {
            -1
        }
        // BATTERY_PROPERTY_STATE_OF_HEALTH (API 34) has the property id 10. Unsupported devices
        // return Integer.MIN_VALUE, which the range check below turns into "unknown".
        val soh = try {
            if (Build.VERSION.SDK_INT >= 34) bm.getIntProperty(10).takeIf { it in 1..100 } ?: -1 else -1
        } catch (_: Throwable) {
            -1
        }

        // CURRENT_NOW is microamps on Pixels; a few devices report milliamps.
        val rawProp = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (rawProp == Long.MIN_VALUE) return null
        val magnitudeMa = if (abs(rawProp) >= 20_000L) abs(rawProp) / 1000.0 else abs(rawProp).toDouble()

        // Sign differs between vendors, so derive it from the charging state instead.
        val positive = charging || status == BatteryManager.BATTERY_STATUS_FULL
        val signed = if (positive) magnitudeMa else -magnitudeMa

        val prev = ema
        val smoothed = if (prev == null) signed else prev + 0.3 * (signed - prev)
        ema = smoothed

        val counterUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val counterMah = if (counterUah > 0) counterUah / 1000f else 0f
        val fullMah = if (percent > 1f && counterMah > 0f) counterMah / (percent / 100f) else 0f

        val absMa = abs(smoothed).toFloat()
        val pph = if (fullMah > 0f) absMa / fullMah * 100f else Float.NaN
        val eta = when {
            fullMah <= 0f || absMa < 5f -> Float.NaN
            smoothed > 0 -> (fullMah - counterMah) / absMa
            else -> counterMah / absMa
        }

        return Reading(
            timeMs = System.currentTimeMillis(),
            mA = smoothed.roundToInt(),
            rawMa = signed.roundToInt(),
            percent = percent,
            mV = mV,
            tempC = tempC,
            charging = charging,
            plugged = plugged,
            fullMah = fullMah,
            pctPerHour = pph,
            etaHours = eta,
            statusText = statusText,
            plugName = plugName,
            healthText = healthText,
            tech = tech,
            cycles = cycles,
            soh = soh,
        )
    }
}

object Fmt {
    fun duration(hours: Float): String {
        if (hours.isNaN() || hours.isInfinite() || hours > 99f || hours < 0f) return "—"
        val m = (hours * 60).roundToInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    fun signedMa(mA: Int): String = if (mA > 0) "+$mA" else "$mA"
}
