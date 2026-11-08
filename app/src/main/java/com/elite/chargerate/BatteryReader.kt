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
    val avgMa: Int = 0,     // signed, ~90 s average used for the estimates
    val percentExact: Float = 0f,   // percent with decimals when known, else the whole number
    val exactKnown: Boolean = false,
    val statusText: String = "Unknown",
    val plugName: String = "On battery",
    val healthText: String = "Unknown",
    val tech: String = "—",
    val cycles: Int = -1,   // -1 when the device does not report it
    val soh: Int = -1,      // state of health percent, -1 when not reported
) {
    val watts: Double get() = mA / 1000.0 * mV / 1000.0
    val avgWatts: Double get() = avgMa / 1000.0 * mV / 1000.0
}

class BatteryReader(private val ctx: Context) {
    private val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

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
        val unscaledMa = Estimator.toMilliamps(ctx, rawProp)

        // Sign differs between vendors, so derive it from the charging state instead.
        val positive = charging || status == BatteryManager.BATTERY_STATUS_FULL
        val now = System.currentTimeMillis()

        val counterUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val realCounterMah = if (counterUah > 0) counterUah / 1000f else 0f

        // Self-calibrate the current against the battery's charge counter, then apply the factor.
        Estimator.calibrate(ctx, if (positive) unscaledMa else -unscaledMa, realCounterMah, now, positive)
        val magnitudeMa = unscaledMa * Estimator.scale(ctx)
        val signed = if (positive) magnitudeMa else -magnitudeMa
        val sm = Estimator.smooth(ctx, signed, positive, now)
        Estimator.trackCharge(ctx, level, sm.fast, charging, now)

        val fullMah = Estimator.fullCapacity(ctx, realCounterMah, level)
        val counterMah = if (realCounterMah > 0f) realCounterMah else fullMah * percent / 100f
        val exact = Estimator.exactPercent(ctx, realCounterMah, fullMah, level)

        val absAvg = abs(sm.slow).toFloat()
        val pph = if (fullMah > 0f) absAvg / fullMah * 100f else Float.NaN
        val eta = when {
            fullMah <= 0f || absAvg < 5f -> Float.NaN
            charging -> Estimator.chargeEtaHours(ctx, exact ?: percent, fullMah, sm.slow)
            sm.slow > 0 -> Float.NaN
            else -> counterMah / absAvg
        }

        return Reading(
            timeMs = System.currentTimeMillis(),
            mA = sm.fast.roundToInt(),
            rawMa = signed.roundToInt(),
            percent = percent,
            mV = mV,
            tempC = tempC,
            charging = charging,
            plugged = plugged,
            fullMah = fullMah,
            pctPerHour = pph,
            etaHours = eta,
            avgMa = sm.slow.roundToInt(),
            percentExact = exact ?: percent,
            exactKnown = exact != null,
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
