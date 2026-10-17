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
        "zoom_in" to "M10,2a8,8 0,1 0,5,14l6,6 1,-1 -6,-6A8,8 0,0 0,10,2z M10,4a6,6 0,1 1,0,12 6,6 0,0 1,0,-12z M9,6h2v3h3v2h-3v3H9v-3H6V9h3z",
        "zoom_out" to "M10,2a8,8 0,1 0,5,14l6,6 1,-1 -6,-6A8,8 0,0 0,10,2z M10,4a6,6 0,1 1,0,12 6,6 0,0 1,0,-12z M6,9h8v2H6z",
        "forward" to "M4,11h12.2l-5.6,-5.6L12,4l8,8 -8,8 -1.4,-1.4L16.2,13H4z",
        "profile" to "M12,12a4,4 0,1 0,0,-8 4,4 0,0 0,0,8z M4,21a8,6 0,0 1,16,0z",
        "backup" to "M12,3v11l4,-4 1.4,1.4L12,17l-5.4,-5.6L8,10l4,4V3z M4,18h16v3H4z",
        "restore" to "M12,4a8,8 0,1 0,7.7,10h-2.1A6,6 0,1 1,12,6v3l5,-4 -5,-4z M13,8v5l4,2 1,-1.7 -3,-1.5V8z",
        "privacy" to "M12,2L4,5v6c0,5.2 3.4,9.8 8,11 4.6,-1.2 8,-5.8 8,-11V5z M10,15l-3,-3 1.4,-1.4L10,12.2l5.6,-5.6L17,8z",
        "notifications" to "M12,22a2.2,2.2 0,0 0,2,-2h-4a2.2,2.2 0,0 0,2,2z M18,16v-5c0,-3.1 -1.6,-5.7 -4.5,-6.4V4a1.5,1.5 0,0 0,-3,0v.6C7.6,5.3 6,7.9 6,11v5l-2,2v1h16v-1z",
        "connection" to "M12,2a10,10 0,1 0,0,20 10,10 0,0 0,0,-20z M11,10h2v7h-2z M11,6h2v2h-2z",
        "info" to "M12,2a10,10 0,1 0,0,20 10,10 0,0 0,0,-20z M11,10h2v7h-2z M11,6h2v2h-2z",
        "sound" to "M3,9v6h4l5,4V5L7,9z M15,8v8a4,4 0,0 0,0,-8z M15,3v2a7,7 0,0 1,0,14v2a9,9 0,0 0,0,-18z",
        "language" to "M12,2a10,10 0,1 0,0,20 10,10 0,0 0,0,-20z M4,11h16v2H4z M11,3h2v18h-2z",
        "text" to "M4,4h16v2H4z M4,11h10v2H4z M4,18h16v2H4z",
        "account" to "M12,2a10,10 0,1 0,0,20 10,10 0,0 0,0,-20z M12,6a3,3 0,1 1,0,6 3,3 0,0 1,0,-6z M6.5,18c.6,-2.4 2.6,-4 5.5,-4s4.9,1.6 5.5,4z",
        "appearance" to "M12,2a10,10 0,1 0,0,20 10,10 0,0 0,0,-20z M12,4v16a8,8 0,0 1,0,-16z",
        "close" to "M5,4l7,7 7,-7 1,1 -7,7 7,7 -1,1 -7,-7 -7,7 -1,-1 7,-7 -7,-7z",
        "chats" to "M4,4h16v12H8l-4,4z M7,7v2h10V7z M7,11v2h7v-2z",
        "nearby" to "M11,10h2v4h-2z M8,8l1.4,1.4a4,4 0,0 0,0,5.2L8,16a6,6 0,0 1,0,-8z M16,8a6,6 0,0 1,0,8l-1.4,-1.4a4,4 0,0 0,0,-5.2z M5,5l1.4,1.4a8,8 0,0 0,0,11.2L5,19a10,10 0,0 1,0,-14z M19,5a10,10 0,0 1,0,14l-1.4,-1.4a8,8 0,0 0,0,-11.2z",
        "spaces" to "M3,3h8v8H3z M13,3h8v8h-8z M3,13h8v8H3z M13,13h8v8h-8z",
        "status" to "M12,2a10,10 0,1 0,10,10h-2a8,8 0,1 1,-8,-8z M18,2h2v3h3v2h-3v3h-2V7h-3V5h3z",
        "more" to "M12,3a2,2 0,1 1,0,4 2,2 0,1 1,0,-4z M12,10a2,2 0,1 1,0,4 2,2 0,1 1,0,-4z M12,17a2,2 0,1 1,0,4 2,2 0,1 1,0,-4z",
        "settings" to "M4,4h16v2H4z M4,11h16v2H4z M4,18h16v2H4z M8,2h2v6H8z M15,9h2v6h-2z M8,16h2v6H8z",
        "search" to "M10,2a8,8 0,1 0,5,14l6,6 1,-1 -6,-6A8,8 0,0 0,10,2z M10,4a6,6 0,1 1,0,12 6,6 0,0 1,0,-12z",
        "sticker" to "M4,3h16v12l-6,6H4z M14,14v5l5,-5z M7,7h2v2H7z M15,7h2v2h-2z M8,11h8v2H8z",
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
        "pause" to "M6,5h4v14H6z M14,5h4v14h-4z",
        "eye" to "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
        "eye_off" to "M12,7c2.76,0 5,2.24 5,5 0,.65 -.13,1.26 -.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,.25 -3.98,.7l2.16,2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28,2.28 .46,.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-.3 4.38,-.84l.42,.42L19.73,22 21,20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-.05,.21 -.08,.43 -.08,.65 0,1.66 1.34,3 3,3 .22,0 .44,-.03 .65,-.08l1.55,1.55c-.67,.33 -1.41,.53 -2.2,.53 -2.76,0 -5,-2.24 -5,-5 0,-.79 .2,-1.53 .53,-2.2zM11.84,9.02l3.15,3.15 .02,-.16c0,-1.66 -1.34,-3 -3,-3l-.17,.01z",
        "qr" to "M3,11h8V3H3V11zM5,5h4v4H5V5zM3,21h8v-8H3V21zM5,15h4v4H5V15zM13,3v8h8V3H13zM19,9h-4V5h4V9zM19,19h2v2h-2zM13,13h2v2h-2zM15,15h2v2h-2zM13,17h2v2h-2zM15,19h2v2h-2zM17,17h2v2h-2zM17,13h2v2h-2zM19,15h2v2h-2z",
        "plus" to "M11,5h2v6h6v2h-6v6h-2v-6H5v-2h6z",
        "drop" to "M12,3l-6,6 1.4,1.4L11,6.8V16h2V6.8l3.6,3.6L18,9z M5,18h14v2H5z",
        "vision" to "M12,10a2,2 0,1 1,0,4 2,2 0,1 1,0,-4z M5,4a2,2 0,1 1,0,4 2,2 0,1 1,0,-4z M19,4a2,2 0,1 1,0,4 2,2 0,1 1,0,-4z M5,16a2,2 0,1 1,0,4 2,2 0,1 1,0,-4z M19,16a2,2 0,1 1,0,4 2,2 0,1 1,0,-4z M6.2,6.8l5,4.4 -0.9,1 -5,-4.4z M17.8,6.8l0.9,1 -5,4.4 -0.9,-1z M11.2,12.8l0.9,1 -5,4.4 -0.9,-1z M12.8,12.8l5,4.4 -0.9,1 -5,-4.4z",
        "nfc" to "M20,2H4c-1.1,0 -2,.9 -2,2v16c0,1.1 .9,2 2,2h16c1.1,0 2,-.9 2,-2V4c0,-1.1 -.9,-2 -2,-2zm0,18H4V4h16v16zM18,6h-5c-1.1,0 -2,.9 -2,2v2.28c-.6,.35 -1,.98 -1,1.72 0,1.1 .9,2 2,2s2,-.9 2,-2c0,-.74 -.4,-1.38 -1,-1.72V8h3v8H8V8h2V6H6v12h12V6z",
        "walkie" to "M8,2h2v4h6c1.1,0 2,.9 2,2v12c0,1.1 -.9,2 -2,2H8c-1.1,0 -2,-.9 -2,-2V8c0,-1.1 .9,-2 2,-2z M9,10v3h6v-3z M9,15v2h2v-2z M13,15v2h2v-2z",
        "storage" to "M4,4h16v4H4z M4,10h16v4H4z M4,16h16v4H4z M6,5.5h2v1H6z M6,11.5h2v1H6z M6,17.5h2v1H6z",
        "devices" to "M7,1h10c1.1,0 2,.9 2,2v18c0,1.1 -.9,2 -2,2H7c-1.1,0 -2,-.9 -2,-2V3c0,-1.1 .9,-2 2,-2z M7,4v14h10V4z M11,19.5h2v2h-2z",
        "check" to "M9,16.2L4.8,12l-1.4,1.4L9,19 21,7l-1.4,-1.4z",
        "help" to "M12,2a10,10 0,1 0,0,20 10,10 0,0 0,0,-20z M11,16h2v2h-2z M12,6a4,4 0,0 1,4,4c0,2.5 -3,2.8 -3,5h-2c0,-3.3 3,-3.2 3,-5a2,2 0,0 0,-4,0H8a4,4 0,0 1,4,-4z"
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
