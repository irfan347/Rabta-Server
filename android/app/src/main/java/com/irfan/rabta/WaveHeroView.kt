package com.irfan.rabta

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Decorative NOVA profile backdrop: flowing lime signal lines on navy. It carries no data. */
class WaveHeroView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val lime = AppTheme.accent(context)
        paint.strokeWidth = d
        // Two bundles of sine lines sweep in from the sides behind the avatar.
        for (side in 0..1) for (i in 0 until 14) {
            path.reset()
            val phase = i * 0.22f; val amp = h * (0.10f + i * 0.012f); val base = h * (0.30f + i * 0.022f)
            var x = 0f
            while (x <= w) {
                val t = x / w * Math.PI.toFloat() * 2f
                val y = base + amp * Math.sin((t + phase + side * 2.4f).toDouble()).toFloat()
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 6 * d
            }
            paint.color = (lime and 0x00FFFFFF) or ((0x14 + i * 0x05) shl 24)
            canvas.drawPath(path, paint)
        }
    }
}
