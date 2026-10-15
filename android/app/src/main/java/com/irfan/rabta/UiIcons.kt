package com.irfan.rabta

import android.graphics.*
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.TextView
import androidx.core.graphics.PathParser

/** Bundled vector paths: the same controls on every Android version. */
object UiIcons {
    private val paths = mapOf(
        "phone" to "M6.6,10.8c1.4,2.8 3.7,5.1 6.5,6.5l2.2,-2.2c.3,-.3 .7,-.4 1.1,-.2 1.2,.4 2.5,.6 3.8,.6 .5,0 .8,.4 .8,.9V20c0,.6 -.4,1 -1,1C10.6,21 3,13.4 3,4c0,-.6 .4,-1 1,-1h3.6c.5,0 .9,.4 .9,.9 0,1.3 .2,2.6 .6,3.8 .1,.4 0,.8 -.2,1.1z",
        "video" to "M17,10.5V6c0,-.6 -.4,-1 -1,-1H4c-.6,0 -1,.4 -1,1v12c0,.6 .4,1 1,1h12c.6,0 1,-.4 1,-1v-4.5l4,4v-11z",
        "mic" to "M12,14c1.7,0 3,-1.3 3,-3V5c0,-1.7 -1.3,-3 -3,-3S9,3.3 9,5v6c0,1.7 1.3,3 3,3z M17,11c0,2.8 -2.2,5 -5,5s-5,-2.2 -5,-5H5c0,3.5 2.6,6.4 6,6.9V21h2v-3.1c3.4,-.5 6,-3.4 6,-6.9z",
        "muted" to "M3,2L2,3l7,7v1c0,1.7 1.3,3 3,3h1l2,2c-.9,.5 -1.9,.8 -3,.8 -2.8,0 -5,-2.2 -5,-5H5c0,3.5 2.6,6.4 6,6.9V22h2v-3.3c1.3,-.2 2.4,-.7 3.4,-1.4L21,22l1,-1z M15,11V5c0,-1.7 -1.3,-3 -3,-3S9,3.3 9,5z M17,11h2c0,1.2 -.3,2.3 -.8,3.3l-1.5,-1.5z",
        "speaker" to "M3,9v6h4l5,4V5L7,9z M14,8v8c1.5,-.8 2.5,-2.3 2.5,-4S15.5,8.8 14,8z M14,3v2c3.4,.9 6,3.6 6,7s-2.6,6.1 -6,7v2c4.6,-1 8,-4.6 8,-9s-3.4,-8 -8,-9z",
        "end" to "M12,6C7.5,6 3.3,7.8 0,11l3,3c.3,.3 .8,.4 1.2,.2l3.4,-1.5c.4,-.2 .6,-.5 .6,-.9V9.7c2.5,-.9 5.1,-.9 7.6,0v2.1c0,.4 .2,.7 .6,.9l3.4,1.5c.4,.2 .9,.1 1.2,-.2l3,-3C20.7,7.8 16.5,6 12,6z",
        "send" to "M2,21l21,-9L2,3v7l15,2 -15,2z",
        "back" to "M20,11H7.8l5.6,-5.6L12,4l-8,8 8,8 1.4,-1.4L7.8,13H20z",
        "compose" to "M3,17.3V21h3.7L18,9.7 14.3,6z M20.7,7c.4,-.4 .4,-1 0,-1.4l-2.3,-2.3c-.4,-.4 -1,-.4 -1.4,0l-1.8,1.8L19,8.8z",
        "attach" to "M16.5,6v11.5a4.5,4.5 0,0 1,-9,0V5a3,3 0,0 1,6,0v10.5a1.5,1.5 0,0 1,-3,0V6H9v9.5a3,3 0,0 0,6,0V5a4.5,4.5 0,0 0,-9,0v12.5a6,6 0,0 0,12,0V6z",
        "flip" to "M7,7h10v3l4,-4 -4,-4v3H5v6h2z M17,17H7v-3l-4,4 4,4v-3h12v-6h-2z",
        "play" to "M8,5v14l11,-7z",
        "pause" to "M6,5h4v14H6z M14,5h4v14h-4z"
    )
    fun set(view: TextView, name: String, color: Int = Color.WHITE, size: Int = 24, label: String = name) {
        val path = PathParser.createPathFromPathData(paths.getValue(name))!!
        val drawable = object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            override fun draw(canvas: Canvas) { canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width()/24f, bounds.height()/24f); canvas.drawPath(path, paint); canvas.restore() }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
            @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        val px = (size * view.resources.displayMetrics.density).toInt()
        drawable.setBounds(0, 0, px, px)
        view.text = SpannableString(" ").apply { setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BASELINE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        view.contentDescription = label
    }
    fun fromSymbol(symbol: String) = when(symbol) {
        "📞" -> "phone"; "🎥", "📹" -> "video"; "🎤" -> "mic"; "🔇" -> "muted"
        "🔊", "🔈" -> "speaker"; "📵" -> "end"; "📎" -> "attach"; "←" -> "back"
        "✎" -> "compose"; "➤" -> "send"; "🔄" -> "flip"; "▶" -> "play"; "⏸" -> "pause"
        else -> null
    }
}
