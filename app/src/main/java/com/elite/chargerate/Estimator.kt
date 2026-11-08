package com.elite.chargerate

import android.content.Context
import kotlin.math.abs
import kotlin.math.exp

/**
 * Process-wide signal processing for the battery readings. The activity and the background
 * service both feed it, so the averages stay warm no matter which one is reading.
 */
object Estimator {
    private const val PREFS = "estimator"
    private const val FAST_TAU_S = 2.5   // gauge: quick, but ignores single-sample spikes
    private const val SLOW_TAU_S = 90.0  // "% per hour" and "lasts until": steady
    private const val MIN_STEP_PERCENT = 4

    private val window = ArrayDeque<Double>()
    private var fast: Double? = null
    private var slow: Double? = null
    private var lastTimeMs = 0L
    private var lastPositive: Boolean? = null

    private var lastPercent = -1
    private var anchorCounter = 0f
    private var anchorPercent = -1

    class Smoothed(val fast: Double, val slow: Double)

    /**
     * CURRENT_NOW should be microamps. Once any reading is large enough to be unambiguous the
     * unit is remembered, so a later low reading (say 15 mA = 15000 uA) is not misread as mA.
     */
    fun toMilliamps(ctx: Context, raw: Long): Double {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var micro = p.getBoolean("micro", false)
        if (!micro && abs(raw) >= 20_000L) {
            micro = true
            p.edit().putBoolean("micro", true).apply()
        }
        return if (micro) abs(raw) / 1000.0 else abs(raw).toDouble()
    }

    fun smooth(ctx: Context, signed: Double, positive: Boolean, nowMs: Long): Smoothed {
        if (lastPositive != null && lastPositive != positive) {
            // Charging state flipped: old averages describe a different situation.
            window.clear(); fast = null; slow = null
        }
        lastPositive = positive

        window.addLast(signed)
        while (window.size > 5) window.removeFirst()
        val median = window.sorted()[window.size / 2]

        val dt = if (lastTimeMs == 0L) 1.0 else ((nowMs - lastTimeMs) / 1000.0).coerceIn(0.1, 30.0)
        lastTimeMs = nowMs

        fast = fast?.let { it + (1 - exp(-dt / FAST_TAU_S)) * (median - it) } ?: median
        slow = slow?.let { it + (1 - exp(-dt / SLOW_TAU_S)) * (median - it) }
            ?: seedFromHistory(ctx, positive, nowMs) ?: median
        return Smoothed(fast!!, slow!!)
    }

    /** On a cold start, begin from the last couple of minutes the service already logged. */
    private fun seedFromHistory(ctx: Context, positive: Boolean, nowMs: Long): Double? = try {
        val recent = History.load(ctx, 18)
            .filter { nowMs - it.first in 0..180_000 && (it.second > 0) == positive }
            .map { it.second.toDouble() }
        if (recent.size >= 3) recent.average() else null
    } catch (_: Throwable) {
        null
    }

    /**
     * Battery capacity in mAh. The percent Android reports is a whole number, so dividing the
     * charge counter by it can be several percent off. Instead we watch the moments the percent
     * ticks over and compare the charge counter between those moments.
     */
    fun fullCapacity(ctx: Context, counterMah: Float, percent: Int): Float {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var learned = p.getFloat("fullMah", 0f)

        if (counterMah > 0f && percent in 0..100) {
            val crossed = lastPercent >= 0 && percent != lastPercent
            lastPercent = percent
            if (crossed) {
                if (anchorPercent < 0) {
                    anchorCounter = counterMah; anchorPercent = percent
                } else {
                    val dp = percent - anchorPercent
                    val dc = counterMah - anchorCounter
                    if (abs(dp) >= MIN_STEP_PERCENT && dp * dc > 0f) {
                        val est = abs(dc) / abs(dp) * 100f
                        if (est in 1500f..9000f) {
                            learned = if (learned <= 0f) est else learned * 0.6f + est * 0.4f
                            p.edit().putFloat("fullMah", learned).apply()
                        }
                        anchorCounter = counterMah; anchorPercent = percent
                    } else if (abs(dp) >= MIN_STEP_PERCENT) {
                        // Direction changed (unplugged, replugged): start over from here.
                        anchorCounter = counterMah; anchorPercent = percent
                    }
                }
            }
        }
        if (learned > 0f) return learned
        return if (percent > 1 && counterMah > 0f) counterMah / (percent / 100f) else 0f
    }
}
