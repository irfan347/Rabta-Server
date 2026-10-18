package com.irfan.rabta

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * WhatsApp's green "Tap to return to call" bar on Rabta's screens while a call goes on behind them. [hiddenHere] hides
 * it where the call itself is showing; [openHere] returns to the call without opening another screen when it can.
 */
class CallBar(private val a: Activity, private val hiddenHere: () -> Boolean = { false }, private val openHere: () -> Boolean = { false }) : CallWatcher {
    val view: TextView = RabtaUi.text(a, "", 14f, true, Color.WHITE).apply {
        gravity = Gravity.CENTER_VERTICAL; minHeight = RabtaUi.dp(a, 44); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(RabtaUi.dp(a, 16), RabtaUi.dp(a, 8), RabtaUi.dp(a, 16), RabtaUi.dp(a, 8)); compoundDrawablePadding = RabtaUi.dp(a, 10)
        setBackgroundColor(0xFF15803D.toInt()); visibility = View.GONE; isFocusable = true
        setOnClickListener { open() }
    }
    private val tick = object : Runnable { override fun run() { refresh(); if (view.visibility == View.VISIBLE) view.postDelayed(this, 1000) } }

    /** From onResume to onPause. */
    fun start() { VoiceCall.watch(this); VideoCall.watch(this); refresh() }
    fun stop() { VoiceCall.unwatch(this); VideoCall.unwatch(this); view.removeCallbacks(tick) }
    override fun callChanged() = refresh()

    private fun clock(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)

    fun refresh() {
        val (icon, words) = when {
            VoiceCall.active && !hiddenHere() -> "phone" to "${VoiceCall.peerName.ifBlank { "Voice call" }} · ${if (VoiceCall.connectedAt > 0) clock(VoiceCall.seconds()) else VoiceCall.status}"
            VideoCall.active && !VideoCall.shown -> "video" to "${VideoCall.peerName.ifBlank { "Video call" }} · ${if (VideoCall.connectedAt > 0) clock(VideoCall.seconds()) else VideoCall.status}"
            else -> null to ""
        }
        val showing = icon != null
        if (showing) {
            view.text = "$words · Tap to return to call"
            view.contentDescription = "Ongoing call with $words. Tap to return to the call"
            view.setCompoundDrawablesRelative(UiIcons.drawable(a, icon!!, Color.WHITE, 18), null, null, null)
        }
        val was = view.visibility == View.VISIBLE
        view.visibility = if (showing) View.VISIBLE else View.GONE
        if (showing && !was) { view.removeCallbacks(tick); view.postDelayed(tick, 1000) }
    }

    private fun open() {
        if (openHere()) return
        when {
            VoiceCall.active -> a.startActivity(Intent(a, ChatActivity::class.java)
                .putExtra("peerId", VoiceCall.peerId).putExtra("peerName", VoiceCall.peerName).putExtra("showCall", true))
            VideoCall.active -> a.startActivity(Intent(a, VideoCallActivity::class.java))
        }
    }
}
