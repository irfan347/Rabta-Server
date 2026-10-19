package com.irfan.rabta

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A frame and the length of videos already on this phone, read once in the background and kept for redraws. */
object VideoFrames {
    private val cache = LruCache<String, Pair<Bitmap?, Long>>(24)
    private val worker = Executors.newSingleThreadExecutor()
    fun cached(f: File): Pair<Bitmap?, Long>? = cache.get(f.absolutePath)
    fun load(a: Activity, f: File, done: (Bitmap?, Long) -> Unit) {
        cached(f)?.let { done(it.first, it.second); return }
        worker.execute {
            val r = MediaMetadataRetriever()
            val read = runCatching {
                r.setDataSource(f.absolutePath)
                val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                    val scale = minOf(1f, 480f / maxOf(b.width, b.height))
                    if (scale < 1f) Bitmap.createScaledBitmap(b, maxOf(1, (b.width * scale).toInt()), maxOf(1, (b.height * scale).toInt()), true) else b
                }
                frame to (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
            }.getOrDefault(null to 0L)
            runCatching { r.release() }
            cache.put(f.absolutePath, read)
            a.runOnUiThread { if (!a.isDestroyed) done(read.first, read.second) }
        }
    }
}

/** Videos being downloaded from a chat, so a bubble drawn again (a new message arrived) still shows how far it got. */
object VideoDownloads {
    private val percent = ConcurrentHashMap<String, Int>()
    private val shown = HashMap<String, java.lang.ref.WeakReference<VideoBubble>>()   // main thread only
    fun attach(key: String, bubble: VideoBubble) { shown[key] = java.lang.ref.WeakReference(bubble) }
    fun progress(key: String): Int? = percent[key]
    private fun redraw(key: String) { shown[key]?.get()?.showState() }
    /** Downloads [body] once; [done] gets the saved file (on the main thread) unless the screen has closed. */
    fun start(a: Activity, key: String, body: String, done: (File) -> Unit) {
        if (percent.putIfAbsent(key, 0) != null) return
        redraw(key)
        val app = a.applicationContext
        Thread {
            val result = runCatching { Transfers.download(app, body) { p -> percent[key] = p; a.runOnUiThread { redraw(key) } } }
            a.runOnUiThread {
                percent.remove(key); redraw(key)
                if (a.isDestroyed || a.isFinishing) return@runOnUiThread
                result.onSuccess { done(Attachments.file(app, it)) }
                    .onFailure { Toast.makeText(a, it.message ?: "Could not download the video. Try again.", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}

/**
 * A video in a chat, as in WhatsApp: its picture with a play button in the middle, the size with a download mark in
 * the bottom-left corner (its length once it is on this phone). The picture comes with the message ([Attachments.withVideoPreview]);
 * an older video without one shows a dark frame until it is downloaded, then a frame of its own.
 */
class VideoBubble(private val a: Activity, private val key: String, private val body: String, private val title: String) : FrameLayout(a) {
    private fun dp(n: Int) = RabtaUi.dp(a, n)
    private val o = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
    private val picture = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val play = UiIcons.image(a, "play", Color.WHITE, 34, "").apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val spinner = ProgressBar(a).apply { indeterminateTintList = ColorStateList.valueOf(Color.WHITE); visibility = View.GONE }
    private val chipIcon = ImageView(a).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val chipText = RabtaUi.text(a, "", 13f, false, Color.WHITE).apply { maxLines = 1; includeFontPadding = false }
    /** Bottom-left corner: download (with the size) before the video is on this phone, its length after. */
    val chip = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(6), dp(12), dp(6)); isFocusable = true
        background = RabtaUi.ripple(a, 0x73000000, 999)
        addView(chipIcon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
        addView(chipText)
    }
    /** The middle of the picture: play, or a spinner while it downloads. */
    val centre = FrameLayout(a).apply {
        isFocusable = true; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x8C000000.toInt()) }
        addView(play, LayoutParams(dp(34), dp(34), Gravity.CENTER))
        addView(spinner, LayoutParams(dp(34), dp(34), Gravity.CENTER))
    }
    private var length = o.optLong("ms")

    init {
        // WhatsApp's shape: as wide as a photo, tall or wide as the video itself within limits (the rest is cropped).
        val width = dp(250)
        val ratio = if (o.optInt("w") > 0 && o.optInt("h") > 0) o.optInt("h").toFloat() / o.optInt("w") else 0.75f
        layoutParams = LayoutParams(width, (width * ratio.coerceIn(0.56f, 1.4f)).toInt())
        background = RabtaUi.shape(a, 0xFF1F2C34.toInt(), 12); clipToOutline = true
        addView(picture, LayoutParams(-1, -1))
        o.optString("thumb").takeIf { it.isNotEmpty() }?.let { MediaHelper.base64ToBitmap(it) }?.let { picture.setImageBitmap(it) }
        // Shade at the bottom keeps the corner labels readable on bright pictures.
        addView(View(a).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x8C000000.toInt(), 0)) }, LayoutParams(-1, dp(56), Gravity.BOTTOM))
        addView(centre, LayoutParams(dp(56), dp(56), Gravity.CENTER))
        addView(chip, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), 0, 0, dp(8)) })
        VideoDownloads.attach(key, this)
        showState()
    }

    private fun saved(): File? = Transfers.onPhone(a, body)

    fun showState() {
        val progress = VideoDownloads.progress(key)
        val file = if (progress == null) saved() else null
        play.visibility = if (progress == null) View.VISIBLE else View.GONE
        spinner.visibility = if (progress == null) View.GONE else View.VISIBLE
        val size = Attachments.details(body)
        chipIcon.setImageDrawable(UiIcons.drawable(a, if (progress == null && file != null) "video" else "download", Color.WHITE, 18))
        when {
            progress != null -> chipText.text = "$progress%"
            file != null -> {
                chipText.text = if (length > 0) VoiceAudio.format(length) else size
                if (picture.drawable == null || length <= 0) VideoFrames.load(a, file) { frame, ms ->
                    if (picture.drawable == null && frame != null) picture.setImageBitmap(frame)
                    if (length <= 0 && ms > 0) { length = ms; if (VideoDownloads.progress(key) == null) chipText.text = VoiceAudio.format(ms) }
                }
            }
            else -> chipText.text = size
        }
        chip.contentDescription = when { progress != null -> "Downloading video, $progress%"; file != null -> "Video${if (length > 0) ", " + VoiceAudio.format(length) else ""}"; else -> "Download video, $size" }
        centre.contentDescription = if (progress != null) "Downloading video" else "Play video"
        contentDescription = "Video from $title"
    }

    /** Plays it here; one not on this phone yet is downloaded first. */
    fun play() {
        if (VideoDownloads.progress(key) != null) return
        val file = saved()
        if (file != null) VideoPlayer.show(a, file, title) { Attachments.open(a, body) }
        else VideoDownloads.start(a, key, body) { VideoPlayer.show(a, it, title) { Attachments.open(a, body) } }
    }
    /** The corner chip: downloads the video (and plays it when it already is on this phone). */
    fun download() {
        if (VideoDownloads.progress(key) != null) return
        if (saved() != null) play() else VideoDownloads.start(a, key, body) {}
    }
}

/** Full-screen video on black with play, pause and seeking, as in WhatsApp. [failed] runs when this phone can't play it. */
object VideoPlayer {
    fun show(a: Activity, file: File, title: String, failed: () -> Unit) {
        VoiceMessageView.pauseAll()
        val dialog = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(a).apply { setBackgroundColor(Color.BLACK) }
        val video = VideoView(a).apply { contentDescription = "Video from $title" }
        root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val bar = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(RabtaUi.dp(a, 8), RabtaUi.dp(a, 24), RabtaUi.dp(a, 16), RabtaUi.dp(a, 8))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0))
        }
        bar.addView(RabtaUi.iconButton(a, "back", "Close video") { dialog.dismiss() }.apply { UiIcons.set(this, "back", Color.WHITE, 24, "Close video") })
        bar.addView(RabtaUi.text(a, title, 17f, true, Color.WHITE))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        video.setMediaController(MediaController(a))
        video.setOnPreparedListener { video.start() }
        video.setOnErrorListener { _, _, _ -> dialog.dismiss(); failed(); true }
        dialog.setOnDismissListener { video.stopPlayback() }
        dialog.setContentView(root); dialog.show()
        video.setVideoPath(file.absolutePath)
    }
}
