package com.irfan.rabta

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The NOVA brand hero: the R mark on a navy disc inside quiet orbital rings.
 * Purely decorative and static; nothing here encodes people, distance or signal strength.
 */
class OrbitMarkView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = c.getDrawable(R.drawable.ic_launcher_fg)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - 4 * d
        paint.style = Paint.Style.STROKE; paint.strokeWidth = d
        paint.color = AppTheme.line(context)
        for (scale in listOf(0.56f, 0.78f, 1f)) canvas.drawCircle(cx, cy, r * scale, paint)
        // One restrained lime orbit arc and two support dots.
        paint.color = AppTheme.LIME; paint.alpha = 150; paint.strokeWidth = 2 * d; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 200f, 46f, false, paint)
        paint.alpha = 255; paint.style = Paint.Style.FILL
        fun dot(radius: Float, angle: Double, color: Int, size: Float) {
            paint.color = color
            canvas.drawCircle(cx + radius * Math.cos(Math.toRadians(angle)).toFloat(), cy + radius * Math.sin(Math.toRadians(angle)).toFloat(), size * d, paint)
        }
        dot(r * 0.78f, -38.0, 0xFFA997FF.toInt(), 3.5f)
        dot(r, 128.0, 0xFF54DDC5.toInt(), 3f)
        paint.color = 0xFF080D14.toInt()
        canvas.drawCircle(cx, cy, r * 0.42f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = d; paint.color = 0x55BFFF76
        canvas.drawCircle(cx, cy, r * 0.42f, paint)
        val half = (r * 0.5f).toInt()
        mark?.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half); mark?.draw(canvas)
    }
}
