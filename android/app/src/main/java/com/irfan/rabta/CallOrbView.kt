package com.irfan.rabta

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** The NOVA call orb: static lime orbit rings behind the start-call button. Decorative; it shows no call data. */
class CallOrbView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var shaderKey = ""
    private var glow: android.graphics.Shader? = null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - 6 * d
        if (r <= 0) return
        val lime = AppTheme.accent(context)
        fun alpha(a: Int) = (lime and 0x00FFFFFF) or (a shl 24)
        paint.style = Paint.Style.FILL
        val key = "$width:$height:$lime"
        if (key != shaderKey) { shaderKey = key; glow = android.graphics.RadialGradient(cx, cy, r, intArrayOf(alpha(0x30), alpha(0x0C), 0), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP) }
        paint.shader = glow
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null; paint.style = Paint.Style.STROKE
        // Gently warped rings give the orbital look without animation.
        for (i in 0 until 7) {
            val base = r * (0.42f + i * 0.085f); val wobble = r * 0.025f * (1 + i % 3)
            path.reset()
            for (step in 0..72) {
                val t = Math.toRadians(step * 5.0)
                val rr = base + wobble * Math.sin(t * 3 + i).toFloat()
                val x = cx + rr * Math.cos(t).toFloat(); val y = cy + rr * Math.sin(t).toFloat()
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            paint.strokeWidth = if (i < 2) 2 * d else d; paint.color = alpha(0xB0 - i * 0x16)
            canvas.drawPath(path, paint)
        }
    }
}
