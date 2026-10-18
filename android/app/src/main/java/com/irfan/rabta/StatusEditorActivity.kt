package com.irfan.rabta

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.ByteArrayOutputStream

/**
 * WhatsApp-style status preview before sharing: full-screen photo or video, crop/rotate, stickers, text and
 * drawing (photos), a caption, who can see it, and send. Returns the prepared file; StatusActivity uploads it.
 */
class StatusEditorActivity : ThemedActivity() {
    private lateinit var kind: String
    private lateinit var source: Uri
    private lateinit var root: FrameLayout
    private lateinit var top: LinearLayout
    private lateinit var tools: LinearLayout
    private lateinit var drawTools: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var caption: EditText
    private lateinit var emoji: EmojiField
    private lateinit var audiencePill: TextView
    private lateinit var palette: LinearLayout
    private var canvasView: StatusCanvasView? = null
    private var video: VideoView? = null
    private var audience: List<String> = emptyList()
    private var sending = false
    private fun dp(n: Int) = RabtaUi.dp(this, n)
    private val colors = intArrayOf(Color.WHITE, Color.BLACK, 0xFFBFFF76.toInt(), 0xFF54DDC5.toInt(), 0xFFA997FF.toInt(), 0xFFFF5C7A.toInt(), 0xFFFFC94D.toInt(), 0xFF4DA3FF.toInt())

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        kind = intent.getStringExtra("kind").orEmpty(); source = intent.data ?: run { finish(); return }
        if (kind != "image" && kind != "video") { finish(); return }
        RabtaUi.systemBars(this, true)
        audience = StatusPost.audience(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Top: close · (music, crop, sticker, text, draw) — draw mode swaps in undo · done.
        top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(16))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x88000000.toInt(), 0))
        }
        top.addView(roundIcon("close", "Discard") { finish() })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tools.addView(roundIcon("music", "Music. Coming soon") { Toast.makeText(this, "Music on status: coming soon", Toast.LENGTH_SHORT).show() }.apply { alpha = 0.6f })
        if (kind == "image") {
            tools.addView(roundIcon("crop_rotate", "Crop and rotate") { crop() })
            tools.addView(roundIcon("sticker", "Add sticker") { sticker() })
            tools.addView(roundText("Aa", "Add text") { text(null) })
            tools.addView(roundIcon("draw", "Draw") { drawMode(true) })
        }
        top.addView(tools)
        drawTools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        drawTools.addView(roundIcon("undo", "Undo") { canvasView?.undo() })
        drawTools.addView(RabtaUi.text(this, "Done", 16f, true, AppTheme.onPrimary(this)).apply {
            gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(18), 0, dp(18), 0); isFocusable = true
            background = RabtaUi.ripple(this@StatusEditorActivity, AppTheme.primary(this@StatusEditorActivity), 999); setOnClickListener { drawMode(false) }
        }, LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(8) })
        top.addView(drawTools)

        // Brush colours (draw mode), down the right edge like WhatsApp.
        palette = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE; setPadding(dp(6), dp(8), dp(6), dp(8)) }
        colors.forEach { color -> palette.addView(swatch(color) { canvasView?.brush = color; markSwatch(palette, color) }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { bottomMargin = dp(10) }) }

        // Bottom: (AI soon) · caption · who can see · send.
        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(24), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xAA000000.toInt(), 0))
        }
        if (kind == "image") bottom.addView(RabtaUi.text(this, "✦  Restyle with AI · Soon", 13f, true, 0xCCFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)); isFocusable = true; contentDescription = "Restyle with AI. Coming soon"
            background = RabtaUi.shape(this@StatusEditorActivity, 0x33FFFFFF, 999)
            setOnClickListener { Toast.makeText(this@StatusEditorActivity, "Restyle with AI: coming soon", Toast.LENGTH_SHORT).show() }
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        // Caption with an emoji button, like WhatsApp's.
        val captionBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = RabtaUi.shape(this@StatusEditorActivity, 0xFF1F2A33.toInt(), 26); setPadding(dp(4), 0, 0, 0) }
        val emojiButton = UiIcons.image(this, "emoji", Color.WHITE, 24, "Emoji").apply { background = RabtaUi.ripple(this@StatusEditorActivity, Color.TRANSPARENT, 999) }
        captionBar.addView(emojiButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        caption = EditText(this).apply {
            hint = "Add a caption…"; setHintTextColor(0xB3FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 16f; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(700)); contentDescription = "Caption"
            background = null; setPadding(dp(6), dp(12), dp(18), dp(12)); minHeight = dp(50)
        }
        captionBar.addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(captionBar, LinearLayout.LayoutParams(-1, -2))
        val sendRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        audiencePill = RabtaUi.text(this, "", 14f, true, Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL; minHeight = dp(44); setPadding(dp(14), 0, dp(16), 0); isFocusable = true; compoundDrawablePadding = dp(8)
            setCompoundDrawablesRelative(UiIcons.drawable(this@StatusEditorActivity, "people", Color.WHITE, 18), null, null, null)
            background = RabtaUi.ripple(this@StatusEditorActivity, 0xFF1F2A33.toInt(), 999)
            setOnClickListener { StatusPost.chooseAudience(this@StatusEditorActivity, audience) { audience = it; showAudience() } }
        }
        sendRow.addView(audiencePill, LinearLayout.LayoutParams(-2, dp(44)))
        sendRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        sendRow.addView(UiIcons.image(this, "send", AppTheme.onPrimary(this), 26, "Send status").apply {
            isFocusable = true; background = RabtaUi.ripple(this@StatusEditorActivity, AppTheme.primary(this@StatusEditorActivity), 999); setOnClickListener { send() }
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        bottom.addView(sendRow)
        emoji = EmojiField(this, caption, bottom, emojiButton, Color.WHITE)
        showAudience()

        if (kind == "image") loadImage() else loadVideo()
        root.addView(palette, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    override fun onKeyboardVisibilityChanged(visible: Boolean) { if (visible && ::emoji.isInitialized) emoji.keyboardShown() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (::emoji.isInitialized && emoji.open) emoji.hide() else super.onBackPressed() }

    private fun showAudience() { audiencePill.text = StatusPost.audienceLabel(audience); audiencePill.contentDescription = "${audiencePill.text}. Change who can see" }

    private fun roundIcon(icon: String, label: String, run: () -> Unit) = UiIcons.image(this, icon, Color.WHITE, 22, label).apply {
        isFocusable = true; background = RabtaUi.ripple(this@StatusEditorActivity, 0x66000000, 999); setOnClickListener { run() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) }
    }
    private fun roundText(value: String, label: String, run: () -> Unit) = RabtaUi.text(this, value, 17f, true, Color.WHITE).apply {
        gravity = Gravity.CENTER; isFocusable = true; contentDescription = label; includeFontPadding = false
        background = RabtaUi.ripple(this@StatusEditorActivity, 0x66000000, 999); setOnClickListener { run() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) }
    }
    private fun swatch(color: Int, pick: () -> Unit) = View(this).apply {
        tag = color; isFocusable = true; contentDescription = "Colour"; setOnClickListener { pick() }
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE) }
    }
    private fun markSwatch(group: LinearLayout, color: Int) {
        for (i in 0 until group.childCount) group.getChildAt(i).let { it.scaleX = if (it.tag == color) 1.25f else 1f; it.scaleY = it.scaleX }
    }

    private fun loadImage() {
        val view = StatusCanvasView(this).apply { contentDescription = "Status photo preview" }
        canvasView = view
        root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        view.onDragging = { dragging -> top.visibility = if (dragging) View.INVISIBLE else View.VISIBLE; bottom.visibility = top.visibility }
        Thread {
            // 1600px on the long side is sharp on any phone and keeps the upload small.
            val bitmap = MediaHelper.decodeBitmap(this, source, 1600)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (bitmap == null) { Toast.makeText(this, "Could not open that photo", Toast.LENGTH_LONG).show(); finish() } else view.setBase(bitmap)
            }
        }.start()
    }

    private fun loadVideo() {
        val frame = FrameLayout(this)
        val player = VideoView(this).apply { contentDescription = "Status video preview" }
        video = player
        frame.addView(player, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val info = RabtaUi.text(this, "", 13f, true, Color.WHITE).apply { setPadding(dp(12), dp(6), dp(12), dp(6)); background = RabtaUi.shape(this@StatusEditorActivity, 0x66000000, 999) }
        frame.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(76) })
        root.addView(frame, 0, FrameLayout.LayoutParams(-1, -1))
        player.setVideoURI(source)
        player.setOnPreparedListener { mp -> mp.isLooping = true; player.start() }
        player.setOnErrorListener { _, _, _ -> info.text = "Preview unavailable on this phone"; true }
        frame.setOnClickListener { if (player.isPlaying) player.pause() else player.start() }
        Thread {
            val details = runCatching {
                val r = android.media.MediaMetadataRetriever()
                try { r.setDataSource(this, source); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L } finally { r.release() }
            }.getOrDefault(0L)
            val size = runCatching { contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length } }.getOrNull() ?: -1L
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val length = VoiceAudio.format(details)
                info.text = when {
                    details > 60_500 -> "$length · Status videos can be up to 1:00"
                    size > 7_600_000 -> "$length · %.1f MB · will be compressed to send".format(size / 1048576.0)
                    size > 0 -> "$length · %.1f MB".format(size / 1048576.0)
                    else -> length
                }
            }
        }.start()
    }

    private fun drawMode(on: Boolean) {
        val view = canvasView ?: return
        view.drawing = on
        tools.visibility = if (on) View.GONE else View.VISIBLE; drawTools.visibility = if (on) View.VISIBLE else View.GONE
        palette.visibility = if (on) View.VISIBLE else View.GONE; bottom.visibility = if (on) View.GONE else View.VISIBLE
        if (on) markSwatch(palette, view.brush)
    }

    private fun crop() {
        val view = canvasView ?: return
        val flat = view.render() ?: return
        // Stickers, text and drawing so far become part of the photo, then the whole picture is cropped.
        CropScreen.show(this, flat, false) { view.setBase(it) }
    }

    private fun sticker() {
        val view = canvasView ?: return
        Stickers.picker(this, { body ->
            val drawable: Drawable? = if (Stickers.valid(body)) Stickers.drawable(body)
                else runCatching { BitmapDrawable(resources, android.graphics.BitmapFactory.decodeFile(Attachments.file(this, body).absolutePath)) }.getOrNull()
            if (drawable != null) view.addSticker(drawable) else Toast.makeText(this, "Sticker unavailable", Toast.LENGTH_SHORT).show()
        }, { Toast.makeText(this, "Import stickers from a chat first", Toast.LENGTH_SHORT).show() })
    }

    /** Full-screen text entry with a colour row, as in WhatsApp. */
    private fun text(start: String?) {
        val view = canvasView ?: return
        var color = Color.WHITE
        val layer = FrameLayout(this).apply { setBackgroundColor(0xB3000000.toInt()); isClickable = true }
        val field = EditText(this).apply {
            setText(start.orEmpty()); textSize = 30f; setTextColor(color); gravity = Gravity.CENTER; background = null
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); hint = "Type something"; setHintTextColor(0x88FFFFFF.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        }
        layer.addView(field, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = dp(24); rightMargin = dp(24) })
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(16)) }
        colors.forEach { c -> row.addView(swatch(c) { color = c; field.setTextColor(c); markSwatch(row, c) }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(6); marginEnd = dp(6) }) }
        markSwatch(row, color)
        layer.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        fun close() {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
            root.removeView(layer); top.visibility = View.VISIBLE; bottom.visibility = View.VISIBLE
        }
        layer.addView(RabtaUi.text(this, "Done", 16f, true, AppTheme.onPrimary(this)).apply {
            gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(18), 0, dp(18), 0); isFocusable = true
            background = RabtaUi.ripple(this@StatusEditorActivity, AppTheme.primary(this@StatusEditorActivity), 999)
            setOnClickListener { val value = field.text.toString().trim(); if (value.isNotEmpty()) view.addText(value, color); close() }
        }, FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP or Gravity.END).apply { topMargin = dp(12); rightMargin = dp(12) })
        top.visibility = View.GONE; bottom.visibility = View.GONE
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        field.requestFocus()
        androidx.core.view.WindowCompat.getInsetsController(window, field).show(androidx.core.view.WindowInsetsCompat.Type.ime())
    }

    private fun send() {
        if (sending) return
        if (audience.isEmpty()) { StatusPost.chooseAudience(this, audience) { audience = it; showAudience(); if (it.isNotEmpty()) send() }; return }
        sending = true
        val text = caption.text.toString().trim()
        if (kind == "video") {
            video?.pause()
            VideoCompressor.prepareWithDialog(this, source, 60_000) { body -> finishWith(body, text) }
            sending = false
            return
        }
        val view = canvasView ?: return
        Toast.makeText(this, "Preparing status…", Toast.LENGTH_SHORT).show()
        val picture = view.render()
        if (picture == null) { sending = false; return }
        Thread {
            val result = runCatching {
                val out = ByteArrayOutputStream(); picture.compress(Bitmap.CompressFormat.JPEG, 88, out)
                Attachments.save(this, out.toByteArray(), "status-${System.currentTimeMillis()}.jpg", "image/jpeg")
            }
            runOnUiThread {
                sending = false
                if (isDestroyed) { result.getOrNull()?.let { Attachments.discard(this, it) }; return@runOnUiThread }
                result.onSuccess { finishWith(it, text) }.onFailure { Toast.makeText(this, it.message ?: "Could not prepare this photo", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
    private fun finishWith(body: String, text: String) {
        setResult(RESULT_OK, Intent().putExtra("kind", kind).putExtra("body", body).putExtra("caption", text).putExtra("audience", audience.toTypedArray()))
        finish()
    }
    override fun onPause() { video?.pause(); super.onPause() }
}

/**
 * The photo being edited plus what was added on top. Everything is kept in the photo's own pixel coordinates,
 * so [render] produces the full-resolution picture exactly as previewed.
 */
class StatusCanvasView(c: Context) : View(c) {
    private sealed class Item
    private class Stroke(val color: Int, val width: Float) : Item() { val path = Path(); var last: PointF? = null }
    private class Label(val text: String, val color: Int, var x: Float, var y: Float, var size: Float) : Item()
    private class Sticker(val drawable: Drawable, var x: Float, var y: Float, var size: Float) : Item()
    private var base: Bitmap? = null
    private val items = mutableListOf<Item>()
    var drawing = false
    var brush = Color.WHITE
    var onDragging: (Boolean) -> Unit = {}
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val d = resources.displayMetrics.density
    private var active: Item? = null
    private var lastX = 0f
    private var lastY = 0f
    private var pinchStart = 0f
    private var sizeStart = 0f
    private var overTrash = false

    fun setBase(bitmap: Bitmap) { base = bitmap; items.clear(); fit(); invalidate() }
    fun addText(text: String, color: Int) { base ?: return; items += Label(text, color, cx(), cy(), 34 * d / scale); invalidate() }
    fun addSticker(drawable: Drawable) { base ?: return; items += Sticker(drawable, cx(), cy(), 130 * d / scale); invalidate() }
    fun undo() { items.indexOfLast { it is Stroke }.takeIf { it >= 0 }?.let { items.removeAt(it); invalidate() } }
    private fun cx() = (width / 2f - ox) / scale
    private fun cy() = (height / 2f - oy) / scale

    fun render(): Bitmap? {
        val b = base ?: return null
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        paintScene(Canvas(out), b)
        return out
    }
    private fun fit() {
        val b = base ?: return
        if (width == 0) return
        scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        ox = (width - b.width * scale) / 2; oy = (height - b.height * scale) / 2
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    override fun onDraw(canvas: Canvas) {
        val b = base ?: return
        canvas.save(); canvas.translate(ox, oy); canvas.scale(scale, scale)
        paintScene(canvas, b)
        canvas.restore()
        if (active != null && active !is Stroke) { // drop here to delete
            paint.style = Paint.Style.FILL; paint.color = if (overTrash) 0xFFFF5C7A.toInt() else 0x88000000.toInt()
            canvas.drawCircle(width / 2f, trashY(), 30 * d, paint)
            val icon = trashIcon
            val half = (13 * d).toInt(); icon.setBounds((width / 2f).toInt() - half, trashY().toInt() - half, (width / 2f).toInt() + half, trashY().toInt() + half); icon.draw(canvas)
        }
    }
    private fun trashY() = height - 90 * d
    private val trashIcon by lazy { UiIcons.drawable(context, "trash", Color.WHITE, 26) }
    private fun paintScene(canvas: Canvas, b: Bitmap) {
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        canvas.drawBitmap(b, 0f, 0f, paint)
        for (item in items) when (item) {
            is Stroke -> { paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.color = item.color; paint.strokeWidth = item.width; canvas.drawPath(item.path, paint) }
            is Label -> {
                textPaint.textSize = item.size; textPaint.color = item.color
                textPaint.setShadowLayer(item.size * 0.08f, 0f, item.size * 0.03f, if (item.color == Color.BLACK) 0x66FFFFFF else 0x99000000.toInt())
                val lines = item.text.split('\n'); val line = item.size * 1.2f; var y = item.y - line * lines.size / 2 + item.size * 0.9f
                for (l in lines) { canvas.drawText(l, item.x, y, textPaint); y += line }
            }
            is Sticker -> { val h = item.size / 2; item.drawable.setBounds((item.x - h).toInt(), (item.y - h).toInt(), (item.x + h).toInt(), (item.y + h).toInt()); item.drawable.draw(canvas) }
        }
        paint.style = Paint.Style.FILL
    }
    private fun bounds(item: Item): RectF = when (item) {
        is Label -> { textPaint.textSize = item.size; val lines = item.text.split('\n'); val w = lines.maxOf { textPaint.measureText(it) }; val h = item.size * 1.2f * lines.size
            RectF(item.x - w / 2, item.y - h / 2, item.x + w / 2, item.y + h / 2) }
        is Sticker -> RectF(item.x - item.size / 2, item.y - item.size / 2, item.x + item.size / 2, item.y + item.size / 2)
        is Stroke -> RectF()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        base ?: return false
        val bx = (e.x - ox) / scale; val by = (e.y - oy) / scale
        if (drawing) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { val s = Stroke(brush, 6 * d / scale); s.path.moveTo(bx, by); s.last = PointF(bx, by); items += s; active = s }
                MotionEvent.ACTION_MOVE -> (active as? Stroke)?.let { s -> val l = s.last!!; s.path.quadTo(l.x, l.y, (bx + l.x) / 2, (by + l.y) / 2); s.last = PointF(bx, by) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { (active as? Stroke)?.let { it.path.lineTo(bx, by) }; active = null }
            }
            invalidate(); return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val slop = 16 * d / scale
                active = items.lastOrNull { it !is Stroke && bounds(it).apply { inset(-slop, -slop) }.contains(bx, by) }
                active?.let { items.remove(it); items.add(it); onDragging(true) }
                lastX = e.x; lastY = e.y; overTrash = false; invalidate(); return active != null
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && active != null) { pinchStart = spread(e); sizeStart = size(active!!) }
            MotionEvent.ACTION_MOVE -> {
                val item = active ?: return false
                if (e.pointerCount >= 2 && pinchStart > 0) setSize(item, (sizeStart * spread(e) / pinchStart).coerceIn(12 * d / scale, maxOf(base!!.width, base!!.height).toFloat()))
                else {
                    val dx = (e.x - lastX) / scale; val dy = (e.y - lastY) / scale
                    when (item) { is Label -> { item.x += dx; item.y += dy }; is Sticker -> { item.x += dx; item.y += dy }; else -> {} }
                    overTrash = Math.hypot((e.x - width / 2f).toDouble(), (e.y - trashY()).toDouble()) < 44 * d
                }
                lastX = e.x; lastY = e.y; invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> { pinchStart = 0f; val i = if (e.actionIndex == 0) 1 else 0; lastX = e.getX(i); lastY = e.getY(i) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active?.let { if (overTrash) items.remove(it) }
                active = null; overTrash = false; pinchStart = 0f; onDragging(false); invalidate()
            }
        }
        return true
    }
    private fun spread(e: MotionEvent) = Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat()
    private fun size(item: Item) = when (item) { is Label -> item.size; is Sticker -> item.size; else -> 0f }
    private fun setSize(item: Item, value: Float) { when (item) { is Label -> item.size = value; is Sticker -> item.size = value; else -> {} } }
}
