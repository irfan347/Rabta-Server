package com.irfan.rabta

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast

/** A photo that fits the screen and can be pinched, panned and double-tapped to zoom. Rabta's screens don't use AppCompat. */
@android.annotation.SuppressLint("AppCompatCustomView")
class ZoomImageView(c: Context) : ImageView(c) {
    private val base = Matrix()
    private val user = Matrix()
    private val shown = Matrix()
    private var zoom = 1f
    private val scaler = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val next = (zoom * d.scaleFactor).coerceIn(1f, 5f)
            user.postScale(next / zoom, next / zoom, d.focusX, d.focusY); zoom = next; settle(); return true
        }
    })
    private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1f) return false
            user.postTranslate(-dx, -dy); settle(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1f) { user.reset(); zoom = 1f } else { user.postScale(2.5f, 2.5f, e.x, e.y); zoom = 2.5f }
            settle(); return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent) = performClick()
    })
    init { scaleType = ScaleType.MATRIX }
    override fun setImageBitmap(bm: Bitmap?) { super.setImageBitmap(bm); user.reset(); zoom = 1f; fit() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }
    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || d.intrinsicWidth <= 0) return
        base.setRectToRect(RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()), RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER)
        settle()
    }
    /** Keeps a zoomed photo covering the screen edges instead of drifting away. */
    private fun settle() {
        val d = drawable ?: return
        shown.set(base); shown.postConcat(user)
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()); shown.mapRect(r)
        val dx = when { r.width() <= width -> width / 2f - r.centerX(); r.left > 0 -> -r.left; r.right < width -> width - r.right; else -> 0f }
        val dy = when { r.height() <= height -> height / 2f - r.centerY(); r.top > 0 -> -r.top; r.bottom < height -> height - r.bottom; else -> 0f }
        user.postTranslate(dx, dy); shown.set(base); shown.postConcat(user); imageMatrix = shown
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e); gestures.onTouchEvent(e)
        parent?.requestDisallowInterceptTouchEvent(zoom > 1f)
        return true
    }
}

/** Full-screen photo on black, as in WhatsApp. */
object PhotoViewer {
    fun show(a: Activity, bitmap: Bitmap?, title: String) {
        if (bitmap == null) { Toast.makeText(a, "Photo unavailable", Toast.LENGTH_SHORT).show(); return }
        val dialog = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(a).apply { setBackgroundColor(Color.BLACK) }
        root.addView(ZoomImageView(a).apply { setImageBitmap(bitmap); contentDescription = "Photo from $title" }, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(RabtaUi.dp(a, 8), RabtaUi.dp(a, 24), RabtaUi.dp(a, 16), RabtaUi.dp(a, 8))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0))
        }
        bar.addView(RabtaUi.iconButton(a, "back", "Close photo") { dialog.dismiss() }.apply { UiIcons.set(this, "back", Color.WHITE, 24, "Close photo") })
        bar.addView(RabtaUi.text(a, title, 17f, true, Color.WHITE))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        dialog.setContentView(root); dialog.show()
    }
}

/**
 * Crop over a fitted photo: drag the frame or its corners, rotate in quarter turns. [square] keeps 1:1
 * (profile photos, shown as a circle). [result] returns the chosen pixels of the real image, never a screenshot.
 */
class CropView(c: Context) : View(c) {
    var square = false
        set(value) { field = value; reset() }
    var source: Bitmap? = null
        set(value) { field = value; reset() }
    private val image = RectF()
    private val crop = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Path()
    private var mode = 0 // 0 none, 1 move, 2..5 corners (TL, TR, BL, BR)
    private var lastX = 0f
    private var lastY = 0f
    private val d = resources.displayMetrics.density
    init { contentDescription = "Crop area. Drag the corners to resize, drag inside to move." }

    fun rotate() {
        val b = source ?: return
        source = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(90f) }, true)
    }
    fun result(): Bitmap? {
        val b = source ?: return null
        if (image.width() <= 0) return b
        val s = b.width / image.width()
        val x = ((crop.left - image.left) * s).toInt().coerceIn(0, b.width - 1)
        val y = ((crop.top - image.top) * s).toInt().coerceIn(0, b.height - 1)
        var w = (crop.width() * s).toInt().coerceIn(1, b.width - x)
        var h = (crop.height() * s).toInt().coerceIn(1, b.height - y)
        if (square) { w = minOf(w, h); h = w }
        return Bitmap.createBitmap(b, x, y, w, h)
    }
    private fun reset() {
        val b = source ?: return
        if (width == 0) return
        val pad = 24 * d
        image.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
        Matrix().apply { setRectToRect(RectF(image), RectF(pad, pad, width - pad, height - pad), Matrix.ScaleToFit.CENTER) }.mapRect(image)
        if (square) { val side = minOf(image.width(), image.height()); crop.set(image.centerX() - side / 2, image.centerY() - side / 2, image.centerX() + side / 2, image.centerY() + side / 2) }
        else crop.set(image)
        crop.intersect(image) // centring a frame as wide as the photo can overshoot its edges by a rounding error
        invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); reset() }

    override fun onDraw(canvas: Canvas) {
        val b = source ?: return
        canvas.drawBitmap(b, null, image, paint.apply { alpha = 255; style = Paint.Style.FILL })
        // Dim everything outside the frame (outside the circle for profile photos).
        shade.reset(); shade.fillType = Path.FillType.EVEN_ODD
        shade.addRect(image, Path.Direction.CW)
        if (square) shade.addCircle(crop.centerX(), crop.centerY(), crop.width() / 2, Path.Direction.CW) else shade.addRect(crop, Path.Direction.CW)
        paint.color = 0x99000000.toInt(); canvas.drawPath(shade, paint)
        paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = 1.5f * d
        canvas.drawRect(crop, paint)
        if (mode != 0) { // rule-of-thirds guides while adjusting
            paint.alpha = 110; paint.strokeWidth = d
            for (i in 1..2) {
                canvas.drawLine(crop.left + crop.width() * i / 3, crop.top, crop.left + crop.width() * i / 3, crop.bottom, paint)
                canvas.drawLine(crop.left, crop.top + crop.height() * i / 3, crop.right, crop.top + crop.height() * i / 3, paint)
            }
            paint.alpha = 255
        }
        paint.strokeWidth = 4 * d; paint.strokeCap = Paint.Cap.ROUND
        val l = 18 * d
        for ((x, y) in listOf(crop.left to crop.top, crop.right to crop.top, crop.left to crop.bottom, crop.right to crop.bottom)) {
            val sx = if (x == crop.left) 1 else -1; val sy = if (y == crop.top) 1 else -1
            canvas.drawLine(x, y, x + sx * l, y, paint); canvas.drawLine(x, y, x, y + sy * l, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y; val grab = 36 * d; val min = 64 * d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = when {
                    near(x, y, crop.left, crop.top, grab) -> 2
                    near(x, y, crop.right, crop.top, grab) -> 3
                    near(x, y, crop.left, crop.bottom, grab) -> 4
                    near(x, y, crop.right, crop.bottom, grab) -> 5
                    crop.contains(x, y) -> 1
                    else -> 0
                }
                lastX = x; lastY = y; parent?.requestDisallowInterceptTouchEvent(mode != 0); invalidate(); return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX; val dy = y - lastY; lastX = x; lastY = y
                when (mode) {
                    1 -> {
                        val mx = dx.clamp(image.left - crop.left, image.right - crop.right, 0f)
                        val my = dy.clamp(image.top - crop.top, image.bottom - crop.bottom, 0f)
                        crop.offset(mx, my)
                    }
                    2, 3, 4, 5 -> {
                        val left = mode == 2 || mode == 4; val top = mode == 2 || mode == 3
                        if (square) {
                            // One size for both sides, anchored at the opposite corner.
                            val ax = if (left) crop.right else crop.left; val ay = if (top) crop.bottom else crop.top
                            val want = maxOf(if (left) ax - x else x - ax, if (top) ay - y else y - ay)
                            val room = minOf(if (left) ax - image.left else image.right - ax, if (top) ay - image.top else image.bottom - ay)
                            val side = want.clamp(min, room, room)
                            crop.set(if (left) ax - side else ax, if (top) ay - side else ay, if (left) ax else ax + side, if (top) ay else ay + side)
                        } else {
                            if (left) crop.left = (crop.left + dx).clamp(image.left, crop.right - min, crop.left) else crop.right = (crop.right + dx).clamp(crop.left + min, image.right, crop.right)
                            if (top) crop.top = (crop.top + dy).clamp(image.top, crop.bottom - min, crop.top) else crop.bottom = (crop.bottom + dy).clamp(crop.top + min, image.bottom, crop.bottom)
                        }
                    }
                }
                invalidate(); return mode != 0
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = 0; invalidate(); return true }
        }
        return false
    }
    private fun near(x: Float, y: Float, px: Float, py: Float, r: Float) = Math.abs(x - px) <= r && Math.abs(y - py) <= r
    /** coerceIn that cannot throw: when the frame fills the photo, rounding can leave the range a hair inside out. */
    private fun Float.clamp(lo: Float, hi: Float, otherwise: Float) = if (lo > hi) otherwise else coerceIn(lo, hi)
}

/** Full-screen crop step shared by the profile photo and the status editor. */
object CropScreen {
    fun show(a: Activity, bitmap: Bitmap, square: Boolean, done: (Bitmap) -> Unit) {
        val dialog = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val cropper = CropView(a).apply { this.square = square; source = bitmap }
        root.addView(RabtaUi.text(a, if (square) "Move and resize the circle" else "Crop and rotate", 15f, true, Color.WHITE).apply {
            gravity = Gravity.CENTER; setPadding(0, RabtaUi.dp(a, 28), 0, RabtaUi.dp(a, 8))
        })
        root.addView(cropper, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(RabtaUi.dp(a, 12), RabtaUi.dp(a, 8), RabtaUi.dp(a, 12), RabtaUi.dp(a, 24)) }
        fun action(label: String, primary: Boolean, run: () -> Unit) = RabtaUi.text(a, label, 16f, true, if (primary) AppTheme.onPrimary(a) else Color.WHITE).apply {
            gravity = Gravity.CENTER; minHeight = RabtaUi.dp(a, 48); setPadding(RabtaUi.dp(a, 20), 0, RabtaUi.dp(a, 20), 0); isFocusable = true
            background = RabtaUi.ripple(a, if (primary) AppTheme.primary(a) else Color.TRANSPARENT, 999); setOnClickListener { run() }
        }
        bar.addView(action("Cancel", false) { dialog.dismiss() })
        bar.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(UiIcons.image(a, "rotate", Color.WHITE, 26, "Rotate").apply {
            isFocusable = true; background = RabtaUi.ripple(a, Color.TRANSPARENT, 999); setOnClickListener { cropper.rotate() }
        }, LinearLayout.LayoutParams(RabtaUi.dp(a, 52), RabtaUi.dp(a, 52)))
        bar.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(action("Done", true) { cropper.result()?.let { dialog.dismiss(); done(it) } })
        root.addView(bar)
        dialog.setContentView(root); dialog.show()
    }
}
