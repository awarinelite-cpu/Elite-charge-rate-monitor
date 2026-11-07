package com.elite.chargerate

import android.content.Context
import java.io.File
import java.util.Locale

/** Rolling CSV log: epochMs,mA,percent,mV,tempC */
object History {
    private const val FILE = "history.csv"
    private const val MAX_BYTES = 1_200_000L
    private const val KEEP_LINES = 10_000

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun append(ctx: Context, r: Reading) {
        val f = file(ctx)
        f.appendText(
            String.format(Locale.US, "%d,%d,%.1f,%d,%.1f\n", r.timeMs, r.mA, r.percent, r.mV, r.tempC)
        )
        if (f.length() > MAX_BYTES) {
            val lines = f.readLines()
            f.writeText(lines.takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
        }
    }

    /** Most recent [max] samples as (timeMs, mA). */
    @Synchronized
    fun load(ctx: Context, max: Int = 360): List<Pair<Long, Int>> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().takeLast(max).mapNotNull { line ->
            val p = line.split(",")
            val t = p.getOrNull(0)?.toLongOrNull()
            val ma = p.getOrNull(1)?.toIntOrNull()
            if (t != null && ma != null) t to ma else null
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}

/** Lifetime peaks, persisted. */
object Lifetime {
    private const val PREFS = "lifetime"

    fun update(ctx: Context, mA: Int) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val maxCharge = p.getInt("maxCharge", 0)
        val maxDischarge = p.getInt("maxDischarge", 0) // most negative
        val e = p.edit()
        if (mA > maxCharge) e.putInt("maxCharge", mA)
        if (mA < maxDischarge) e.putInt("maxDischarge", mA)
        e.apply()
    }

    fun maxCharge(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("maxCharge", 0)
    fun maxDischarge(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("maxDischarge", 0)

    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
