package com.irfan.rabta

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Shows the [VideoCall]. Back or Home turns it into a small picture-in-picture window so you can use your chats
 * while the call goes on; closing that window leaves the call going, with the green bar and its notice to return.
 * The call has a task of its own (see the manifest), so back leads to the chat with that person explicitly.
 */
class VideoCallActivity : ThemedActivity(), CallWatcher {

    companion object {
        const val PEER_ID    = "peerId"
        const val PEER_NAME  = "peerName"
        const val IS_CALLER  = "isCaller"
        const val CALL_ID    = "callId"
        const val OFFER_SDP  = "offerSdp"
        private const val SHARE = 41
    }

    private val handler = Handler(Looper.getMainLooper())
    private var localView: SurfaceViewRenderer? = null
    private var remoteView: SurfaceViewRenderer? = null
    private lateinit var statusTv: TextView
    /** Name and call time: at the top, out of the way of the face, and only for a moment once the call connects. */
    private lateinit var info: LinearLayout
    private var infoForConnected = false
    private val fadeInfo = Runnable { if (connected()) showInfo(false) }
    private lateinit var muteBtn: TextView
    private lateinit var speakerBtn: TextView
    private lateinit var shareBtn: TextView
    private lateinit var controls: LinearLayout
    /** Filters and background blur for your camera: a button under your preview opens a strip of live previews. */
    private lateinit var effectsBtn: android.widget.ImageView
    private lateinit var effects: android.widget.HorizontalScrollView
    private val tiles = mutableMapOf<String, android.widget.ImageView>()
    private lateinit var sharingNote: LinearLayout
    private var pending: Intent? = null
    private var askingShare = false
    private var started = false
    private val tick = Runnable { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Like WhatsApp, back shows the chat with that person, under the call's small window where this phone has one.
        onBack { if (!minimize()) finish(); showChat(); true }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        Session.init(this)
        // Brought back after the call ended (e.g. from Recents): never place the call again by itself.
        if (savedInstanceState != null && !VideoCall.active) { finish(); return }
        open(intent)
    }

    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next)
        setIntent(next)
        open(next)
    }

    /** A new call when the intent names one; otherwise back to the call going on. */
    private fun open(i: Intent) {
        val peer = i.getStringExtra(PEER_ID).orEmpty()
        val id = i.getStringExtra(CALL_ID).orEmpty()
        if (peer.isEmpty() || (VideoCall.active && VideoCall.callId == id)) {
            if (VideoCall.active) attach() else finish()
            return
        }
        if (!hasPerms()) { pending = i; askPerms(); return }
        begin(i)
    }

    private fun begin(i: Intent) {
        releaseViews()
        VideoCall.start(this, i.getStringExtra(PEER_ID).orEmpty(), i.getStringExtra(PEER_NAME) ?: "User", i.getBooleanExtra(IS_CALLER, false),
            i.getStringExtra(CALL_ID) ?: System.currentTimeMillis().toString(), i.getStringExtra(OFFER_SDP))
        attach()
    }

    private fun attach() {
        val egl = VideoCall.egl ?: run { finish(); return }
        releaseViews()
        buildUi()
        localView?.init(egl.eglBaseContext, null)
        remoteView?.init(egl.eglBaseContext, remoteEvents)
        VideoCall.attach(localView!!, remoteView!!)
        VideoCall.watch(this)
        if (started) VideoCall.visible(true)
        pip(isInPictureInPictureMode)
        render()
    }

    /** Your own shared phone screen is tall: show all of it rather than filling the view. */
    private val remoteEvents = object : RendererCommon.RendererEvents {
        override fun onFirstFrameRendered() {}
        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
            val ratio = if (rotation % 180 == 0) height.toFloat() / width.coerceAtLeast(1) else width.toFloat() / height.coerceAtLeast(1)
            handler.post { remoteView?.setScalingType(if (ratio > 1.7f || ratio < 0.8f) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_BALANCED) }
        }
    }

    private fun releaseViews() {
        VideoCall.unwatch(this)
        val local = localView; val remote = remoteView
        if (local != null && remote != null) VideoCall.detach(local, remote)
        try { local?.release() } catch (_: Exception) {}
        try { remote?.release() } catch (_: Exception) {}
        localView = null; remoteView = null
    }

    override fun callChanged() {
        if (!VideoCall.active) {
            // Ended while on screen: the chat with that person, where the call now shows; a small window just closes.
            val onScreen = started && !isInPictureInPictureMode
            releaseViews(); if (onScreen) showChat(); finish(); return
        }
        render()
    }

    override fun onStart() { super.onStart(); started = true; if (VideoCall.active && localView != null) VideoCall.visible(true) }
    // Locked, closed or Rabta in the background: the camera pauses there and resumes on return, the voice goes on.
    override fun onStop() { super.onStop(); started = false; if (VideoCall.active && !isChangingConfigurations) VideoCall.visible(false) }
    override fun onDestroy() { VideoEffects.onSnapshot = null; handler.removeCallbacksAndMessages(null); releaseViews(); super.onDestroy() }


    /**
     * The chat with the person in the call, in Rabta's own task (the call's task is separate): brought to the front
     * when Rabta is open, or opened over the chats when the call came while Rabta was closed.
     */
    private fun showChat() {
        val peer = VideoCall.peerId.ifBlank { return }
        val chat = Intent(this, ChatActivity::class.java).putExtra("peerId", peer).putExtra("peerName", VideoCall.peerName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val rabtaOpen = getSystemService(android.app.ActivityManager::class.java).appTasks.any { task ->
            runCatching { task.taskInfo.baseActivity?.className }.getOrNull().let { it != null && it != VideoCallActivity::class.java.name }
        }
        runCatching {
            if (rabtaOpen) startActivity(chat)
            else android.app.TaskStackBuilder.create(this).addNextIntent(Intent(this, ConversationListActivity::class.java)).addNextIntent(chat).startActivities()
        }
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (!askingShare) minimize() }

    /** The small floating window, like WhatsApp; false where this phone does not offer it. */
    private fun minimize(): Boolean {
        if (!VideoCall.active || isInPictureInPictureMode || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        return runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build()) }.getOrDefault(false)
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        pip(inPip)
    }
    private fun pip(inPip: Boolean) {
        if (!::controls.isInitialized) return
        val chrome = if (inPip) View.GONE else View.VISIBLE
        controls.visibility = chrome; info.visibility = chrome; effectsBtn.visibility = if (inPip || VideoCall.sharing) View.GONE else View.VISIBLE
        if (inPip) showEffects(false)
        localView?.visibility = if (inPip || VideoCall.sharing) View.GONE else View.VISIBLE
        if (inPip) sharingNote.visibility = View.GONE else render()
    }

    private fun hasPerms() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPerms() = ActivityCompat.requestPermissions(this,
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        val next = pending; pending = null
        if (g.size >= 2 && g.all { it == PackageManager.PERMISSION_GRANTED } && next != null) begin(next)
        else { Toast.makeText(this, "Camera aur mic chahiye video call ke liye", Toast.LENGTH_SHORT).show(); if (!VideoCall.active) finish() }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun oval(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF080D14.toInt()) }
        setContentView(root)
        RabtaUi.systemBars(this,true)

        remoteView = SurfaceViewRenderer(this)
        root.addView(remoteView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        localView = SurfaceViewRenderer(this).apply { setZOrderMediaOverlay(true) }
        root.addView(localView, FrameLayout.LayoutParams(dp(110), dp(160)).apply {
            gravity = Gravity.TOP or Gravity.END; topMargin = dp(56); marginEnd = dp(16) })

        // Name + call time, top left beside your own preview: they fade 3 s after the call connects; a tap brings them back.
        info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { setColor(0x8C080D14.toInt()); cornerRadius = dp(18).toFloat() }
        }
        info.addView(TextView(this).apply {
            text = Names.of(this@VideoCallActivity, VideoCall.peerId, VideoCall.peerName); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        statusTv = TextView(this).apply { textSize = 14f; setTextColor(0xCCFFFFFF.toInt()); maxLines = 1 }
        info.addView(statusTv)
        root.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START; topMargin = dp(56); marginStart = dp(16); marginEnd = dp(142) })
        infoForConnected = false
        root.setOnClickListener { if (connected()) peekInfo() }
        remoteView?.setOnClickListener { if (connected()) peekInfo() }

        // While your screen is shared, your camera preview gives way to this note.
        sharingNote = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply { setColor(0xE6172230.toInt()); cornerRadius = dp(18).toFloat() }
            addView(RabtaUi.text(this@VideoCallActivity, "You're sharing your screen", 15f, true, Color.WHITE).apply { gravity = Gravity.CENTER })
            addView(RabtaUi.text(this@VideoCallActivity, "Stop sharing", 14f, true, AppTheme.ACCENT).apply {
                gravity = Gravity.CENTER; minHeight = dp(44); setPadding(dp(16), dp(8), dp(16), 0); isFocusable = true; setOnClickListener { VideoCall.stopSharing() }
            })
        }
        root.addView(sharingNote, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(64) })

        effectsBtn = UiIcons.image(this, "sparkle", Color.WHITE, 22, L.t(this, "Filters and background blur")).apply {
            isFocusable = true; background = oval(0xB3172230.toInt()); setOnClickListener { showEffects(effects.visibility != View.VISIBLE) }
        }
        root.addView(effectsBtn, FrameLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(226); marginEnd = dp(49) })
        effects = buildEffects()
        root.addView(effects, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM; bottomMargin = dp(116); marginStart = dp(8); marginEnd = dp(8) })

        // Controls
        controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun cBtn(icon: String, bg: Int, act: () -> Unit) = TextView(this).apply {
            UiIcons.set(this,UiIcons.fromSymbol(icon) ?: icon); textSize = 24f; gravity = Gravity.CENTER; background = oval(bg)
            isClickable = true; isFocusable = true; setOnClickListener { act() }
        }
        muteBtn = cBtn("🎤", 0xEE172230.toInt()) { VideoCall.toggleMute() }
        val endBtn = cBtn("📵", 0xFFD32F2F.toInt()) { VideoCall.hangUp() }
        val flipBtn = cBtn("🔄", 0xEE172230.toInt()) { VideoCall.flipCamera(this) }
        speakerBtn = cBtn("🔊", AppTheme.ACCENT) { VideoCall.toggleSpeaker() }
        shareBtn = cBtn("screen_share", 0xEE172230.toInt()) { toggleShare() }
        listOf(muteBtn, flipBtn, shareBtn, speakerBtn, endBtn).forEach { controls.addView(it, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginStart = dp(6); marginEnd = dp(6) }) }
        endBtn.contentDescription = "End call"; flipBtn.contentDescription = "Switch camera"
        root.addView(controls, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM; bottomMargin = dp(48) })
    }

    private fun connected() = VideoCall.connectedAt > 0 && VideoCall.status == "Connected"

    /** The strip: Blur first (on or off), then one tile per filter, each showing your camera with that look. */
    private fun buildEffects() = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; visibility = View.GONE
        background = GradientDrawable().apply { setColor(0xE6101820.toInt()); cornerRadius = dp(22).toFloat() }
        val row = LinearLayout(this@VideoCallActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(8)) }
        fun tile(key: String, label: String, run: () -> Unit) = row.addView(LinearLayout(this@VideoCallActivity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true; setPadding(dp(5), 0, dp(5), 0)
            setOnClickListener { run(); renderEffects() }
            val image = android.widget.ImageView(this@VideoCallActivity).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; clipToOutline = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = GradientDrawable().apply { setColor(0xFF223040.toInt()); cornerRadius = dp(16).toFloat() }
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            tiles[key] = image
            addView(image, LinearLayout.LayoutParams(dp(60), dp(76)))
            addView(RabtaUi.text(this@VideoCallActivity, label, 11f, true, Color.WHITE).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(5), 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(dp(68), -2))
            tag = label
        })
        tile("blur", "Blur") { VideoEffects.blur = !VideoEffects.blur }
        VideoFilter.values().forEach { f -> tile(f.name, f.label) { VideoEffects.filter = f } }
        addView(row)
    }

    private fun showEffects(show: Boolean) {
        if (!::effects.isInitialized) return
        effects.visibility = if (show) View.VISIBLE else View.GONE
        // Live previews only while the strip is open; the camera thread sends a small snapshot about twice a second.
        VideoEffects.onSnapshot = if (show) { snap -> handler.post { previews(snap) } } else null
        if (show) { renderEffects(); RabtaUi.enter(effects) }
    }

    private fun previews(snap: I420Planes) {
        if (!::effects.isInitialized || effects.visibility != View.VISIBLE) return
        fun bitmap(p: I420Planes) = android.graphics.Bitmap.createBitmap(VideoLook.argb(p), p.w, p.h, android.graphics.Bitmap.Config.ARGB_8888)
        VideoFilter.values().forEach { f -> tiles[f.name]?.setImageBitmap(bitmap(VideoLook.copy(snap).also { VideoLook.colour(it, f) })) }
        // Blur's tile: the picture softened all over (the call itself keeps you sharp).
        val plain = bitmap(snap)
        val tiny = android.graphics.Bitmap.createScaledBitmap(plain, maxOf(2, plain.width / 8), maxOf(2, plain.height / 8), true)
        tiles["blur"]?.setImageBitmap(android.graphics.Bitmap.createScaledBitmap(tiny, plain.width, plain.height, true))
    }

    private fun renderEffects() {
        if (!::effects.isInitialized) return
        val row = effects.getChildAt(0) as LinearLayout
        for (i in 0 until row.childCount) {
            val item = row.getChildAt(i) as LinearLayout
            val key = tiles.entries.firstOrNull { it.value === item.getChildAt(0) }?.key ?: continue
            val on = if (key == "blur") VideoEffects.blur else VideoEffects.filter.name == key
            (item.getChildAt(0).background as GradientDrawable).setStroke(dp(2), if (on) AppTheme.ACCENT else Color.TRANSPARENT)
            (item.getChildAt(1) as TextView).setTextColor(if (on) AppTheme.ACCENT else Color.WHITE)
            item.contentDescription = if (key == "blur") "${L.t(this, "Background blur")}, ${L.t(this, if (on) "on" else "off")}" else "${item.tag} ${L.t(this, "filter")}${if (on) ", ${L.t(this, "selected")}" else ""}"
        }
        effectsBtn.background = oval(if (VideoEffects.blur || VideoEffects.filter != VideoFilter.NORMAL) AppTheme.ACCENT else 0xB3172230.toInt())
    }

    /** Shows the name and time for 3 s while the call is connected. */
    private fun peekInfo() {
        showInfo(true)
        handler.removeCallbacks(fadeInfo); handler.postDelayed(fadeInfo, 3000)
    }
    /** Fades rather than removes, so a screen reader still reads the name and time. */
    private fun showInfo(show: Boolean) {
        if (!::info.isInitialized) return
        val alpha = if (show) 1f else 0f
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) info.animate().alpha(alpha).setDuration(220).start() else info.alpha = alpha
    }

    private fun render() {
        handler.removeCallbacks(tick)
        if (!::statusTv.isInitialized || !VideoCall.active) return
        val s = VideoCall.seconds()
        val connected = connected()
        statusTv.text = if (connected) "%d:%02d".format(s / 60, s % 60) else VideoCall.status
        statusTv.contentDescription = if (connected) "Call time ${statusTv.text}" else VideoCall.status
        // Calling, ringing or reconnecting stays on screen; once connected the name and time show for 3 s only.
        if (connected && !infoForConnected) { infoForConnected = true; peekInfo() }
        else if (!connected) { infoForConnected = false; handler.removeCallbacks(fadeInfo); showInfo(true) }
        val muted = VideoCall.muted
        UiIcons.set(muteBtn, if (muted) "muted" else "mic", label = if (muted) "Unmute microphone" else "Mute microphone")
        muteBtn.background = oval(if (muted) AppTheme.ACCENT else 0xEE172230.toInt()); muteBtn.isSelected = muted
        speakerBtn.background = oval(if (VideoCall.speakerOn) AppTheme.ACCENT else 0xEE172230.toInt()); speakerBtn.isSelected = VideoCall.speakerOn
        speakerBtn.contentDescription = if (VideoCall.speakerOn) "Speaker on" else "Speaker off"
        val sharing = VideoCall.sharing
        shareBtn.background = oval(if (sharing) AppTheme.ACCENT else 0xEE172230.toInt()); shareBtn.isSelected = sharing
        shareBtn.contentDescription = if (sharing) "Stop sharing your screen" else "Share your screen"
        val inPip = isInPictureInPictureMode
        sharingNote.visibility = if (sharing && !inPip) View.VISIBLE else View.GONE
        localView?.visibility = if (sharing || inPip) View.GONE else View.VISIBLE
        if (VideoCall.connectedAt > 0) handler.postDelayed(tick, 1000)
    }

    /** Android asks once per share whether Rabta may show your screen; the other phone then sees it instead of your camera. */
    private fun toggleShare() {
        if (VideoCall.sharing) { VideoCall.stopSharing(); return }
        if (VideoCall.connectedAt == 0L) { Toast.makeText(this, "Share your screen once the call connects", Toast.LENGTH_SHORT).show(); return }
        val projection = getSystemService(MediaProjectionManager::class.java) ?: return
        askingShare = true
        if (runCatching { startActivityForResult(projection.createScreenCaptureIntent(), SHARE) }.isFailure) {
            askingShare = false; Toast.makeText(this, "Screen sharing isn't available on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SHARE) return
        askingShare = false
        if (resultCode != RESULT_OK || data == null || !VideoCall.active) return
        // Android wants its screen-sharing notice in place before the screen is captured.
        if (!MessageService.sharingStarted() || !VideoCall.share(this, data)) {
            MessageService.sharingEnded()
            Toast.makeText(this, "Screen sharing could not start. Try again.", Toast.LENGTH_SHORT).show()
        }
    }
}
