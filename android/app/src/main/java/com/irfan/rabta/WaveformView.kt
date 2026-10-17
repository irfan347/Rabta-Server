package com.irfan.rabta

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Live recording level: one bar per microphone sample (MediaRecorder.maxAmplitude), newest on the right. */
class WaveformView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val levels = ArrayDeque<Float>()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun clear() { levels.clear(); invalidate() }
    fun add(amplitude: Int) {
        // maxAmplitude is 0..32767; a square root keeps quiet speech visible.
        levels.addLast(Math.sqrt((amplitude.coerceIn(0, 32767) / 32767.0)).toFloat())
        while (levels.size > 64) levels.removeFirst()
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val step = 5 * d; paint.strokeWidth = 3 * d; paint.color = AppTheme.text(context)
        val count = (width / step).toInt(); val cy = height / 2f
        val shown = levels.toList().takeLast(count)
        val start = width - shown.size * step
        shown.forEachIndexed { i, level ->
            val h = maxOf(2 * d, level * (height - 4 * d)) / 2
            val x = start + i * step + step / 2
            canvas.drawLine(x, cy - h, x, cy + h, paint)
        }
    }
}
