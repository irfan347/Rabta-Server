package com.irfan.rabta

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Plays one voice message at a time for the whole app, as WhatsApp does: starting another stops the one playing (it
 * resumes where it was left), and it goes on when you leave its chat, shown by a [VoiceBar] with pause and stop.
 * Main thread only.
 */
object VoicePlayer {
    /** The chat a voice message is in, to return to it from the bar. */
    data class Origin(val chat: String, val chatName: String, val group: Boolean)
    fun interface Listener { fun voiceChanged() }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var generation = 0
    /** Where each note left off when another one started. */
    private val leftAt = HashMap<String, Int>()
    /** The message playing or paused ("" when none), who sent it and where. */
    var key = ""; private set
    var sender = ""; private set
    var origin: Origin? = null; private set
    var loading = false; private set
    var prepared = false; private set
    /** The note that could not be played last. */
    var failedKey = ""; private set
    val playing get() = prepared && runCatching { player?.isPlaying == true }.getOrDefault(false)
    val position get() = if (prepared) runCatching { player?.currentPosition ?: 0 }.getOrDefault(0) else 0
    val duration get() = if (prepared) runCatching { player?.duration ?: 0 }.getOrDefault(0) else 0
    fun leftAt(id: String): Int? = leftAt[id]

    private val tick = object : Runnable { override fun run() { changed(); if (playing) main.postDelayed(this, 100) } }
    fun watch(l: Listener) { listeners.add(l) }
    fun unwatch(l: Listener) { listeners.remove(l) }
    private fun changed() { listeners.toList().forEach { it.voiceChanged() } }

    /** Plays, pauses or resumes message [id]. */
    fun toggle(c: Context, id: String, body: String, sender: String, origin: Origin?) {
        if (id == key && (loading || prepared)) { if (prepared) { if (playing) pause() else resume() }; return }
        stop()
        key = id; this.sender = sender; this.origin = origin; loading = true; failedKey = ""
        val version = ++generation; val app = c.applicationContext
        changed()
        Thread {
            val made = runCatching { VoiceAudio.tempFile(app, body) }
            main.post {
                if (version != generation) { made.getOrNull()?.delete(); return@post }
                made.onSuccess { f ->
                    file = f
                    try {
                        val p = MediaPlayer(); player = p
                        p.setOnPreparedListener {
                            if (version != generation) return@setOnPreparedListener
                            loading = false; prepared = true
                            leftAt.remove(id)?.let { at -> if (at in 1 until p.duration) p.seekTo(at) }
                            p.start(); main.removeCallbacks(tick); main.post(tick); changed()
                        }
                        p.setOnCompletionListener { if (version == generation) halt(false) }
                        p.setOnErrorListener { _, _, _ -> if (version == generation) fail(); true }
                        p.setDataSource(f.absolutePath); p.prepareAsync()
                    } catch (_: Exception) { fail() }
                }.onFailure { fail() }
            }
        }.start()
    }
    fun pause() { val p = player ?: return; if (prepared && p.isPlaying) { p.pause(); main.removeCallbacks(tick); changed() } }
    fun resume() { val p = player ?: return; if (prepared && !p.isPlaying) { p.start(); main.removeCallbacks(tick); main.post(tick); changed() } }
    fun seekTo(ms: Int) { if (prepared) { player?.seekTo(ms); changed() } }
    /** A call starts: the voice message playing stops, as on a phone (callable from any thread). */
    fun callStarted() { if (Looper.myLooper() == Looper.getMainLooper()) stop() else main.post { stop() } }
    /** Stops the note playing (another one starts, a call, the bar's stop); it resumes where it was next time. */
    fun stop() = halt(true)
    private fun halt(remember: Boolean) {
        if (key.isNotEmpty()) { if (remember && prepared) position.takeIf { it > 0 }?.let { leftAt[key] = it } else if (!remember) leftAt.remove(key) }
        generation++; main.removeCallbacks(tick)
        player?.let { runCatching { it.release() } }; player = null
        file?.delete(); file = null
        val had = key.isNotEmpty()
        key = ""; sender = ""; origin = null; loading = false; prepared = false
        if (had) changed()
    }
    private fun fail() { val id = key; halt(false); failedKey = id; changed() }
}

/**
 * WhatsApp's bar for a voice message playing outside its chat: who sent it and how far it is, pause or play, and
 * stop; tap to go back to its chat. [hiddenFor] is the chat on screen, where the message's own bubble shows it.
 */
class VoiceBar(private val a: Activity, private val hiddenFor: () -> String = { "" }) : VoicePlayer.Listener {
    private fun dp(n: Int) = RabtaUi.dp(a, n)
    private val words: TextView = RabtaUi.text(a, "", 14f, true, AppTheme.text(a)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val toggle: ImageView = UiIcons.image(a, "pause", AppTheme.accent(a), 24, "Pause voice message").apply {
        isFocusable = true; background = RabtaUi.ripple(a, Color.TRANSPARENT, 999)
        setOnClickListener { if (VoicePlayer.playing) VoicePlayer.pause() else VoicePlayer.resume() }
    }
    val view: LinearLayout = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48); setPadding(dp(16), 0, dp(4), 0); visibility = View.GONE
        background = RabtaUi.ripple(a, AppTheme.raised(a), 0); isFocusable = true
        addView(UiIcons.image(a, "mic", AppTheme.accent(a), 20, "").apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        addView(toggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(UiIcons.image(a, "close", AppTheme.sub(a), 22, "Stop voice message").apply {
            isFocusable = true; background = RabtaUi.ripple(a, Color.TRANSPARENT, 999); setOnClickListener { VoicePlayer.stop() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        setOnClickListener { open() }
    }
    /** From onResume to onPause. */
    fun start() { VoicePlayer.watch(this); refresh() }
    fun stop() { VoicePlayer.unwatch(this) }
    override fun voiceChanged() = refresh()

    fun refresh() {
        val origin = VoicePlayer.origin
        val showing = VoicePlayer.key.isNotEmpty() && origin?.chat != hiddenFor()
        view.visibility = if (showing) View.VISIBLE else View.GONE
        if (!showing) return
        val who = VoicePlayer.sender.ifBlank { L.t(a, "Voice message") }
        words.text = if (VoicePlayer.loading) who else "$who · ${VoiceAudio.format(VoicePlayer.position.toLong())}"
        val playing = VoicePlayer.playing || VoicePlayer.loading
        toggle.setImageDrawable(UiIcons.drawable(a, if (playing) "pause" else "play", AppTheme.accent(a), 24))
        toggle.contentDescription = if (playing) "Pause voice message" else "Play voice message"
        view.contentDescription = "Voice message from $who${if (origin != null) " in ${origin.chatName}" else ""}. Tap to return to the chat"
    }

    private fun open() {
        val o = VoicePlayer.origin ?: return
        a.startActivity(Intent(a, ChatActivity::class.java).putExtra("peerId", o.chat).putExtra("peerName", o.chatName).putExtra("isGroup", o.group))
    }
}
