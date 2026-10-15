package com.irfan.rabta

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import org.webrtc.*

class VideoCallActivity : ThemedActivity(), RabtaEvents.Listener {

    companion object {
        const val PEER_ID    = "peerId"
        const val PEER_NAME  = "peerName"
        const val IS_CALLER  = "isCaller"
        const val CALL_ID    = "callId"
        const val OFFER_SDP  = "offerSdp"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var peerId: String
    private lateinit var peerName: String
    private lateinit var callId: String
    private var isCaller = false

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var localView: SurfaceViewRenderer? = null
    private var remoteView: SurfaceViewRenderer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var muted = false
    private var useFrontCamera = true
    private var connectedAt=0L
    private var speakerOn=true
    private var previousAudioMode=android.media.AudioManager.MODE_NORMAL
    private var previousSpeaker=false
    private lateinit var speakerBtn: TextView
    private val durationTick=object:Runnable {
        override fun run() {
            if(connectedAt==0L || isFinishing) return
            val secs=(android.os.SystemClock.elapsedRealtime()-connectedAt)/1000
            statusTv.text="Connected · %d:%02d".format(secs/60,secs%60)
            handler.postDelayed(this,1000)
        }
    }

    private lateinit var statusTv: TextView
    private lateinit var muteBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        Session.init(this)
        peerId   = intent.getStringExtra(PEER_ID)   ?: ""; if (peerId.isEmpty()) { finish(); return }
        peerName = intent.getStringExtra(PEER_NAME) ?: "User"
        callId   = intent.getStringExtra(CALL_ID)   ?: System.currentTimeMillis().toString()
        isCaller = intent.getBooleanExtra(IS_CALLER, false)
        if (!hasPerms()) { askPerms(); return }
        begin()
    }

    override fun onDestroy() { RabtaEvents.remove(this); cleanup(); super.onDestroy() }

    private fun hasPerms() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPerms() = ActivityCompat.requestPermissions(this,
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        if (g.size >= 2 && g.all { it == PackageManager.PERMISSION_GRANTED }) begin()
        else { Toast.makeText(this, "Camera aur mic chahiye video call ke liye", Toast.LENGTH_SHORT).show(); finish() }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun oval(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        remoteView = SurfaceViewRenderer(this)
        root.addView(remoteView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        localView = SurfaceViewRenderer(this).apply { setZOrderMediaOverlay(true) }
        root.addView(localView, FrameLayout.LayoutParams(dp(110), dp(160)).apply {
            gravity = Gravity.TOP or Gravity.END; topMargin = dp(56); marginEnd = dp(16) })

        // Name + status (center)
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        info.addView(TextView(this).apply { text = peerName; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        statusTv = TextView(this).apply { text = "Connecting..."; textSize = 14f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER }
        info.addView(statusTv)
        root.addView(info, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER; bottomMargin = dp(160) })

        // Controls
        val ctrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun cBtn(icon: String, bg: Int, sz: Int, act: () -> Unit) = TextView(this).apply {
            UiIcons.set(this,UiIcons.fromSymbol(icon) ?: "phone"); textSize = 24f; gravity = Gravity.CENTER; background = oval(bg)
            isClickable = true; setOnClickListener { act() }
        }
        muteBtn = cBtn("🎤", 0x99444444.toInt(), dp(60)) { toggleMute() }
        val endBtn = cBtn("📵", 0xFFD32F2F.toInt(), dp(70)) { hangUp() }
        val flipBtn = cBtn("🔄", 0x99444444.toInt(), dp(60)) { flipCamera() }
        speakerBtn=cBtn("🔊",0xFF128C7E.toInt(),dp(60)) { toggleSpeaker() }
        listOf(muteBtn,endBtn,flipBtn,speakerBtn).forEach { ctrl.addView(it,LinearLayout.LayoutParams(dp(56),dp(56)).apply { marginStart=dp(8); marginEnd=dp(8) }) }
        muteBtn.contentDescription="Mute microphone"; endBtn.contentDescription="End call"; flipBtn.contentDescription="Switch camera"; speakerBtn.contentDescription="Speaker on"
        root.addView(ctrl, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM; bottomMargin = dp(48) })
    }

    private fun begin() {
        buildUi()
        val am=getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        previousAudioMode=am.mode; previousSpeaker=am.isSpeakerphoneOn
        am.mode=android.media.AudioManager.MODE_IN_COMMUNICATION; am.isSpeakerphoneOn=true
        RabtaEvents.add(this)
        initWebRtc()
        createPeerConnection()
        if (isCaller) createAndSendOffer()
        else { val sdp = intent.getStringExtra(OFFER_SDP); if (sdp != null) acceptOffer(sdp) else finish() }
    }

    private fun initWebRtc() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
        eglBase = EglBase.create()
        localView?.init(eglBase!!.eglBaseContext, null)
        remoteView?.init(eglBase!!.eglBaseContext, null)
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .createPeerConnectionFactory()
        surfaceHelper = SurfaceTextureHelper.create("CamThread", eglBase!!.eglBaseContext)
        videoSource = factory!!.createVideoSource(false)
        val enumerator = Camera2Enumerator(this)
        val camId = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return
        capturer = Camera2Capturer(this, camId, null).apply {
            initialize(surfaceHelper, applicationContext, videoSource!!.capturerObserver)
            startCapture(640, 480, 24)
        }
        localVideoTrack = factory!!.createVideoTrack("v0", videoSource!!)
        localVideoTrack!!.addSink(localView!!)
        audioSource = factory!!.createAudioSource(MediaConstraints())
        localAudioTrack = factory!!.createAudioTrack("a0", audioSource!!)
    }

    private fun createPeerConnection() {
        val ice = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val cfg = PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        pc = factory!!.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                WsClient.sendRaw(JSONObject().put("type","ice_candidate").put("to",peerId).put("callId",callId)
                    .put("sdpMid",c.sdpMid).put("sdpMLineIndex",c.sdpMLineIndex).put("sdp",c.sdp).toString())
            }
            override fun onTrack(t: RtpTransceiver) {
                val track = t.receiver.track() ?: return
                if (track is VideoTrack) handler.post { remoteView?.let { track.addSink(it) } }
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                handler.post {
                    if(isFinishing) return@post
                    when(s) {
                        PeerConnection.PeerConnectionState.CONNECTED -> { if(connectedAt==0L) connectedAt=android.os.SystemClock.elapsedRealtime(); handler.removeCallbacks(durationTick); handler.post(durationTick) }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> { handler.removeCallbacks(durationTick); statusTv.text="Reconnecting…" }
                        PeerConnection.PeerConnectionState.FAILED -> { Toast.makeText(this@VideoCallActivity,"Connection failed",Toast.LENGTH_SHORT).show(); hangUp() }
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
        val stream = "s0"
        pc!!.addTrack(localVideoTrack!!, listOf(stream))
        pc!!.addTrack(localAudioTrack!!, listOf(stream))
    }

    private fun sdpObserver(onSet: () -> Unit = {}, onCreate: (SessionDescription) -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(s: SessionDescription) { onCreate(s) }
        override fun onSetSuccess() { onSet() }
        override fun onCreateFailure(e: String) { handler.post { if (!isFinishing) statusTv.text = "SDP error: $e" } }
        override fun onSetFailure(e: String) { handler.post { if (!isFinishing) statusTv.text = "SDP fail: $e" } }
    }

    private fun createAndSendOffer() {
        val mc = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo","true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio","true")
        }
        pc!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc!!.setLocalDescription(sdpObserver(onSet = {
                    WsClient.sendRaw(JSONObject().put("type","video_offer").put("to",peerId).put("callId",callId)
                        .put("fromName",Session.name).put("sdp",sdp.description).toString())
                    handler.post { statusTv.text = "Ring ho rahi hai..." }
                }), sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String) { handler.post { statusTv.text = "Error: $e" } }
            override fun onSetFailure(e: String) {}
        }, mc)
    }

    private fun acceptOffer(offerSdp: String) {
        pc!!.setRemoteDescription(sdpObserver(onSet = {
            pc!!.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc!!.setLocalDescription(sdpObserver(onSet = {
                        WsClient.sendRaw(JSONObject().put("type","video_answer").put("to",peerId).put("callId",callId).put("sdp",sdp.description).toString())
                        handler.post { statusTv.text = "Connecting..." }
                    }), sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(e: String) { handler.post { statusTv.text = "Error: $e" } }
                override fun onSetFailure(e: String) {}
            }, MediaConstraints())
        }), SessionDescription(SessionDescription.Type.OFFER, offerSdp))
    }

    private fun toggleMute() {
        muted = !muted; localAudioTrack?.setEnabled(!muted)
        UiIcons.set(muteBtn,if(muted) "muted" else "mic",label=if(muted) "Unmute microphone" else "Mute microphone")
        muteBtn.background=oval(if(muted) 0xFF128C7E.toInt() else 0x99444444.toInt()); muteBtn.isSelected=muted
    }
    private fun toggleSpeaker() {
        speakerOn=!speakerOn
        (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).isSpeakerphoneOn=speakerOn
        speakerBtn.background=oval(if(speakerOn) 0xFF128C7E.toInt() else 0x99444444.toInt()); speakerBtn.isSelected=speakerOn
        speakerBtn.contentDescription=if(speakerOn) "Speaker on" else "Speaker off"
    }

    private fun flipCamera() {
        useFrontCamera = !useFrontCamera
        val enumerator = Camera2Enumerator(this)
        val id = if (useFrontCamera) enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                 else enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        id?.let { (capturer as? Camera2Capturer)?.switchCamera(null, it) }
    }

    private fun hangUp() {
        try { WsClient.sendRaw(JSONObject().put("type","video_end").put("to",peerId).put("callId",callId).toString()) } catch (_: Exception) {}
        cleanup(); finish()
    }

    private fun cleanup() {
        handler.removeCallbacks(durationTick)
        if(::speakerBtn.isInitialized) try { (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).apply { isSpeakerphoneOn=previousSpeaker; mode=previousAudioMode } } catch (_:Exception) {}
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}; capturer = null
        try { localVideoTrack?.dispose() } catch (_: Exception) {}; localVideoTrack = null
        try { localAudioTrack?.dispose() } catch (_: Exception) {}; localAudioTrack = null
        try { audioSource?.dispose() } catch (_: Exception) {}; audioSource = null
        try { videoSource?.dispose() } catch (_: Exception) {}; videoSource = null
        try { pc?.close() } catch (_: Exception) {}; pc = null
        try { surfaceHelper?.dispose() } catch (_: Exception) {}; surfaceHelper = null
        try { localView?.release() } catch (_: Exception) {}; localView = null
        try { remoteView?.release() } catch (_: Exception) {}; remoteView = null
        try { factory?.dispose() } catch (_: Exception) {}; factory = null
        try { eglBase?.release() } catch (_: Exception) {}; eglBase = null
    }

    // RabtaEvents
    override fun onVideoAnswer(callId: String, sdp: String) {
        if (callId != this.callId) return
        pc?.setRemoteDescription(sdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }
    override fun onIceCandidateEvent(callId: String, sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        if (callId != this.callId) return
        pc?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
    }
    override fun onVideoEnd(callId: String) {
        if (callId != this.callId) return
        handler.post { cleanup(); finish() }
    }
}
