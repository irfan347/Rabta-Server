package com.irfan.rabta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.PathParser

/**
 * A chat bubble with WhatsApp's small tail on the first message of a run. The tail side always keeps
 * [tail] px of room so bubbles in a run line up whether or not they draw it.
 */
class BubbleDrawable(private val color: Int, private val mine: Boolean, private val showTail: Boolean, private val radius: Float, val tail: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@BubbleDrawable.color }
    private val path = Path()
    private val body = RectF()
    override fun onBoundsChange(b: android.graphics.Rect) {
        super.onBoundsChange(b)
        body.set(b.left + (if (mine) 0f else tail), b.top.toFloat(), b.right - (if (mine) tail else 0f), b.bottom.toFloat())
        path.reset()
        val r = radius
        // Rounded body; the corner next to the tail is square so the tail grows out of it.
        val tl = if (!mine && showTail) 0f else r; val tr = if (mine && showTail) 0f else r
        path.addRoundRect(body, floatArrayOf(tl, tl, tr, tr, r, r, r, r), Path.Direction.CW)
        if (showTail) {
            if (mine) { path.moveTo(body.right - 1, body.top); path.lineTo(body.right + tail - r / 4, body.top); path.quadTo(body.right + tail, body.top, body.right + tail - r / 4, body.top + r / 4); path.lineTo(body.right - 1, body.top + tail * 1.6f) }
            else { path.moveTo(body.left + 1, body.top); path.lineTo(body.left - tail + r / 4, body.top); path.quadTo(body.left - tail, body.top, body.left - tail + r / 4, body.top + r / 4); path.lineTo(body.left + 1, body.top + tail * 1.6f) }
            path.close()
        }
    }
    override fun draw(canvas: Canvas) { canvas.drawPath(path, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getOutline(outline: android.graphics.Outline) { outline.setRoundRect(body.left.toInt(), body.top.toInt(), body.right.toInt(), body.bottom.toInt(), radius) }
}

/**
 * Message text with its time and ticks tucked into the bottom-right corner, WhatsApp-style: on the last line
 * when it fits there, otherwise on a line of its own.
 */
class InlineMetaLayout(c: Context, private val body: TextView, private val meta: View) : ViewGroup(c) {
    private val gap = RabtaUi.dp(c, 10)
    private var inline = false
    init { addView(body); addView(meta) }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val max = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthSpec)
        body.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        meta.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val layout = body.layout
        val rtl = layout != null && layout.lineCount > 0 && layout.getParagraphDirection(layout.lineCount - 1) == android.text.Layout.DIR_RIGHT_TO_LEFT
        val last = if (layout == null || layout.lineCount == 0) 0f else layout.getLineWidth(layout.lineCount - 1) + body.paddingLeft + body.paddingRight
        val together = last + gap + meta.measuredWidth
        inline = !rtl && together <= max
        val width = if (inline) maxOf(body.measuredWidth, together.toInt()) else maxOf(body.measuredWidth, meta.measuredWidth)
        val height = if (inline) maxOf(body.measuredHeight, meta.measuredHeight) else body.measuredHeight + meta.measuredHeight
        setMeasuredDimension(minOf(width, max), height)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        body.layout(0, 0, body.measuredWidth, body.measuredHeight)
        // On the last line the time sits a touch below the text baseline, as in WhatsApp.
        val top = if (inline) h - meta.measuredHeight + RabtaUi.dp(context, 3) else body.measuredHeight
        meta.layout(w - meta.measuredWidth, minOf(top, h - meta.measuredHeight), w, minOf(top, h - meta.measuredHeight) + meta.measuredHeight)
    }
}

/** A quiet doodle pattern behind the chat, drawn once into a tile from Rabta's own icons in the theme's colours. */
class ChatWallpaper(c: Context) : Drawable() {
    private val paint = Paint()
    private val base = AppTheme.bg(c)
    init {
        val d = c.resources.displayMetrics.density; val size = (220 * d).toInt()
        val tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; color = AppTheme.text(c); alpha = if (AppTheme.dark(c)) 14 else 18 }
        val icons = listOf("chats", "star", "mic", "photo", "send", "music", "phone", "nearby", "emoji", "sticker", "drop", "lock")
        val spots = listOf(0.12f to 0.14f, 0.55f to 0.08f, 0.85f to 0.3f, 0.32f to 0.42f, 0.7f to 0.6f, 0.1f to 0.7f, 0.45f to 0.85f, 0.88f to 0.88f, 0.6f to 0.32f, 0.25f to 0.95f, 0.95f to 0.62f, 0.38f to 0.18f)
        icons.forEachIndexed { i, name ->
            val path = runCatching { PathParser.createPathFromPathData(UiIcons.path(name)) }.getOrNull() ?: return@forEachIndexed
            val (x, y) = spots[i]
            canvas.save(); canvas.translate(x * size - 14 * d, y * size - 14 * d); canvas.rotate(((i * 37) % 50 - 25).toFloat(), 14 * d, 14 * d)
            canvas.scale(28 * d / 24f, 28 * d / 24f); canvas.drawPath(path, ink); canvas.restore()
        }
        paint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    override fun draw(canvas: Canvas) { canvas.drawColor(base); canvas.drawRect(bounds, paint) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}
