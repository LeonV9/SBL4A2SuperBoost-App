package de.sbl4a2.superboost

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Spektrum-Anzeige mit weich auslaufenden Balken und Peak-Haltemarken (Gaming-Optik). */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bars = 24
    private val target = FloatArray(bars)
    private val shown = FloatArray(bars)
    private val peaks = FloatArray(bars)
    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val rect = RectF()

    fun submit(levels: FloatArray) {
        val n = min(levels.size, bars)
        for (i in 0 until n) target[i] = levels[i].coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    fun clear() {
        target.fill(0f)
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barPaint.shader = LinearGradient(
            0f, h.toFloat(), 0f, 0f,
            intArrayOf(0xFF5A0010.toInt(), 0xFFFF1744.toInt(), 0xFFFF8A80.toInt()),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 4f * density
        val barW = (w - gap * (bars - 1)) / bars
        val minH = 3f * density
        val radius = 3f * density
        var active = false
        for (i in 0 until bars) {
            val t = target[i]
            shown[i] += (t - shown[i]) * (if (t > shown[i]) 0.5f else 0.15f)
            if (abs(t - shown[i]) > 0.002f) active = true
            peaks[i] = if (shown[i] > peaks[i]) shown[i] else max(0f, peaks[i] - 0.008f)
            if (peaks[i] > 0.002f) active = true

            val left = i * (barW + gap)
            val barH = max(shown[i] * h, minH)
            rect.set(left, h - barH, left + barW, h)
            canvas.drawRoundRect(rect, radius, radius, barPaint)

            if (peaks[i] > 0.02f) {
                val y = h - peaks[i] * h
                canvas.drawRect(left, max(0f, y - 2f * density), left + barW, max(2f * density, y), capPaint)
            }
        }
        if (active) postInvalidateOnAnimation()
    }
}
