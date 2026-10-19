package com.irfan.rabta

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import java.security.SecureRandom

/** A screen showing a call; told whenever the call changes. Main thread only. */
fun interface CallWatcher { fun callChanged() }

/**
 * The voice call, kept apart from the chat screen so it goes on while you go back to your chats, open another chat
 * or leave Rabta. [ChatActivity] only shows it, and [CallBar] leads back to it. One call at a time, main thread only.
 */
object VoiceCall : RabtaEvents.Listener {
    var peerId = ""; private set
    var peerName = ""; private set
    var status = ""; private set
    var connectedAt = 0L; private set
    var muted = false; private set
    var speaker = false; private set
    private var call: InternetVoiceCall? = null
    private var logId = -1L
    private var app: Context? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private val handler = Handler(Looper.getMainLooper())
    private val watchers = mutableListOf<CallWatcher>()

    val active get() = call != null
    val id get() = call?.id.orEmpty()
    fun with(peer: String) = active && peerId == peer
    fun seconds() = if (connectedAt == 0L) 0L else (SystemClock.elapsedRealtime() - connectedAt) / 1000

    fun watch(w: CallWatcher) { if (w !in watchers) watchers += w }
    fun unwatch(w: CallWatcher) { watchers -= w }
    private fun changed() { watchers.toList().forEach { it.callChanged() } }
    private fun toast(text: String) { app?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() } }

    private val noAnswer = Runnable { if (active && connectedAt == 0L) { end(outcome = "no answer"); toast("Call ka jawab nahi mila") } }
    // A dropped connection is not a hung-up call: the server holds an answered call for half a minute, and the voice
    // comes back once Rabta reconnects. A call that never connected, or one still cut off after that, ends here.
    private const val RECONNECTING = "Reconnecting…"
    private const val RECONNECT_MS = 30_000L
    private val cutOff = Runnable { if (active && !WsClient.connected) { end(false, "disconnected"); toast("Call disconnected") } }

    /** Calls [peer], or with [initiate] false answers the call [voiceId]. The screen checks the microphone and connection first. */
    fun start(c: Context, peer: String, name: String, initiate: Boolean, voiceId: String) {
        if (active) end()   // answering another call ends this one, like a phone
        if (VideoCall.active) VideoCall.hangUp()
        val ctx = c.applicationContext; app = ctx
        peerId = peer; peerName = name; connectedAt = 0L; muted = false; speaker = false
        runCatching {
            val am = ctx.getSystemService(AudioManager::class.java)
            previousMode = am.mode; previousSpeaker = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION; am.isSpeakerphoneOn = false
        }
        // The screen may turn off, lock or show something else: MessageService keeps the microphone and the phone awake.
        MessageService.callStarted(ctx, peer, name, false)
        logId = runCatching { Db.get(ctx).logCallStart(peer, name, if (initiate) "outgoing" else "incoming") }.getOrDefault(-1L)
        val id = if (initiate) ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) } else voiceId
        status = if (initiate) "Calling…" else "Connecting…"
        RabtaEvents.add(this)
        val net = InternetVoiceCall(peer, id) { s ->
            if (active) {
                status = s
                if (s.startsWith("Connected") && connectedAt == 0L) { connectedAt = SystemClock.elapsedRealtime(); handler.removeCallbacks(noAnswer) }
                changed()
            }
        }
        call = net; net.start(initiate)
        handler.postDelayed(noAnswer, 45_000)
        changed()
    }

    /** [sendEnd] false when the other phone hung up first. */
    fun end(sendEnd: Boolean = true, outcome: String = "cancelled") {
        val net = call ?: return
        call = null; net.stop(sendEnd)
        RabtaEvents.remove(this); handler.removeCallbacks(noAnswer); handler.removeCallbacks(cutOff)
        app?.let { ctx ->
            MessageService.callEnded(ctx)
            val seconds = seconds().toInt()
            if (logId > 0) runCatching { val db = Db.get(ctx); db.logCallEnd(logId, seconds); if (connectedAt == 0L) db.callOutcome(logId, outcome) }
            runCatching { ctx.getSystemService(AudioManager::class.java).apply { isSpeakerphoneOn = previousSpeaker; mode = previousMode } }
        }
        logId = -1L; connectedAt = 0L; status = ""
        RabtaEvents.postHistoryChanged(peerId)   // the call joins the chat's timeline
        changed()
    }

    fun toggleMute() { val net = call ?: return; muted = !muted; net.muted = muted; changed() }
    fun toggleSpeaker() {
        if (!active) return
        speaker = !speaker
        runCatching { app?.getSystemService(AudioManager::class.java)?.isSpeakerphoneOn = speaker }
        changed()
    }

    override fun onCallAudio(fromId: String, data: String, callId: String) {
        // Audio alone must never answer a call: only the call already going on hears it.
        if (fromId == peerId && callId == id) call?.onAudio(data)
    }
    override fun onCallEnd(fromId: String, callId: String) {
        if (fromId == peerId && callId == id) { end(false, "ended before answer"); toast("Call khatam") }
    }
    override fun onCallState(fromId: String, callId: String, state: String) {
        if (fromId != peerId || callId != id) return
        when (state) {
            "busy" -> { end(false, "busy"); toast("Busy") }
            // The other phone's connection dropped for a moment, and came back.
            "reconnecting" -> if (connectedAt > 0) { status = RECONNECTING; changed() }
            "back" -> if (connectedAt > 0 && status == RECONNECTING) { status = "Connected"; changed() }
            else -> call?.state(state)
        }
    }
    override fun onDisconnected() {
        if (!active || WsClient.connected) return
        if (connectedAt == 0L) { end(false); toast("Call disconnected"); return }
        if (status == RECONNECTING) return
        status = RECONNECTING; handler.postDelayed(cutOff, RECONNECT_MS); changed()
    }
    override fun onConnected() {
        if (!active || connectedAt == 0L) return
        handler.removeCallbacks(cutOff)
        // Connected again: the server keeps the call once it hears it goes on (or tells this phone it ended meanwhile).
        WsClient.sendRaw(org.json.JSONObject().put("type", "call_alive").put("to", peerId).put("callId", id).toString())
        if (status == RECONNECTING) { status = "Connected"; changed() }
    }
}
