package com.irfan.rabta

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import org.json.JSONObject
import org.webrtc.*

/**
 * The video call, kept apart from [VideoCallActivity] so it goes on in the small picture-in-picture window, behind
 * your chats and after that window is closed; the screen only shows it. It can also share your screen in place of
 * the camera. One call at a time, main thread only.
 */
object VideoCall : RabtaEvents.Listener {
    var peerId = ""; private set
    var peerName = ""; private set
    var callId = ""; private set
    var isCaller = false; private set
    var status = ""; private set
    var connectedAt = 0L; private set
    var muted = false; private set
    var speakerOn = true; private set
    var sharing = false; private set
    var active = false; private set
    /** The other phone took the call (caller: its answer arrived; callee: the answer went out). */
    private var answered = false
    private var cutOffPending = false
    /** A call screen is in view (full screen or picture-in-picture). */
    var shown = false; private set

    private var app: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val watchers = mutableListOf<CallWatcher>()
    private val ringback = CallSounds()
    private var logId = -1L
    private var outcome = "cancelled"
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false

    var egl: EglBase? = null; private set
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private val pendingIce = mutableListOf<IceCandidate>()
    private var helper: SurfaceTextureHelper? = null
    private var camera: CameraVideoCapturer? = null
    private var cameraOn = false
    private var frontCamera = true
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideo: VideoTrack? = null
    private var localAudio: AudioTrack? = null
    private var remoteVideo: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var localSink: VideoSink? = null
    private var remoteSink: VideoSink? = null
    private var screen: ScreenCapturerAndroid? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null

    fun watch(w: CallWatcher) { if (w !in watchers) watchers += w }
    fun unwatch(w: CallWatcher) { watchers -= w }
    private fun changed() { watchers.toList().forEach { it.callChanged() } }
    private fun status(s: String) { status = s; changed() }
    private fun toast(text: String) { app?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() } }
    fun seconds() = if (connectedAt == 0L) 0L else (SystemClock.elapsedRealtime() - connectedAt) / 1000

    private val ringTimeout = Runnable { if (active && connectedAt == 0L) { outcome = "no answer"; hangUp() } }
    // A dropped connection is not a hung-up call: once answered, the picture and voice go directly between the phones,
    // and the server holds the call for half a minute while Rabta reconnects. Still cut off after that, it ends here.
    private const val RECONNECT_MS = 30_000L
    private val cutOff = Runnable { cutOffPending = false; if (active && !WsClient.connected) { outcome = "disconnected"; end() } }

    /** Calls [peer] when [caller], otherwise answers [offerSdp]. The screen checks the camera and microphone first. */
    fun start(c: Context, peer: String, name: String, caller: Boolean, id: String, offerSdp: String?) {
        if (active) hangUp()
        if (VoiceCall.active) VoiceCall.end()
        VoicePlayer.callStarted()
        val ctx = c.applicationContext; app = ctx
        peerId = peer; peerName = name; callId = id; isCaller = caller
        connectedAt = 0L; muted = false; speakerOn = true; sharing = false; outcome = "cancelled"; frontCamera = true; pendingIce.clear(); answered = false
        logId = runCatching { Db.get(ctx).logCallStart(peer, name, if (caller) "outgoing video" else "incoming video") }.getOrDefault(-1L)
        runCatching {
            val am = ctx.getSystemService(AudioManager::class.java)
            previousMode = am.mode; previousSpeaker = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION; am.isSpeakerphoneOn = true
        }
        active = true
        RabtaEvents.add(this)
        // Picture-in-picture, a locked screen or other apps on top: MessageService keeps the microphone and the phone awake.
        MessageService.callStarted(ctx, peer, name, true)
        try { prepare(ctx) } catch (_: Throwable) { toast("Video call could not start on this phone"); outcome = "failed"; end(); return }
        status("Preparing connection…")
        Thread {
            val ice = runCatching {
                val servers = FeatureApi.request("/api/ice").getJSONArray("servers")
                (0 until servers.length()).map { i -> val s = servers.getJSONObject(i); val urls = s.getJSONArray("urls"); PeerConnection.IceServer.builder((0 until urls.length()).map { urls.getString(it) }).setUsername(s.optString("username")).setPassword(s.optString("credential")).createIceServer() }
            }.getOrElse { listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()) }
            handler.post {
                if (!active || callId != id) return@post
                connect(ice); handler.postDelayed(ringTimeout, 45000)
                if (caller) { status("Calling…"); offer() } else if (offerSdp != null) accept(offerSdp) else hangUp()
            }
        }.start()
    }

    private fun prepare(ctx: Context) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        val e = EglBase.create(); egl = e
        val f = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
            .createPeerConnectionFactory()
        factory = f
        helper = SurfaceTextureHelper.create("CamThread", e.eglBaseContext)
        val source = f.createVideoSource(false); videoSource = source
        // Filters and background blur work on the camera's frames before they are sent (see VideoEffects).
        VideoEffects.reset(); source.setVideoProcessor(VideoEffects)
        val enumerator = Camera2Enumerator(ctx)
        val camId = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull()
        if (camId != null) camera = Camera2Capturer(ctx, camId, null).apply { initialize(helper, ctx, source.capturerObserver) }
        localVideo = f.createVideoTrack("v0", source)
        audioSource = f.createAudioSource(MediaConstraints())
        localAudio = f.createAudioTrack("a0", audioSource!!)
    }

    private fun connect(ice: List<PeerConnection.IceServer>) {
        val f = factory ?: return
        val id = callId; val to = peerId
        val cfg = PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                WsClient.sendRaw(JSONObject().put("type", "ice_candidate").put("to", to).put("callId", id)
                    .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("sdp", c.sdp).toString())
            }
            override fun onTrack(t: RtpTransceiver) {
                val track = t.receiver.track() as? VideoTrack ?: return
                handler.post { if (active && callId == id) { remoteVideo = track; remoteSink?.let { track.addSink(it) }; changed() } }
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                handler.post {
                    if (!active || callId != id) return@post
                    when (s) {
                        PeerConnection.PeerConnectionState.CONNECTED -> { ringback.stop(); handler.removeCallbacks(ringTimeout); if (connectedAt == 0L) connectedAt = SystemClock.elapsedRealtime(); status("Connected") }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> status("Reconnecting…")
                        PeerConnection.PeerConnectionState.FAILED -> { toast("Connection failed"); hangUp() }
                        else -> {}
                    }
                }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(a: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        })
        val p = pc ?: return
        localVideo?.let { videoSender = p.addTrack(it, listOf("s0")) }
        localAudio?.let { p.addTrack(it, listOf("s0")) }
    }

    private fun flushIce() { handler.post { val p = pc ?: return@post; if (p.remoteDescription != null) { pendingIce.forEach { p.addIceCandidate(it) }; pendingIce.clear() } } }

    private fun sdpObserver(onSet: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(s: SessionDescription) {}
        override fun onSetSuccess() { onSet() }
        override fun onCreateFailure(e: String) { handler.post { if (active) status("Couldn't connect the call") } }
        override fun onSetFailure(e: String) { handler.post { if (active) status("Couldn't connect the call") } }
    }

    private fun offer() {
        val p = pc ?: return; val id = callId; val to = peerId
        val mc = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
        }
        p.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                p.setLocalDescription(sdpObserver(onSet = {
                    WsClient.sendRaw(JSONObject().put("type", "video_offer").put("to", to).put("callId", id)
                        .put("fromName", Session.name).put("sdp", sdp.description).toString())
                    handler.post { if (active && callId == id) status("Calling…") }
                }), sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String) { handler.post { if (active) status("Couldn't connect the call") } }
            override fun onSetFailure(e: String) {}
        }, mc)
    }

    private fun accept(offerSdp: String) {
        val p = pc ?: return; val id = callId; val to = peerId
        p.setRemoteDescription(sdpObserver(onSet = {
            flushIce()
            p.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    p.setLocalDescription(sdpObserver(onSet = {
                        WsClient.sendRaw(JSONObject().put("type", "video_answer").put("to", to).put("callId", id).put("sdp", sdp.description).toString())
                        handler.post { if (active && callId == id) { answered = true; status("Connecting…") } }
                    }), sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(e: String) { handler.post { if (active) status("Couldn't connect the call") } }
                override fun onSetFailure(e: String) {}
            }, MediaConstraints())
        }), SessionDescription(SessionDescription.Type.OFFER, offerSdp))
    }

    /** A call screen shows the call in [local] (your camera) and [remote]. */
    fun attach(local: VideoSink, remote: VideoSink) {
        if (localSink !== local) { localSink?.let { localVideo?.removeSink(it) }; localSink = local; localVideo?.addSink(local) }
        if (remoteSink !== remote) { remoteSink?.let { remoteVideo?.removeSink(it) }; remoteSink = remote; remoteVideo?.addSink(remote) }
    }
    fun detach(local: VideoSink, remote: VideoSink) {
        if (localSink === local) { localVideo?.removeSink(local); localSink = null }
        if (remoteSink === remote) { remoteVideo?.removeSink(remote); remoteSink = null }
    }

    /** The call screen came into view or left it. Android lends the camera only to a screen in view; a shared screen goes on. */
    fun visible(on: Boolean) {
        if (!active) return
        shown = on
        if (!sharing) camera(on)
        changed()
    }

    private fun camera(on: Boolean) {
        val cam = camera ?: return
        if (on == cameraOn) return
        try { if (on) cam.startCapture(640, 480, 24) else cam.stopCapture(); cameraOn = on } catch (_: Exception) {}
    }

    fun flipCamera(c: Context) {
        val cam = camera as? Camera2Capturer ?: return
        frontCamera = !frontCamera
        val enumerator = Camera2Enumerator(c)
        val id = if (frontCamera) enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } else enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        id?.let { cam.switchCamera(null, it) }
    }

    fun toggleMute() { if (!active) return; muted = !muted; localAudio?.setEnabled(!muted); changed() }
    fun toggleSpeaker() {
        if (!active) return
        speakerOn = !speakerOn
        runCatching { app?.getSystemService(AudioManager::class.java)?.isSpeakerphoneOn = speakerOn }
        changed()
    }

    /**
     * Sends the screen in place of the camera, with Android's consent in [permission]. MessageService must already
     * hold its screen-sharing type ([MessageService.sharingStarted]); false if sharing could not start.
     */
    fun share(c: Context, permission: Intent): Boolean {
        val f = factory ?: return false; val e = egl ?: return false; val sender = videoSender ?: return false
        if (!active || sharing) return false
        val h = SurfaceTextureHelper.create("ScreenThread", e.eglBaseContext)
        val source = f.createVideoSource(true)
        val capturer = ScreenCapturerAndroid(permission, object : MediaProjection.Callback() {
            // Stopped from Android's own controls, or the screen is no longer shared.
            override fun onStop() { handler.post { stopSharing() } }
        })
        val m = c.resources.displayMetrics
        val scale = minOf(1f, 1280f / maxOf(m.widthPixels, m.heightPixels))
        return try {
            capturer.initialize(h, c.applicationContext, source.capturerObserver)
            capturer.startCapture((m.widthPixels * scale).toInt() / 2 * 2, (m.heightPixels * scale).toInt() / 2 * 2, 15)
            val track = f.createVideoTrack("screen0", source)
            sender.setTrack(track, false)
            screen = capturer; screenHelper = h; screenSource = source; screenTrack = track
            sharing = true; camera(false)
            changed()
            true
        } catch (_: Exception) {
            runCatching { capturer.dispose() }; runCatching { source.dispose() }; runCatching { h.dispose() }
            false
        }
    }

    fun stopSharing() {
        if (!sharing) return
        sharing = false
        runCatching { videoSender?.setTrack(localVideo, false) }
        runCatching { screen?.stopCapture() }; runCatching { screen?.dispose() }
        runCatching { screenTrack?.dispose() }; runCatching { screenSource?.dispose() }; runCatching { screenHelper?.dispose() }
        screen = null; screenTrack = null; screenSource = null; screenHelper = null
        MessageService.sharingEnded()
        if (active && shown) camera(true)
        changed()
    }

    fun hangUp() {
        if (!active) return
        runCatching { WsClient.sendRaw(JSONObject().put("type", "video_end").put("to", peerId).put("callId", callId).toString()) }
        end()
    }

    /** Ends the call here; the other phone is told by [hangUp] or has already ended it. */
    fun end() {
        if (!active) return
        stopSharing()
        active = false; shown = false
        RabtaEvents.remove(this)
        ringback.stop(); handler.removeCallbacks(ringTimeout); handler.removeCallbacks(cutOff); cutOffPending = false
        app?.let { ctx ->
            MessageService.callEnded(ctx)
            if (logId > 0) runCatching { val db = Db.get(ctx); if (connectedAt > 0) db.logCallEnd(logId, seconds().toInt()) else db.callOutcome(logId, "$outcome · video") }
            runCatching { ctx.getSystemService(AudioManager::class.java).apply { isSpeakerphoneOn = previousSpeaker; mode = previousMode } }
        }
        logId = -1L
        changed()   // the screen lets go of its views while the tracks still exist
        localSink?.let { runCatching { localVideo?.removeSink(it) } }; remoteSink?.let { runCatching { remoteVideo?.removeSink(it) } }
        localSink = null; remoteSink = null
        runCatching { if (cameraOn) camera?.stopCapture() }; cameraOn = false
        runCatching { pc?.dispose() }; pc = null; videoSender = null; remoteVideo = null
        runCatching { camera?.dispose() }; camera = null
        runCatching { localVideo?.dispose() }; localVideo = null
        runCatching { localAudio?.dispose() }; localAudio = null
        runCatching { videoSource?.setVideoProcessor(null) }; VideoEffects.reset()
        runCatching { videoSource?.dispose() }; videoSource = null
        runCatching { audioSource?.dispose() }; audioSource = null
        runCatching { helper?.dispose() }; helper = null
        runCatching { factory?.dispose() }; factory = null
        runCatching { egl?.release() }; egl = null
        pendingIce.clear(); connectedAt = 0L; status = ""
        RabtaEvents.postHistoryChanged(peerId)   // the call joins the chat's timeline
    }

    override fun onCallState(fromId: String, callId: String, state: String) {
        if (callId != this.callId || fromId != peerId || !isCaller || connectedAt != 0L) return
        when (state) {
            "calling" -> { ringback.stop(); status("Calling…") }
            "ringing" -> { ringback.ringing(); status("Ringing…") }
            "accepted" -> { ringback.stop(); status("Connecting…") }
            "busy" -> { outcome = "busy"; toast("Busy"); end() }
        }
    }
    override fun onDisconnected() {
        if (!active || WsClient.connected) return
        if (!answered) { outcome = "disconnected"; end(); return }
        if (!cutOffPending) { cutOffPending = true; handler.postDelayed(cutOff, RECONNECT_MS) }
    }
    override fun onConnected() {
        if (!active) return
        handler.removeCallbacks(cutOff); cutOffPending = false
        // Connected again: the server keeps the call once it hears it goes on (or tells this phone it ended meanwhile).
        if (answered) WsClient.sendRaw(JSONObject().put("type", "call_alive").put("to", peerId).put("callId", callId).toString())
    }
    override fun onVideoAnswer(callId: String, sdp: String) {
        if (callId != this.callId) return
        answered = true; ringback.stop(); status("Connecting…")
        pc?.setRemoteDescription(sdpObserver(onSet = { flushIce() }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }
    override fun onIceCandidateEvent(callId: String, sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        if (callId != this.callId) return
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
        val p = pc
        if (p?.remoteDescription != null) p.addIceCandidate(candidate) else if (pendingIce.size < 64) pendingIce.add(candidate)
    }
    override fun onVideoEnd(callId: String) {
        if (callId != this.callId) return
        outcome = "ended before answer"; end()
    }
}
