package com.elite.chargerate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Simple line graph of mA over time. Green above zero (charging), red below (discharging). */
class GraphView(ctx: Context) : View(ctx) {
    private var data: List<Pair<Long, Int>> = emptyList()
    private val d = resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * d
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val zero = Paint().apply {
        color = Color.parseColor("#475569")
        strokeWidth = 1f * d
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 11f * d
    }

    fun setData(points: List<Pair<Long, Int>>) {
        data = points
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (data.size < 2) {
            label.textAlign = Paint.Align.CENTER
            c.drawText("Collecting data…", w / 2, h / 2, label)
            return
        }
        val lo = min(0, data.minOf { it.second }) - 50
        val hi = max(0, data.maxOf { it.second }) + 50
        val padL = 8f * d
        val padR = 8f * d
        val t0 = data.first().first
        val t1 = data.last().first
        val span = max(1L, t1 - t0).toFloat()

        fun x(t: Long) = padL + (t - t0) / span * (w - padL - padR)
        fun y(v: Int) = h - (v - lo).toFloat() / (hi - lo) * h

        c.drawLine(0f, y(0), w, y(0), zero)
        label.textAlign = Paint.Align.LEFT
        c.drawText("$hi mA", padL, 12f * d, label)
        c.drawText("$lo mA", padL, h - 4f * d, label)

        for (i in 1 until data.size) {
            val a = data[i - 1]
            val b = data[i]
            line.color = if (b.second >= 0) Color.parseColor("#22C55E") else Color.parseColor("#F87171")
            c.drawLine(x(a.first), y(a.second), x(b.first), y(b.second), line)
        }
    }
}
