package com.elite.chargerate

import android.content.Context
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

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
            val prevPercent = lastPercent
            val crossed = prevPercent >= 0 && percent != prevPercent
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

    // ------------------------------------------------------------------ fractional percent

    private var exLevel = -1
    private var exCharging = true
    private var exBase = 0f
    private var exAnchorCounter = 0f
    private var exAnchorTick = false
    private var exIntegralMah = 0.0
    private var exLastMs = 0L
    private var exLastCounter = 0f
    private var exLastCounterChangeMs = 0L
    private var exLast = -1f

    private fun exAnchor(level: Int, base: Float, counter: Float, tick: Boolean) {
        exLevel = level; exBase = base; exAnchorCounter = counter; exAnchorTick = tick
        exIntegralMah = 0.0; exLast = -1f
    }

    /**
     * Percent with decimals. Anchored to the moment Android's whole percent ticks over (that
     * moment is exact), then advanced by how much charge has flowed since, using the battery's
     * charge counter, or the integrated current when the counter is stale. mAh per percent is
     * learned from consecutive ticks. Never clamped to a stale capacity estimate, so it keeps
     * moving, and the whole part always equals the percent the phone shows.
     */
    fun exactPercent(ctx: Context, counterMah: Float, fullMah: Float, level: Int,
                     charging: Boolean, avgMa: Double, nowMs: Long): Float? {
        if (level !in 0..100) return null
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val capSlope = if (fullMah > 0f) fullMah / 100f else 0f
        var slope = p.getFloat("mahPerPct", 0f)
        if (slope <= 0f) slope = capSlope
        if (slope <= 0f) return null
        val haveCounter = counterMah > 0f

        val dt = if (exLastMs == 0L) 0.0 else ((nowMs - exLastMs) / 1000.0).coerceIn(0.0, 30.0)
        exLastMs = nowMs
        exIntegralMah += avgMa * dt / 3600.0
        if (haveCounter && counterMah != exLastCounter) {
            exLastCounter = counterMah; exLastCounterChangeMs = nowMs
        }

        if (exLevel < 0 || charging != exCharging) {
            val raw = if (haveCounter && capSlope > 0f) counterMah / capSlope - level else 0.5f
            exAnchor(level, if (raw in 0.05f..0.95f) raw else 0.5f, counterMah, false)
        } else if (level != exLevel) {
            if (haveCounter && exAnchorTick && abs(level - exLevel) == 1 && capSlope > 0f) {
                val d = abs(counterMah - exAnchorCounter)
                if (d in capSlope * 0.5f..capSlope * 1.6f) {
                    val old = p.getFloat("mahPerPct", 0f)
                    val ns = if (old > 0f) old * 0.7f + d * 0.3f else d
                    p.edit().putFloat("mahPerPct", ns).apply()
                    slope = ns
                }
            }
            exAnchor(level, if (charging) 0f else 0.99f, counterMah, true)
        }
        exCharging = charging

        val fresh = haveCounter && nowMs - exLastCounterChangeMs < 30_000
        val deltaMah = if (fresh) (counterMah - exAnchorCounter).toDouble() else exIntegralMah
        var frac = (exBase + (deltaMah / slope).toFloat()).coerceIn(0f, 0.99f)
        if (exLast >= 0f) frac = if (charging) maxOf(frac, exLast) else minOf(frac, exLast)
        exLast = frac
        return (level + frac).coerceIn(0f, 100f)
    }

    // ------------------------------------------------------------ current self-calibration

    private var calStartMs = 0L
    private var calLastMs = 0L
    private var calCounter = 0f
    private var calIntegral = 0.0
    private var calPositive = true

    fun scale(ctx: Context): Double =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("scale", 1f).toDouble()

    /**
     * Compares the charge the current reading says flowed (integrated over a few minutes) with
     * what the battery's own charge counter says changed, and nudges a correction factor.
     * [signedMa] is the unscaled current.
     */
    fun calibrate(ctx: Context, signedMa: Double, counterMah: Float, nowMs: Long, positive: Boolean) {
        if (counterMah <= 0f) {
            calStartMs = 0L
            return
        }
        val gap = if (calLastMs == 0L) 0.0 else (nowMs - calLastMs) / 1000.0
        calLastMs = nowMs
        if (calStartMs == 0L || gap > 15.0 || calPositive != positive) {
            calStartMs = nowMs; calCounter = counterMah; calIntegral = 0.0; calPositive = positive
            return
        }
        calIntegral += signedMa * gap / 3600.0
        if ((nowMs - calStartMs) / 1000.0 >= 240.0) {
            val dc = (counterMah - calCounter).toDouble()
            if (abs(dc) >= 4.0 && abs(calIntegral) >= 4.0 && dc * calIntegral > 0.0) {
                val ratio = dc / calIntegral
                if (ratio in 0.7..1.4) {
                    val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val old = p.getFloat("scale", 1f)
                    p.edit().putFloat("scale", (old * 0.7 + ratio * 0.3).coerceIn(0.8, 1.25).toFloat()).apply()
                }
            }
            calStartMs = nowMs; calCounter = counterMah; calIntegral = 0.0
        }
    }

    // ------------------------------------------------------------------- charging taper

    private var cPct = -1
    private var cSumMa = 0.0
    private var cSumS = 0.0
    private var cValid = false
    private var cLastMs = 0L
    private var refCurrent: Double? = null
    private var ratios: DoubleArray? = null

    private fun loadRatios(ctx: Context): DoubleArray {
        ratios?.let { return it }
        val arr = DoubleArray(100)
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ratios", null)
        saved?.split(",")?.forEachIndexed { i, v -> if (i < 100) arr[i] = v.toDoubleOrNull() ?: 0.0 }
        ratios = arr
        return arr
    }

    /** How much slower charging gets near full, as a multiplier on the time for one percent. */
    private fun defaultMultiplier(p: Int): Double = when {
        p < 80 -> 1.0
        p < 90 -> 1.0 + (p - 80) * 0.2
        else -> 2.8 + (p - 90) * 0.45
    }

    private fun ratioAt(ctx: Context, p: Int): Double {
        val learned = loadRatios(ctx)[p.coerceIn(0, 99)]
        return if (learned > 0.0) learned else 1.0 / defaultMultiplier(p)
    }

    /**
     * Watches each full percent step while charging. Steps between 30% and 70% give a reference
     * current; steps above 75% are compared to it to learn how this charger tapers off.
     */
    fun trackCharge(ctx: Context, percent: Int, fastMa: Double, charging: Boolean, nowMs: Long) {
        if (!charging) {
            cPct = -1; cValid = false; refCurrent = null; cLastMs = 0L
            return
        }
        val dt = if (cLastMs == 0L) 0.0 else ((nowMs - cLastMs) / 1000.0).coerceIn(0.0, 30.0)
        cLastMs = nowMs
        if (cPct < 0) {
            cPct = percent; cValid = false; cSumMa = 0.0; cSumS = 0.0
            return
        }
        if (percent == cPct) {
            cSumMa += abs(fastMa) * dt
            cSumS += dt
            return
        }
        if (percent == cPct + 1 && cValid && cSumS >= 20.0) {
            val avg = cSumMa / cSumS
            val step = cPct
            val ref = refCurrent
            if (step in 30..70) {
                refCurrent = if (ref == null) avg else ref * 0.5 + avg * 0.5
            } else if (step >= 75 && ref != null && ref > 50.0) {
                val r = (avg / ref).coerceIn(0.05, 1.2)
                val arr = loadRatios(ctx)
                arr[step] = if (arr[step] > 0.0) arr[step] * 0.5 + r * 0.5 else r
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("ratios", arr.joinToString(",") { "%.4f".format(java.util.Locale.US, it) })
                    .apply()
            }
        }
        cValid = abs(percent - cPct) == 1
        cPct = percent
        cSumMa = 0.0
        cSumS = 0.0
    }

    /** Hours to 100%, stepping percent by percent with the expected taper. NaN if unknown. */
    fun chargeEtaHours(ctx: Context, exactPercent: Float, fullMah: Float, avgMa: Double): Float {
        if (fullMah <= 0f || avgMa < 20.0) return Float.NaN
        val start = floor(exactPercent).toInt().coerceIn(0, 99)
        val now = ratioAt(ctx, start)
        var hours = 0.0
        for (p in start..99) {
            val frac = if (p == start) (start + 1 - exactPercent).coerceIn(0f, 1f).toDouble() else 1.0
            val current = avgMa * ratioAt(ctx, p) / now
            hours += frac * (fullMah / 100.0) / current
        }
        return hours.toFloat()
    }
}
