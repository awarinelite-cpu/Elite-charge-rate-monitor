package com.elite.chargerate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Speedometer-style gauge for the live current in mA.
 * Range is -3000 .. +3000 mA. Left side (red) is discharging, right side (teal) is charging.
 */
class GaugeView(ctx: Context) : View(ctx) {

    private val d = resources.displayMetrics.density
    private var value = 0f

    private val maxMa = 3000f
    private val halfSweep = 97f // degrees either side of straight up

    private val stops = floatArrayOf(0f, 0.22f, 0.47f, 0.72f, 1f)
    private val stopColors = intArrayOf(
        Color.parseColor("#F43F3F"),
        Color.parseColor("#F97316"),
        Color.parseColor("#D4E21F"),
        Color.parseColor("#4ADE50"),
        Color.parseColor("#14B8A6"),
    )

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DCE6FF")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 11f * d
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * d
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.6f * d
    }
    private val pivotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B1B58")
        style = Paint.Style.FILL
    }
    private val pivotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * d
    }
    private val pivotDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.FILL
    }

    private val oval = RectF()
    private val chevron = Path()

    fun setValue(mA: Int) {
        value = mA.toFloat()
        invalidate()
    }

    private fun radius(w: Float) = w * 0.31f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val r = radius(w.toFloat())
        setMeasuredDimension(w, (r * 1.13f + 30f * d).toInt())
    }

    private fun colorAt(f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        for (i in 1 until stops.size) {
            if (t <= stops[i]) {
                val local = (t - stops[i - 1]) / (stops[i] - stops[i - 1])
                return lerp(stopColors[i - 1], stopColors[i], local)
            }
        }
        return stopColors.last()
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val r = Color.red(a) + (Color.red(b) - Color.red(a)) * t
        val g = Color.green(a) + (Color.green(b) - Color.green(a)) * t
        val bl = Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t
        return Color.rgb(r.toInt(), g.toInt(), bl.toInt())
    }

    /** Degrees from straight up (negative = left) for a mA value. */
    private fun angleFor(mA: Float) = mA.coerceIn(-maxMa, maxMa) / maxMa * halfSweep

    private fun px(cx: Float, r: Float, deg: Float) = cx + r * sin(Math.toRadians(deg.toDouble())).toFloat()
    private fun py(cy: Float, r: Float, deg: Float) = cy - r * cos(Math.toRadians(deg.toDouble())).toFloat()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val r = radius(w)
        val stroke = 14f * d
        val cx = w / 2f
        val cy = r + 17f * d

        // Gradient arc, drawn in 1 degree slices.
        arcPaint.strokeWidth = stroke
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val startCanvasAngle = 270f - halfSweep
        val total = halfSweep * 2f
        var a = 0f
        while (a < total) {
            arcPaint.color = colorAt((a + 0.5f) / total)
            c.drawArc(oval, startCanvasAngle + a, minOf(1.6f, total - a), false, arcPaint)
            a += 1f
        }
        // Rounded ends.
        capPaint.color = stopColors.first()
        c.drawCircle(px(cx, r, -halfSweep), py(cy, r, -halfSweep), stroke / 2f, capPaint)
        capPaint.color = stopColors.last()
        c.drawCircle(px(cx, r, halfSweep), py(cy, r, halfSweep), stroke / 2f, capPaint)

        // Ticks and labels.
        val ticks = floatArrayOf(-3000f, -1500f, 0f, 1500f, 3000f)
        val names = arrayOf("-3k", "-1.5k", "", "1.5k", "3k")
        val inner = r - stroke / 2f - 3f * d
        for (i in ticks.indices) {
            val ang = angleFor(ticks[i])
            tickPaint.color = colorAt((ang + halfSweep) / total)
            tickPaint.strokeWidth = 2f * d
            c.drawLine(px(cx, inner, ang), py(cy, inner, ang), px(cx, inner - 6f * d, ang), py(cy, inner - 6f * d, ang), tickPaint)
            if (names[i].isNotEmpty()) {
                val lr = inner - 20f * d
                val ty = py(cy, lr, ang) - (labelPaint.ascent() + labelPaint.descent()) / 2f
                c.drawText(names[i], px(cx, lr, ang), ty, labelPaint)
            }
        }

        // Needle and marker.
        val ang = angleFor(value)
        c.drawLine(cx, cy, px(cx, r - 4f * d, ang), py(cy, r - 4f * d, ang), needlePaint)

        val mx = px(cx, r, ang)
        val my = py(cy, r, ang)
        markerPaint.color = if (value > 0f) Color.parseColor("#22C55E") else Color.parseColor("#EF4444")
        c.save()
        c.translate(mx, my)
        c.rotate(ang)
        val half = 12f * d
        c.drawRoundRect(-half, -half, half, half, 6f * d, 6f * d, markerPaint)
        chevron.reset()
        val dir = if (value > 0f) 1f else -1f
        chevron.moveTo(-dir * 2.5f * d, -5f * d)
        chevron.lineTo(dir * 2.5f * d, 0f)
        chevron.lineTo(-dir * 2.5f * d, 5f * d)
        c.drawPath(chevron, chevronPaint)
        c.restore()

        // Pivot.
        c.drawCircle(cx, cy, 9f * d, pivotFill)
        c.drawCircle(cx, cy, 9f * d, pivotRing)
        c.drawCircle(cx, cy, 3f * d, pivotDot)
    }
}
