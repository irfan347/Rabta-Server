package com.irfan.rabta

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : ThemedActivity(), RabtaEvents.Listener {

    companion object { @Volatile var openPeer: String? = null }

    private val GREEN get() = AppTheme.accent(this)
    private val GREEN2 get() = AppTheme.primary(this)
    private val BEIGE get() = AppTheme.bg(this@ChatActivity)
    private val SENT get() = AppTheme.sent(this@ChatActivity)
    private val RECV get() = AppTheme.surface(this@ChatActivity)
    private val TXT get() = AppTheme.text(this@ChatActivity)
    private val SUB get() = AppTheme.sub(this@ChatActivity)
    private val DARK    = 0xFF080D14.toInt()
    private val DARK2   = 0xFF111B27.toInt()
    private val GRAY_BTN= 0xFF223040.toInt()
    private val RED     = 0xFFD32F2F.toInt()

    private lateinit var peerId: String
    private lateinit var peerName: String
    private var isGroup = false
    /** "Message yourself": your own notes, kept on this phone; no calls, presence, typing or receipts. */
    private var self = false
    /** Your name for them where you gave one. [peerName] (their own) is what the chat is stored and sent under. */
    private val shownName get() = when { self -> People.selfName(this); isGroup -> peerName; else -> Names.of(this, peerId, peerName) }
    private lateinit var titleTv: TextView
    private var replyTo:Db.Message?=null
    private lateinit var replyBar:LinearLayout
    private lateinit var replyPreview:TextView
    private lateinit var offlineBanner:TextView
    private var draftReplyId:String?=null
    private var firstHistory=true
    private var scrollAfterSend=false
    private var renderedHistory:List<Triple<Db.Message,Pair<Boolean,String>,Int?>>?=null
    private var renderedQuery:String?=null
    private var renderedCalls:List<Db.CallRecord>?=null
    private lateinit var chatBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var micBtn: TextView
    private lateinit var statusTv: TextView
    private lateinit var typingTv: TextView
    // Call overlay widgets
    private lateinit var callOverlay: LinearLayout
    private lateinit var callNameTv: TextView
    private lateinit var callStatusTv: TextView
    private lateinit var callAvatarFrame: FrameLayout
    private lateinit var headerBar: LinearLayout
    private lateinit var composerBar: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var lockHint: LinearLayout
    private lateinit var lockedPanel: LinearLayout
    private lateinit var lockedTimer: TextView
    private lateinit var waveform: WaveformView
    private lateinit var pauseBtn: LinearLayout
    private lateinit var barRow: LinearLayout
    private lateinit var holdStrip: LinearLayout
    private lateinit var holdTimer: TextView
    private lateinit var emojiBtn: View
    private lateinit var stickerBtn: View
    private lateinit var cameraBtn: View
    private var pillContent: List<View> = emptyList()
    private var recordingLocked = false
    private var recordingPaused = false
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private lateinit var muteControl: TextView
    private lateinit var speakerControl: TextView
    private var searchQuery = ""
    private var historyVersion = 0
    private var recording = false
    private var recordingStarted = 0L
    private val recordingTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val seconds = recordedMillis()/1000
            holdTimer.text = duration(seconds); lockedTimer.text = duration(seconds)
            if(!recordingPaused) waveform.add(MediaHelper.recordingLevel())
            // Meanwhile the contact sees "recording audio…", as in WhatsApp.
            val now=System.currentTimeMillis()
            if(!recordingPaused&&!isGroup&&!self&&now-lastTypingSent>2500) { lastTypingSent=now; WsClient.sendTyping(peerId,"recording") }
            if(seconds >= 120) stopVoiceRecord(true) else uiHandler.postDelayed(this,100)
        }
    }
    /** Recorded time, excluding pauses. */
    private fun recordedMillis():Long {
        if(!recording) return 0
        val now=android.os.SystemClock.elapsedRealtime()
        return now-recordingStarted-pausedTotal-(if(recordingPaused) now-pausedAt else 0)
    }
    private val callTick = Runnable { syncCall() }
    private val searchTask = Runnable { loadHistory(true) }
    private fun duration(seconds: Long) = "%d:%02d".format(seconds/60,seconds%60)

    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var db: Db
    private var pendingInitiate = true
    /** The voice call screen is showing; the call itself is [VoiceCall], which goes on when this screen closes. */
    private var callShown = false
    private var resumed = false
    private val callWatcher = CallWatcher { syncCall() }
    private lateinit var callBar: CallBar
    private lateinit var voiceBar: VoiceBar
    private var lastTypingSent = 0L

    private val statusTick = object : Runnable {
        override fun run() { updateStatus(); uiHandler.postDelayed(this, 4000) }
    }
    private val typingHide = object : Runnable { override fun run() {
        typingTv.visibility = View.GONE; statusTv.visibility = View.VISIBLE
    }}

    override fun ownsPrivacyAuthentication()=Privacy.chatLocked(this,intent.getStringExtra("peerId").orEmpty())
    private var chatUnlocked = false
    private var chatUnlocking = false
    private var initialState: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBack { when { callShown -> { hideCallScreen(); true }; selectedIds.isNotEmpty() -> { clearSelection(); true }; panelOpen() -> { hidePanel(); true }; else -> false } }
        Session.init(this)
        peerId   = intent.getStringExtra("peerId")   ?: ""; if (peerId.isEmpty()) { finish(); return }
        peerName = intent.getStringExtra("peerName") ?: "User"
        isGroup  = intent.getBooleanExtra("isGroup", false)
        self = !isGroup && People.isSelf(peerId)
        if(!isGroup&&Privacy.request(this,peerId)) {
            val page=FeatureUi.page(this,"Message request")
            page.addView(FeatureUi.text(this,"$peerName wants to message you. Accept only if you recognize this person. Their calls are blocked until you accept."))
            page.addView(FeatureUi.button(this,"Accept") { Privacy.accept(this,peerId);Db.get(this).saveContact(peerId,peerName);recreate() })
            page.addView(FeatureUi.button(this,"Block") { MessageActions.toggleBlock(this,peerId) {finish()} })
            page.addView(FeatureUi.button(this,"Report") { MessageActions.report(this,peerId) })
            return
        }
        initialState = savedInstanceState
        if(Privacy.chatLocked(this,peerId)) {
            setContentView(RabtaUi.text(this,"Locked chat",20f).apply { gravity=Gravity.CENTER })
        } else { chatUnlocked=true; initializeChat(savedInstanceState) }
    }

    private fun initializeChat(savedInstanceState:Bundle?) {
        db = Db.get(this)
        // Opening another chat in this screen recreates it: the last chat's draft and search stay with that chat.
        val saved=savedInstanceState?.takeIf { (it.getString("statePeer") ?: peerId)==peerId }
        searchQuery=saved?.getString("searchQuery")?:intent.getStringExtra("searchQuery").orEmpty()
        draftReplyId=saved?.getString("replyId")
        buildUi()
        input.setText(saved?.getString("draft").orEmpty())
        RabtaEvents.add(this)
        loadHistory()
        if (VoiceCall.with(peerId) && (intent.getBooleanExtra("showCall", false) || saved?.getBoolean("callShown") == true)) showCallScreen()
        if (intent.getBooleanExtra("autoAnswer", false)) uiHandler.post { startCall(false) }
        consumeCallRequest()
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        // Another chat (a notification, the call bar): it opens as a fresh screen in this one's place, carrying nothing
        // over. Recreating this screen instead was unreliable on Android 10 and could bring back the previous chat.
        if(newIntent.getStringExtra("peerId")!=peerId) { finish(); startActivity(Intent(newIntent).setFlags(0)); overridePendingTransition(0,0); return }
        setIntent(newIntent)
        if(!chatUnlocked) return
        if(newIntent.getBooleanExtra("showCall",false)) showCallScreen()
        if(newIntent.getBooleanExtra("autoAnswer",false)) startCall(false)
        consumeCallRequest()
    }

    private fun consumeCallRequest() {
        val kind=intent.getStringExtra("requestedCallKind") ?: return
        intent.removeExtra("requestedCallKind")
        uiHandler.post {
            if(!isFinishing && !isDestroyed && !Privacy.blocked(this,peerId)) {
                when(kind) { "voice" -> startCall(true);"video" -> startVideoCall() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if(!isGroup&&Privacy.request(this,peerId))return
        if(Privacy.chatLocked(this,peerId) && !chatUnlocked) {
            window.decorView.visibility=View.INVISIBLE
            if(!chatUnlocking) {
                chatUnlocking=true
                AppLock.authenticate(this, failed={ chatUnlocking=false;finish() }) {
                    chatUnlocking=false;chatUnlocked=true
                    if(!::db.isInitialized) initializeChat(initialState)
                    window.decorView.visibility=View.VISIBLE
                    resumeChat()
                }
            }
            return
        }
        resumeChat()
    }
    private fun resumeChat() {
        if (!::db.isInitialized) return
        openPeer = peerId; ChatNotifications.clear(this, peerId)
        uiHandler.post(statusTick); uiHandler.post(presenceTick); RabtaEvents.add(this)
        resumed = true; VoiceCall.watch(callWatcher); callBar.start(); voiceBar.start(); syncCall()
        titleTv.text = shownName   // e.g. after you named them in Contact info
        if (!firstHistory) loadHistory()   // e.g. a video call just ended: its bubble joins the chat
        Thread { try { db.clearUnread(peerId) } catch (_: Exception) {} }.start()
        if (!isGroup && !self) { WsClient.sendRead(peerId, "all"); WsClient.watch(peerId) }
    }
    override fun onPause() {
        // A voice message playing goes on, as in WhatsApp; the bar in the chats shows it.
        super.onPause()
        if(Privacy.chatLocked(this,peerId)) { chatUnlocked=false;window.decorView.visibility=View.INVISIBLE;VoicePlayer.stop() }
        RabtaEvents.remove(this)
        openPeer = null; uiHandler.removeCallbacks(statusTick); uiHandler.removeCallbacks(presenceTick); if(recording) stopVoiceRecord(false)
        WsClient.unwatch(peerId)
        resumed = false; if (::callBar.isInitialized) { VoiceCall.unwatch(callWatcher); callBar.stop(); voiceBar.stop(); uiHandler.removeCallbacks(callTick); earpiece(false) }
    }
    override fun onDestroy() {
        // The call is not this screen's: it goes on, and the green bar or its notice leads back to it.
        if(recording) stopVoiceRecord(false); RabtaEvents.remove(this); VoiceCall.unwatch(callWatcher); earpiece(false)
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(state:Bundle) {
        if(::input.isInitialized) { state.putString("draft",input.text.toString());state.putString("searchQuery",searchQuery);state.putString("replyId",replyTo?.id);state.putBoolean("callShown",callShown);state.putString("statePeer",peerId) }
        super.onSaveInstanceState(state)
    }

    override fun onKeyboardVisibilityChanged(visible:Boolean) {
        if(visible) {
            // Remember the keyboard's height so the emoji panel takes exactly its place.
            window.decorView.rootWindowInsets?.let { wi ->
                @Suppress("DEPRECATION")
                val h=if(android.os.Build.VERSION.SDK_INT>=30) wi.getInsets(android.view.WindowInsets.Type.ime()).bottom-wi.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                    else wi.systemWindowInsetBottom-wi.stableInsetBottom
                if(h>dp(180)) getSharedPreferences("appearance",0).edit().putInt("keyboard_height",h).apply()
            }
            if(panelOpen()&&emojiPanel?.searching!=true) hidePanel()
        }
        if(visible&&::chatScroll.isInitialized&&nearBottom()) scrollBottom(true)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val groupSize by lazy { if(isGroup) runCatching { db.group(peerId)?.members?.size }.getOrNull() else null }
    private var presence=""
    private val presenceTick=object:Runnable { override fun run() { loadPresence(); uiHandler.postDelayed(this,30000) } }
    /** The contact's real presence from the server; nothing is guessed when the server cannot say. */
    private fun loadPresence() {
        if(isGroup||self||!Session.loggedIn||!WsClient.connected) return
        Thread {
            val r=runCatching { FeatureApi.request("/api/user/id/$peerId") }.getOrNull()
            uiHandler.post {
                if(isDestroyed||r==null) return@post
                presence=when { r.optBoolean("online") -> L.t(this,"online"); r.optLong("lastSeen")>0 -> lastSeen(r.optLong("lastSeen")); else -> "" }
                updateStatus()
            }
        }.start()
    }
    private fun lastSeen(t:Long):String {
        val clock=fmtTime(t)
        return when(dayKey(t)) {
            dayKey(System.currentTimeMillis()) -> "last seen today at $clock"
            dayKey(System.currentTimeMillis()-86_400_000) -> "last seen yesterday at $clock"
            else -> "last seen "+java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(Date(t))
        }
    }
    private val myPhoto by lazy { Session.profilePic.takeIf { it.isNotEmpty() }?.let { MediaHelper.base64ToBitmap(it) } }
    private val PROFILE = 120
    private fun openContactProfile() {
        if(self) { startActivity(Intent(this,ProfileActivity::class.java));return }
        startActivityForResult(Intent(this,ContactProfileActivity::class.java).putExtra("peerId",peerId).putExtra("peerName",peerName),PROFILE)
    }
    private fun openSearch() {
        searchField.visibility=View.VISIBLE;searchField.requestFocus()
        androidx.core.view.WindowCompat.getInsetsController(window,searchField).show(androidx.core.view.WindowInsetsCompat.Type.ime())
    }
    private fun pill(c: Int, r: Int = 999) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun oval(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun fmtTime(t: Long) = android.text.format.DateFormat.getTimeFormat(this).format(Date(t))

    private fun avatar(name: String, sz: Int): TextView {
        val cols = listOf(0xFF4B3F8F,0xFF325E71,0xFF3D6B2A,0xFF286F68)
        return TextView(this).apply {
            text = name.filter { it.isLetterOrDigit() }.take(1).uppercase().ifEmpty { "?" }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sz*0.38f); includeFontPadding=false; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = oval(cols[(name.hashCode() and 0x7FFFFFFF)%cols.size].toInt())
        }
    }

    private fun buildUi() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BEIGE); isFocusableInTouchMode=true }
        setContentView(root)

        // ── Header ──
        val hdr = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setBackgroundColor(RECV); setPadding(dp(6),dp(8),dp(10),dp(8)) }
        hdr.addView(RabtaUi.iconButton(this,"back","Back") { finish() })
        val headerAvatar=FrameLayout(this).apply { contentDescription=peerName;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        headerAvatar.addView(avatar(peerName,dp(40)),FrameLayout.LayoutParams(dp(40),dp(40)))
        if(!isGroup) ProfileHelper.loadIfNeeded(peerId,Session.serverUrl,Session.token) { bmp ->
            if(bmp!=null) uiHandler.post { if(!isDestroyed) headerAvatar.addView(ImageView(this).apply {
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageBitmap(bmp);scaleType=ImageView.ScaleType.CENTER_CROP;background=oval(RECV);clipToOutline=true
            },FrameLayout.LayoutParams(dp(40),dp(40))) }
        }
        hdr.addView(headerAvatar,LinearLayout.LayoutParams(dp(40),dp(40)))
        val hdrCol = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),0,0,0) }
        titleTv=TextView(this)
        hdrCol.addView(titleTv.apply { text=shownName; textSize=17f; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setTextColor(TXT); maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END })
        val subRow = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        statusTv  = TextView(this).apply { textSize=13f; setTextColor(SUB);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
        typingTv  = TextView(this).apply { textSize=13f; setTextColor(AppTheme.accent(this@ChatActivity)); text=L.t(this@ChatActivity,"typing…"); visibility=View.GONE }
        subRow.addView(statusTv); subRow.addView(typingTv)
        hdrCol.addView(subRow)
        hdr.addView(hdrCol, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        if (!isGroup && !self) hdr.addView(headerAction("phone","Voice call") { startCall(true) })
        if (!isGroup && !self) hdr.addView(headerAction("video","Video call") { startVideoCall() })
        if(!isGroup) {
            hdrCol.setOnClickListener { openContactProfile() }
            headerAvatar.setOnClickListener { openContactProfile() }
            headerAvatar.isFocusable=true;headerAvatar.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            headerAvatar.contentDescription="$shownName. Open profile"
        }
        hdr.addView(headerAction("more","Chat options") {
            RabtaUi.sheet(this,"Chat options",listOf(
                RabtaUi.SheetAction("Search chat") { openSearch() },
                RabtaUi.SheetAction(if(Privacy.chatLocked(this,peerId)) "Remove chat lock" else "Lock chat") { toggleChatLock() },
                RabtaUi.SheetAction("Chat wallpaper") { Wallpapers.choose(this,peerId) { chatScroll.background=Wallpapers.drawable(this,peerId) } },
                RabtaUi.SheetAction("Starred messages in this chat") { startActivity(Intent(this,StarredActivity::class.java).putExtra("peerId",peerId)) },
                RabtaUi.SheetAction("All starred messages") { startActivity(Intent(this,StarredActivity::class.java)) }
            ))
        })
        headerBar=hdr
        root.addView(hdr)
        selectionBar=buildSelectionBar()
        root.addView(selectionBar)
        callBar=CallBar(this,{ callShown && VoiceCall.with(peerId) }) { if(VoiceCall.with(peerId)) { showCallScreen();true } else false }
        root.addView(callBar.view,LinearLayout.LayoutParams(-1,-2))
        // A voice message from another chat still playing; one from this chat shows in its own bubble.
        voiceBar=VoiceBar(this) { peerId }
        root.addView(voiceBar.view,LinearLayout.LayoutParams(-1,-2))
        // One slim line while offline (tap for the full connection details), not a block of text.
        offlineBanner=RabtaUi.text(this,"",12f,color=AppTheme.sub(this)).apply {
            minHeight=dp(36);gravity=Gravity.CENTER;setPadding(dp(16),dp(6),dp(16),dp(6));maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
            background=RabtaUi.ripple(this@ChatActivity,AppTheme.raised(this@ChatActivity),0)
            setOnClickListener { ConnectionHelp.show(this@ChatActivity) }
        }
        root.addView(offlineBanner)
        updateStatus()

        // ── Call overlay ──
        callOverlay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xFF183A3D.toInt(),0xFF101827.toInt(),0xFF080D14.toInt())); visibility=View.GONE }
        // Top: name + status
        // Like WhatsApp: the arrow (or Back) shows the chats again while the call goes on.
        callOverlay.addView(LinearLayout(this).apply {
            setPadding(dp(6),dp(8),dp(6),0)
            addView(RabtaUi.iconButton(this@ChatActivity,"back","Minimize call") { hideCallScreen() }.apply { UiIcons.set(this,"back",Color.WHITE,label="Minimize call") })
        })
        val callTop = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(0,dp(8),0,0) }
        callNameTv = TextView(this).apply { text=shownName; textSize=30f; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END; setTextColor(Color.WHITE); gravity=Gravity.CENTER }
        callStatusTv = TextView(this).apply { textSize=14f; setTextColor(0xBBFFFFFF.toInt()); gravity=Gravity.CENTER; setPadding(0,dp(6),0,0) }
        callTop.addView(callNameTv); callTop.addView(callStatusTv)
        callOverlay.addView(callTop)
        // Center: avatar
        callAvatarFrame = FrameLayout(this)
        callAvatarFrame.addView(CallOrbView(this), FrameLayout.LayoutParams(dp(300),dp(300)).apply { gravity=Gravity.CENTER })
        callAvatarFrame.addView(avatar(peerName, dp(112)), FrameLayout.LayoutParams(dp(112),dp(112)).apply { gravity=Gravity.CENTER })
        callOverlay.addView(callAvatarFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        // Bottom: call controls
        val panel = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=RabtaUi.shape(this@ChatActivity,0xEE172230.toInt(),28,0xFF334155.toInt()); setPadding(dp(16),dp(20),dp(16),dp(20)) }
        val row1 = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
        val speakerBtn = callBtn("🔊","Speaker") { toggleSpeaker(it) }
        speakerControl=speakerBtn.getChildAt(0) as TextView
        val muteBtnLayout = callBtn("🎤","Mute") { toggleMute(it) }
        muteControl=muteBtnLayout.getChildAt(0) as TextView
        val vidBtn2 = callBtn("📹","Video") { startVideoCall() }
        row1.addView(speakerBtn, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        row1.addView(vidBtn2,    LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        row1.addView(muteBtnLayout, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        val row2 = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; setPadding(0,dp(20),0,0) }
        val endBtnPanel = callBtnRed("📵","End call") { endCall() }
        row2.addView(LinearLayout(this).apply {}, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        row2.addView(endBtnPanel, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        row2.addView(LinearLayout(this).apply {}, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        panel.addView(row1); panel.addView(row2)
        callOverlay.addView(panel)
        root.addView(callOverlay,LinearLayout.LayoutParams(-1,0,1f))
        searchField=EditText(this).apply {
            visibility=if(searchQuery.isBlank()) View.GONE else View.VISIBLE; setHintTextColor(SUB); background=pill(RECV,16); hint="Search in this chat"; setSingleLine(true); textSize=14f; setTextColor(TXT)
            setPadding(dp(14),dp(8),dp(14),dp(8)); setText(searchQuery)
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int) {}
                override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int) {
                    searchQuery=s?.toString().orEmpty(); historyVersion++
                    uiHandler.removeCallbacks(searchTask); uiHandler.postDelayed(searchTask,200)
                }
                override fun afterTextChanged(s:Editable?) {}
            })
        }
        root.addView(searchField)

        // ── Chat area ──
        chatScroll = ScrollView(this).apply {
            background=Wallpapers.drawable(this@ChatActivity,peerId);isVerticalScrollBarEnabled=false;isSmoothScrollingEnabled=true
            descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS;clipToPadding=false
        }
        chatBox = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(6),dp(10),dp(12)) }
        chatScroll.addView(chatBox)
        root.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))

        replyBar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;visibility=View.GONE
            setPadding(dp(16),dp(8),dp(8),dp(8));setBackgroundColor(RECV)
        }
        replyPreview=RabtaUi.text(this,"",14f,color=SUB).apply { maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(dp(12),dp(4),dp(8),dp(4));background=RabtaUi.shape(this@ChatActivity,SENT,12) }
        replyBar.addView(replyPreview,LinearLayout.LayoutParams(0,-2,1f))
        replyBar.addView(RabtaUi.iconButton(this,"close","Cancel reply") { replyTo=null;replyBar.visibility=View.GONE })
        root.addView(replyBar)
        // ── Composer: one rounded pill (emoji · message · sticker · attach · camera) and a round mic/send button ──
        composerBar=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false }
        // Shown while holding the mic: slide up to lock.
        lockHint=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;visibility=View.GONE
            background=RabtaUi.shape(this@ChatActivity,RECV,999,AppTheme.line(this@ChatActivity));setPadding(0,dp(10),0,dp(8))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(TextView(this@ChatActivity).apply { UiIcons.set(this,"lock",SUB,20,"Lock") },LinearLayout.LayoutParams(-2,-2))
            addView(TextView(this@ChatActivity).apply { UiIcons.set(this,"chevron_up",SUB,20,"Slide up") },LinearLayout.LayoutParams(-2,-2))
        }
        composerBar.addView(lockHint,LinearLayout.LayoutParams(dp(48),-2).apply { gravity=Gravity.END;marginEnd=dp(10);bottomMargin=dp(6) })
        // Locked recording: timer and live level, then delete · pause/resume · send.
        lockedPanel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;visibility=View.GONE;setPadding(dp(16),dp(14),dp(12),dp(10))
            background=RabtaUi.shape(this@ChatActivity,RECV,24).apply { val r=dp(24).toFloat();cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f) }
        }
        val levelRow=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        lockedTimer=RabtaUi.text(this,"0:00",20f,false,TXT).apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        levelRow.addView(lockedTimer)
        waveform=WaveformView(this)
        levelRow.addView(waveform,LinearLayout.LayoutParams(0,dp(32),1f).apply { marginStart=dp(16);marginEnd=dp(4) })
        lockedPanel.addView(levelRow)
        val lockedActions=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(14),0,0) }
        lockedActions.addView(TextView(this).apply {
            UiIcons.set(this,"trash",AppTheme.error(this@ChatActivity),24,"Delete recording");gravity=Gravity.CENTER;isFocusable=true
            background=RabtaUi.ripple(this@ChatActivity,(AppTheme.error(this@ChatActivity) and 0x00FFFFFF) or 0x26000000,999)
            setOnClickListener { stopVoiceRecord(false) }
        },LinearLayout.LayoutParams(dp(52),dp(52)))
        pauseBtn=LinearLayout(this).apply {
            gravity=Gravity.CENTER;isFocusable=true;background=RabtaUi.ripple(this@ChatActivity,AppTheme.raised(this@ChatActivity),999)
            setOnClickListener { togglePause() }
            addView(TextView(this@ChatActivity).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            addView(RabtaUi.text(this@ChatActivity,"Pause",16f,true).apply { setPadding(dp(10),0,0,0);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        }
        lockedActions.addView(pauseBtn,LinearLayout.LayoutParams(0,dp(52),1f).apply { marginStart=dp(12);marginEnd=dp(12) })
        lockedActions.addView(TextView(this).apply {
            UiIcons.set(this,"send",AppTheme.onPrimary(this@ChatActivity),26,"Send voice message");gravity=Gravity.CENTER;isFocusable=true
            background=RabtaUi.ripple(this@ChatActivity,GREEN2,999);setOnClickListener { stopVoiceRecord(true) }
        },LinearLayout.LayoutParams(dp(56),dp(56)))
        lockedPanel.addView(lockedActions)
        composerBar.addView(lockedPanel,LinearLayout.LayoutParams(-1,-2))

        // Room above the gesture bar, like WhatsApp; system insets are added on top of this.
        barRow=LinearLayout(this).apply { gravity=Gravity.BOTTOM;setPadding(dp(8),dp(6),dp(10),dp(18));clipChildren=false;clipToPadding=false }
        val pill=LinearLayout(this).apply {
            gravity=Gravity.BOTTOM;minimumHeight=dp(52);setPadding(dp(6),0,dp(6),0)
            background=RabtaUi.shape(this@ChatActivity,RECV,26)
        }
        // ImageViews keep every icon exactly centred in its 52dp row (text-based icons drift with font metrics).
        fun pillIcon(icon:String,label:String,action:()->Unit)=UiIcons.image(this,icon,SUB,24,label).apply {
            isFocusable=true;isClickable=true;background=RabtaUi.ripple(this@ChatActivity,Color.TRANSPARENT,999)
            setOnClickListener { action() };layoutParams=LinearLayout.LayoutParams(dp(42),dp(52))
        }
        emojiBtn=pillIcon("emoji","Emoji") { showEmojiPicker() }
        pill.addView(emojiBtn)
        input = EditText(this).apply {
            hint=L.t(this@ChatActivity,"Message"); minHeight=dp(52);textSize=17f; setTextColor(TXT); setHintTextColor(SUB); maxLines=5
            background=null;gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(6),dp(12),dp(4),dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object:TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){
                    val has = !s.isNullOrBlank()
                    sendBtn.visibility = if(has) View.VISIBLE else View.GONE
                    micBtn.visibility  = if(has) View.GONE   else View.VISIBLE
                    // Like WhatsApp, typing leaves room for the text: sticker and camera step aside.
                    stickerBtn.visibility = if(has) View.GONE else View.VISIBLE
                    cameraBtn.visibility = if(has) View.GONE else View.VISIBLE
                    val now = System.currentTimeMillis()
                    if (has && !self && now - lastTypingSent > 2500) { lastTypingSent=now; uiHandler.postDelayed({ WsClient.sendTyping(peerId) }, 300) }
                }
                override fun afterTextChanged(s:Editable?){}
            })
        }
        pill.addView(input, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        stickerBtn=pillIcon("sticker","Stickers") { Stickers.picker(this@ChatActivity,{sendContent(it,"sticker")},{pickAttachment("image/*",104)}) }
        pill.addView(stickerBtn)
        val attachBtn=pillIcon("attach","attach") { showAttachments() }
        pill.addView(attachBtn)
        cameraBtn=pillIcon("photo","Camera") { takePhoto() }
        pill.addView(cameraBtn)
        // While holding the mic, the pill shows the timer and how to cancel.
        holdStrip=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;visibility=View.GONE;minimumHeight=dp(52);setPadding(dp(12),0,dp(8),0) }
        holdStrip.addView(View(this).apply { background=RabtaUi.shape(this@ChatActivity,AppTheme.error(this@ChatActivity),999) },LinearLayout.LayoutParams(dp(10),dp(10)))
        holdTimer=RabtaUi.text(this,"0:00",17f,false,TXT).apply { setPadding(dp(10),0,0,0);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        holdStrip.addView(holdTimer)
        holdStrip.addView(RabtaUi.text(this,"‹  Slide to cancel",14f,color=SUB).apply { gravity=Gravity.END;maxLines=1 },LinearLayout.LayoutParams(0,-2,1f))
        pill.addView(holdStrip,LinearLayout.LayoutParams(0,-2,1f))
        if(Experience.easy(this)) { stickerBtn.visibility=View.GONE;cameraBtn.visibility=View.GONE;emojiBtn.visibility=View.GONE }
        pillContent=listOf(emojiBtn,input,stickerBtn,attachBtn,cameraBtn)
        barRow.addView(pill,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply { marginEnd=dp(6) })
        sendBtn = TextView(this).apply {
            UiIcons.set(this,"send",AppTheme.onPrimary(this@ChatActivity),24,"Send message"); gravity=Gravity.CENTER
            background=RabtaUi.ripple(this@ChatActivity,GREEN2,999); isFocusable=true;isClickable=true; setOnClickListener{sendMsg()}; visibility=View.GONE
        }
        barRow.addView(sendBtn, LinearLayout.LayoutParams(dp(52),dp(52)))
        micBtn = TextView(this).apply {
            UiIcons.set(this,"mic",AppTheme.onPrimary(this@ChatActivity),26,"Hold to record, slide up to lock"); gravity=Gravity.CENTER
            background=RabtaUi.ripple(this@ChatActivity,GREEN2,999);isFocusable=true
            // Screen readers cannot hold: a tap starts a locked recording instead.
            setOnClickListener { if(recording) stopVoiceRecord(true) else checkAudioPermission { startVoiceRecord(); lockRecording() } }
            var downX=0f;var downY=0f
            setOnTouchListener { _,ev -> when(ev.actionMasked){
                MotionEvent.ACTION_DOWN->{ downX=ev.rawX;downY=ev.rawY;checkAudioPermission{startVoiceRecord()}; true }
                MotionEvent.ACTION_MOVE->{
                    if(recording&&!recordingLocked) {
                        val dx=ev.rawX-downX;val dy=ev.rawY-downY
                        when { dx < -dp(110) -> stopVoiceRecord(false); dy < -dp(90) -> lockRecording() }
                    }
                    true
                }
                MotionEvent.ACTION_UP->{
                    if(recording&&!recordingLocked) {
                        if(recordedMillis()<700) { stopVoiceRecord(false);toast("Hold to record, release to send") } else stopVoiceRecord(true)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL->{ if(recording&&!recordingLocked) stopVoiceRecord(false); true }
                else->false } }
        }
        barRow.addView(micBtn, LinearLayout.LayoutParams(dp(52),dp(52)))
        composerBar.addView(barRow,LinearLayout.LayoutParams(-1,-2))
        root.addView(composerBar)
    }

    // ── Emoji, GIF and sticker panel in the keyboard's place (WhatsApp-style) ──
    private var emojiPanel:EmojiPanel?=null
    private fun panelOpen()=emojiPanel?.visibility==View.VISIBLE
    private fun keyboardHeight()=getSharedPreferences("appearance",0).getInt("keyboard_height",0).takeIf { it>dp(180) } ?: dp(300)
    private fun showEmojiPicker() { if(panelOpen()) { hidePanel();showKeyboard() } else showPanel() }
    private fun showPanel() {
        val panel=emojiPanel ?: EmojiPanel(this,
            insert={ e -> val at=input.selectionStart.coerceAtLeast(0);input.text.insert(at,e) },
            backspace={ input.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DEL));input.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_DEL)) },
            sendSticker={ sendContent(it,"sticker") },
            importSticker={ pickAttachment("image/*",104) },
            searchChanged={ searching -> emojiPanel?.layoutParams=LinearLayout.LayoutParams(-1,if(searching) -2 else keyboardHeight()) }
        ).also { emojiPanel=it;composerBar.addView(it,LinearLayout.LayoutParams(-1,keyboardHeight())) }
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
        panel.layoutParams=LinearLayout.LayoutParams(-1,keyboardHeight())
        panel.visibility=View.VISIBLE;setEmojiIcon(true)
    }
    private fun hidePanel() { emojiPanel?.let { it.stopSearch();it.visibility=View.GONE };setEmojiIcon(false) }
    private fun showKeyboard() { input.requestFocus();androidx.core.view.WindowCompat.getInsetsController(window,input).show(androidx.core.view.WindowInsetsCompat.Type.ime()) }
    private fun setEmojiIcon(panel:Boolean) {
        (emojiBtn as? ImageView)?.setImageDrawable(UiIcons.drawable(this,if(panel) "keyboard" else "emoji",SUB,24))
        emojiBtn.contentDescription=if(panel) "Keyboard" else "Emoji"
    }

    private var captureUri:android.net.Uri?=null
    private fun takePhoto() {
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),97);return
        }
        val file=java.io.File(filesDir,"attachments/camera-${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }
        val uri=FileProvider.getUriForFile(this,"$packageName.files",file);captureUri=uri
        runCatching { startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT,uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION),105) }
            .onFailure { toast("No camera app available") }
    }

    // Shared call controls
    private fun callBtn(icon: String, label: String, onClick: (TextView) -> Unit): LinearLayout {
        val col = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
        val btn = TextView(this).apply { UiIcons.set(this,UiIcons.fromSymbol(icon) ?: "phone",Color.WHITE,26,label); textSize=24f; gravity=Gravity.CENTER; background=RabtaUi.ripple(this@ChatActivity,0xFF2C3B4D.toInt(),20); isClickable=true;isFocusable=true }
        btn.setOnClickListener { onClick(btn) }
        col.addView(btn, LinearLayout.LayoutParams(dp(60),dp(60)))
        col.addView(TextView(this).apply { this.text=label; textSize=12f; setTextColor(0xCCFFFFFF.toInt()); gravity=Gravity.CENTER; setPadding(0,dp(6),0,0) })
        return col
    }
    private fun callBtnRed(icon: String, label: String, onClick: () -> Unit): LinearLayout {
        val col = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
        col.addView(TextView(this).apply { UiIcons.set(this,"end",Color.WHITE,28,label); textSize=24f; gravity=Gravity.CENTER; background=oval(RED); isClickable=true; setOnClickListener{onClick()} }, LinearLayout.LayoutParams(dp(68),dp(68)))
        col.addView(TextView(this).apply { this.text=label; textSize=12f; setTextColor(0xCCFFFFFF.toInt()); gravity=Gravity.CENTER; setPadding(0,dp(6),0,0) })
        return col
    }
    /** Header actions share one size and sit exactly centred: drawn icons, not text glyphs resting on a baseline. */
    private fun headerAction(icon: String, label: String, onClick: () -> Unit) = UiIcons.image(this,icon,TXT,24,L.t(this,label)).apply {
        isFocusable=true;isClickable=true;background=RabtaUi.ripple(this@ChatActivity,Color.TRANSPARENT,999);setOnClickListener { onClick() }
        layoutParams=LinearLayout.LayoutParams(dp(46),dp(46))
    }

    // Existing Db/Outbox and receipt states remain the source of truth.
    private fun nearBottom() = chatScroll.getChildAt(0).height <= chatScroll.height+chatScroll.scrollY+dp(80)

    private fun loadHistory(forceScroll:Boolean=false) {
        val version=++historyVersion
        val query=searchQuery
        val oldY=chatScroll.scrollY
        val atBottom=nearBottom()
        val anchor=(0 until chatBox.childCount).map { chatBox.getChildAt(it) }
            .firstOrNull { it.tag?.toString()?.startsWith("message:")==true&&it.bottom>=oldY }
            ?.let { it.tag to (it.top-oldY) }
        if(firstHistory&&chatBox.childCount==0) chatBox.addView(RabtaUi.loading(this,"Loading messages…"))
        Thread {
            val result=runCatching {
                // Photo shapes are read here, off the main thread, so the bubbles are drawn at their size at once.
                db.searchMessages(peerId,query).map { m -> Triple(m,db.meta(m.id),Transfers.progress[m.id]) }
                    .onEach { (m,_,_) -> if(m.msgType=="image") ChatImages.shape(m.id,m.body) }
            }
            // Calls with this contact sit in the timeline like WhatsApp's; searches show messages only.
            val calls=if(isGroup||query.isNotBlank()) emptyList() else runCatching { db.callsWith(peerId) }.getOrDefault(emptyList())
            uiHandler.post {
                if(isDestroyed||isFinishing||version!=historyVersion)return@post
                val unchanged=result.getOrNull()?.let { it==renderedHistory&&query==renderedQuery&&calls==renderedCalls }?:false
                if(unchanged) { if(forceScroll)scrollBottom(true);return@post }
                result.getOrNull()?.let { items -> if(query==renderedQuery&&calls==renderedCalls&&update(items,forceScroll||atBottom)) return@post }
                chatBox.removeAllViews();shownMessages.clear();messageRows.clear();forwardButtons.clear();footers.clear();continuedOf.clear()
                result.onFailure {
                    renderedHistory=null;renderedQuery=null;renderedCalls=null;selectedIds.clear();updateSelection()
                    chatBox.addView(RabtaUi.empty(this,"chats","Could not load messages","Your saved conversation is still on this phone.","Retry") { loadHistory() })
                }.onSuccess { items ->
                    renderedHistory=items;renderedQuery=query;renderedCalls=calls
                    if(items.isEmpty()&&calls.isEmpty()) {
                        chatBox.addView(if(query.isNotBlank()) RabtaUi.empty(this,"search","No matches","Try another word or clear your search.","Clear search") { searchField.setText("") }
                            else RabtaUi.empty(this,"chats","Start the conversation","Messages you send and receive will appear here."))
                    }
                    var lastDay=""
                    var previous:Db.Message?=null
                    fun newDay(time:Long)=dayKey(time).let { day -> (day!=lastDay).also { if(it) { addDateSeparator(time);lastDay=day } } }
                    var nextCall=0
                    fun callsUntil(time:Long) { while(nextCall<calls.size&&calls[nextCall].startedAt<=time) { val c=calls[nextCall++];newDay(c.startedAt);addCallBubble(c);previous=null } }
                    items.forEach { (m,meta,progress) ->
                        callsUntil(m.sentAt)
                        val newDay=newDay(m.sentAt)
                        val continued=!newDay&&previous?.let { it.mine==m.mine&&it.fromId==m.fromId&&m.sentAt-it.sentAt<10*60_000 }==true
                        addBubble(m,meta,progress,continued)
                        previous=m
                    }
                    callsUntil(Long.MAX_VALUE)
                    if(replyTo==null&&draftReplyId!=null) {
                        items.firstOrNull { it.first.id==draftReplyId }?.first?.let { selectReply(it,false) }
                        draftReplyId=null
                    }
                    selectedIds.retainAll(shownMessages.keys);updateSelection()
                    if(firstHistory||forceScroll||atBottom||scrollAfterSend) scrollBottom(!firstHistory) else chatScroll.post {
                        val anchorView=anchor?.let { saved ->(0 until chatBox.childCount).map { chatBox.getChildAt(it) }.firstOrNull { it.tag==saved.first } }
                        chatScroll.scrollTo(0,if(anchorView!=null&&anchor!=null) (anchorView.top-anchor.second).coerceAtLeast(0) else oldY)
                    }
                    firstHistory=false;scrollAfterSend=false
                }
            }
        }.start()
    }

    /**
     * The chat as drawn, and [items] the same messages so far: redraws only those that changed (a new tick only changes
     * its time line, so a voice message playing goes on) and adds new ones at the end. False when the chat has to be
     * drawn again from the start (a message removed, a search, calls in between).
     */
    private fun update(items:List<Triple<Db.Message,Pair<Boolean,String>,Int?>>,toBottom:Boolean):Boolean {
        val before=renderedHistory ?: return false
        if(before.isEmpty()||items.size<before.size||before.indices.any { items[it].first.id!=before[it].first.id }||before.any { it.first.id !in messageRows }) return false
        val last=before.last().first
        // Calls sit by time: one after the last message would belong between it and the new ones.
        if(items.size>before.size&&renderedCalls.orEmpty().any { it.startedAt>last.sentAt }) return false
        for(i in before.indices) if(items[i]!=before[i]) redraw(items[i],before[i])
        var previous=last;var lastDay=dayKey(last.sentAt)
        for((m,meta,progress) in items.subList(before.size,items.size)) {
            val day=dayKey(m.sentAt);val newDay=day!=lastDay
            if(newDay) { addDateSeparator(m.sentAt);lastDay=day }
            addBubble(m,meta,progress,!newDay&&previous.mine==m.mine&&previous.fromId==m.fromId&&m.sentAt-previous.sentAt<10*60_000)
            previous=m
        }
        renderedHistory=items
        forwardButtons.removeAll { !it.isAttachedToWindow }
        selectedIds.retainAll(shownMessages.keys);updateSelection()
        if(toBottom||scrollAfterSend) scrollBottom(true)
        scrollAfterSend=false
        return true
    }
    private fun redraw(now:Triple<Db.Message,Pair<Boolean,String>,Int?>,before:Triple<Db.Message,Pair<Boolean,String>,Int?>) {
        val (m,meta,progress)=now
        val retry=setOf("failed","expired","paused","canceled")
        val footer=footers[m.id]
        if(footer!=null&&meta==before.second&&progress==before.third&&m.copy(status=before.first.status)==before.first&&m.status !in retry&&before.first.status !in retry) {
            fillFooter(footer.first,m,footer.second);shownMessages[m.id]=m;return
        }
        val row=messageRows[m.id] ?: return
        val index=chatBox.indexOfChild(row).takeIf { it>=0 } ?: return
        chatBox.removeViewAt(index)
        addBubble(m,meta,progress,continuedOf[m.id]==true,index)
    }

    private fun selectReply(m:Db.Message,focus:Boolean=true) {
        clearSelection()
        replyTo=m
        val author=if(m.mine) "You" else Names.of(this,m.fromId,m.fromName.ifBlank { peerName })
        replyPreview.text="Reply to $author\n${MessageActions.preview(m)}"
        replyBar.visibility=View.VISIBLE
        if(focus) { input.requestFocus();androidx.core.view.WindowCompat.getInsetsController(window,input).show(androidx.core.view.WindowInsetsCompat.Type.ime()) }
    }

    private fun dayKey(time:Long)=SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date(time))
    /** Like WhatsApp: Today, Yesterday, the weekday within the last week, then the full date. */
    private fun addDateSeparator(time:Long) {
        fun midnight(t:Long)=java.util.Calendar.getInstance().apply { timeInMillis=t;set(java.util.Calendar.HOUR_OF_DAY,0);set(java.util.Calendar.MINUTE,0);set(java.util.Calendar.SECOND,0);set(java.util.Calendar.MILLISECOND,0) }.timeInMillis
        val days=Math.round((midnight(System.currentTimeMillis())-midnight(time))/86_400_000.0).toInt()
        val title=when(days) {
            0->L.t(this,"Today")
            1->L.t(this,"Yesterday")
            in 2..6->SimpleDateFormat("EEEE",Locale.getDefault()).format(Date(time))
            else->java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(Date(time))
        }
        chatBox.addView(RabtaUi.text(this,title,12f,true,SUB).apply {
            gravity=Gravity.CENTER;setPadding(dp(14),dp(6),dp(14),dp(6));background=RabtaUi.shape(this@ChatActivity,AppTheme.raised(this@ChatActivity),16)
            androidx.core.view.ViewCompat.setAccessibilityHeading(this,true)
        },LinearLayout.LayoutParams(-2,-2).apply { gravity=Gravity.CENTER;topMargin=dp(16);bottomMargin=dp(14) })
    }

    private fun bubbleSurface(mine:Boolean,tail:Boolean)=object:LinearLayout(this) {
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val limit=minOf(dp(420),(View.MeasureSpec.getSize(widthMeasureSpec)*0.86f).toInt())
            val contentWidth=(limit-paddingLeft-paddingRight).coerceAtLeast(0)
            for(i in 0 until childCount) {
                val lp=getChildAt(i).layoutParams
                if(lp.width>contentWidth)lp.width=contentWidth
            }
            super.onMeasure(View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST),heightMeasureSpec)
        }
    }.apply {
        orientation=LinearLayout.VERTICAL
        val drawn=BubbleDrawable(if(mine) SENT else RECV,mine,tail,dp(12).toFloat(),dp(7).toFloat())
        background=drawn
        val t=drawn.tail.toInt()
        setPadding(dp(10)+(if(mine) 0 else t),dp(6),dp(10)+(if(mine) t else 0),dp(5))
    }

    private fun addBubble(m:Db.Message,meta:Pair<Boolean,String>,progress:Int?,continued:Boolean=false,at:Int=-1) {
        shownMessages[m.id]=m;continuedOf[m.id]=continued
        val author=if(m.mine) L.t(this,"You") else Names.of(this,m.fromId,m.fromName.ifBlank { peerName })
        val bubble=bubbleSurface(m.mine,!continued)
        bubble.tag="bubble:${m.id}"
        if(isGroup&&!m.mine&&!continued) bubble.addView(RabtaUi.text(this,Names.of(this,m.fromId,m.fromName.ifBlank { m.fromId }),13f,true,AppTheme.accent(this)).apply { setPadding(0,0,0,dp(2)) })
        var timeInline=false
        when(m.msgType) {
            "rich" -> {
                val o=runCatching { org.json.JSONObject(m.body) }.getOrNull()
                if(o!=null) {
                    val status=o.optJSONObject("status")
                    if(status!=null) bubble.addView(statusCard(status),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
                    else bubble.addView(textLine(o.optString("reply")).apply {
                        textSize=13f;setTextColor(SUB);maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END
                        background=RabtaUi.shape(this@ChatActivity,AppTheme.raised(this@ChatActivity),12)
                        setPadding(dp(12),dp(8),dp(12),dp(8));contentDescription="Quoted message: ${o.optString("reply")}"
                    },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
                    val text=o.optString("text")
                    // A quick emoji reply to a status shows big, like WhatsApp's.
                    if(EmojiMotion.count(text)>0) bubble.addView(largeEmoji(text))
                    else { bubble.addView(InlineMetaLayout(this,textLine(text),msgFooter(m)));timeInline=true }
                } else bubble.addView(textLine("Reply could not be displayed"))
            }
            "image" -> {
                val shape=ChatImages.shape(m.id,m.body)
                if(shape!=null) {
                    // Like WhatsApp: the photo fills its bubble in its own shape (very tall or wide ones are cropped), tap for full screen.
                    val width=dp(250);val height=(width*shape.second.toFloat()/shape.first).toInt().coerceIn(dp(140),dp(330))
                    val picture=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                    // Decoded once at the bubble's size, off the main thread; the whole photo only when it is opened.
                    ChatImages.cached(m.id)?.let { picture.setImageBitmap(it) } ?: ChatImages.load(this,m.id,m.body,width) { bmp -> if(bmp!=null) picture.setImageBitmap(bmp) }
                    val photo=FrameLayout(this).apply {
                        background=RabtaUi.shape(this@ChatActivity,AppTheme.raised(this@ChatActivity),12);clipToOutline=true;isFocusable=true
                        contentDescription="Photo from $author. Tap to open";tag="photo:${m.id}"
                        setOnClickListener { ChatImages.full(this@ChatActivity,m.body) { PhotoViewer.show(this@ChatActivity,it,author) } }
                    }
                    photo.addView(picture,FrameLayout.LayoutParams(-1,-1))
                    photo.addView(View(this).apply { background=GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(0x8C000000.toInt(),0)) },FrameLayout.LayoutParams(-1,dp(48),Gravity.BOTTOM))
                    photo.addView(msgFooter(m,true),FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply { setMargins(0,0,dp(8),dp(6)) })
                    bubble.addView(photo,LinearLayout.LayoutParams(width,height));timeInline=true
                }
                else bubble.addView(textLine("Photo unavailable"))
            }
            "voice" -> {
                val voice=VoiceMessageView(this,m.body,if(m.mine) Session.name else Names.of(this,m.fromId,m.fromName.ifBlank { peerName }),if(m.mine) myPhoto else null,if(m.mine) SENT else RECV,
                    m.id,VoicePlayer.Origin(peerId,peerName,isGroup))
                if(!m.mine) (if(isGroup) m.fromId else peerId).takeIf { it.isNotBlank() }?.let { who ->
                    ProfileHelper.loadIfNeeded(who,Session.serverUrl,Session.token) { bmp -> if(!isDestroyed) voice.setPhoto(bmp) }
                }
                bubble.addView(voice,LinearLayout.LayoutParams(dp(250),-2))
            }
            "video" -> {
                // Like WhatsApp: the picture, play in the middle, download and size in the corner, the time on the picture.
                val video=VideoBubble(this,m.id,m.body,author).apply { tag="video:${m.id}" }
                video.addView(msgFooter(m,true),FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply { setMargins(0,0,dp(8),dp(8)) })
                video.setOnClickListener { video.play() };video.centre.setOnClickListener { video.play() };video.chip.setOnClickListener { video.download() }
                bubble.addView(video);timeInline=true
            }
            "file" -> {
                val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
                card.addView(RabtaUi.text(this,"Document",12f,true,AppTheme.accent(this)))
                card.addView(textLine(Attachments.name(m.body)))
                card.addView(RabtaUi.text(this,Attachments.details(m.body),12f,color=SUB))
                card.addView(RabtaUi.button(this,"Open file") { Attachments.open(this,m.body) }.apply { setPadding(0,dp(8),0,dp(8));background=RabtaUi.ripple(this@ChatActivity,Color.TRANSPARENT) })
                bubble.addView(card,LinearLayout.LayoutParams(dp(250),-2))
            }
            "sticker" -> {
                val iv=ImageView(this).apply { contentDescription="Sticker";scaleType=ImageView.ScaleType.FIT_CENTER }
                if(Stickers.valid(m.body))iv.setImageDrawable(Stickers.drawable(m.body))
                else if(runCatching {org.json.JSONObject(m.body).has("remote")}.getOrDefault(false)) FeatureApi.run(this,{Transfers.download(this,m.body)}) { local ->iv.setImageURI(android.net.Uri.fromFile(Attachments.file(this,local))) }
                else runCatching { iv.setImageURI(android.net.Uri.fromFile(Attachments.file(this,m.body))) }
                bubble.addView(iv,LinearLayout.LayoutParams(dp(150),dp(150)))
            }
            else -> {
                val card=if(m.msgType=="text") contactCard.matchEntire(m.body) else null
                if(card!=null) bubble.addView(contactView(card.groupValues[1],card.groupValues[2]),LinearLayout.LayoutParams(dp(250),-2))
                else if(m.msgType=="text"&&EmojiMotion.count(m.body)>0) bubble.addView(largeEmoji(m.body))
                else { bubble.addView(InlineMetaLayout(this,textLine(m.body),msgFooter(m)));timeInline=true }
            }
        }
        if(!timeInline) bubble.addView(msgFooter(m),LinearLayout.LayoutParams(-2,-2).apply { gravity=Gravity.END;topMargin=dp(4) })
        val reactions=runCatching { org.json.JSONObject(meta.second) }.getOrDefault(org.json.JSONObject())
        val emojis=reactions.keys().asSequence().map { reactions.optString(it) }.groupingBy { it }.eachCount().entries.joinToString("  ") { it.key+" "+it.value }
        if(meta.first||emojis.isNotEmpty())bubble.addView(RabtaUi.text(this,(if(meta.first) "★  " else "")+emojis,12f,color=SUB))
        progress?.let { bubble.addView(RabtaUi.text(this,"Uploading $it% · Hold to cancel",12f,color=SUB)) }
        // Hold a message to select it; the header then offers Reply, Star, Delete, Forward and more for the selection.
        // Only the bubble and what is tappable in it listen: a held view takes every touch, so a picture given a listener
        // would keep taps from the photo or video it is part of.
        fun actions(v:View) {
            if(v===bubble||v.isClickable) v.setOnLongClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);toggleSelected(m.id);true }
            if(v is ViewGroup)for(i in 0 until v.childCount)actions(v.getChildAt(i))
        }
        actions(bubble)
        if(m.mine&&m.status in setOf("failed","expired","paused","canceled")) {
            val label=if(m.status=="paused") "Resume sending" else if(m.status=="expired") "Resend expired message" else "Retry message"
            bubble.addView(RabtaUi.button(this,label) { Outbox.retry(m,isGroup) }.apply {
                tag="retry:${m.id}";contentDescription="$label, ${MessageActions.preview(m)}"
                setTextColor(AppTheme.error(this@ChatActivity));setPadding(dp(8),dp(10),dp(8),dp(10))
                background=RabtaUi.ripple(this@ChatActivity,Color.TRANSPARENT);layoutParams=LinearLayout.LayoutParams(-1,-2)
            })
        }
        val wrap=MessageRow(m.id).apply { orientation=LinearLayout.HORIZONTAL;gravity=(if(m.mine) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL;tag="message:${m.id}" }
        // Photos, videos, voice messages and files get WhatsApp's round forward button beside them.
        val forward=if(m.msgType in setOf("image","video","voice","file")) forwardButton(m) else null
        if(forward!=null&&m.mine) wrap.addView(forward,LinearLayout.LayoutParams(dp(36),dp(36)).apply { marginEnd=dp(8);topMargin=if(continued) dp(2) else dp(8) })
        wrap.addView(bubble,LinearLayout.LayoutParams(-2,-2).apply { topMargin=if(continued) dp(2) else dp(8) })
        if(forward!=null&&!m.mine) wrap.addView(forward,LinearLayout.LayoutParams(dp(36),dp(36)).apply { marginStart=dp(8);topMargin=if(continued) dp(2) else dp(8) })
        if(at>=0) chatBox.addView(wrap,at,LinearLayout.LayoutParams(-1,-2)) else chatBox.addView(wrap,LinearLayout.LayoutParams(-1,-2))
        messageRows[m.id]=wrap;paintSelection(m.id)
    }
    private fun toggleChatLock() {
            val locked=Privacy.chatLocked(this,peerId)
            android.app.AlertDialog.Builder(this).setTitle(if(locked) "Remove chat lock?" else "Lock this chat?")
                .setMessage("Use your fingerprint or device screen lock each time you return. Message previews and notification replies will be hidden. This protects access on this phone.")
                .setNegativeButton("Cancel",null).setPositiveButton("Continue") { _,_ ->
                    AppLock.authenticate(this) { Privacy.setChatLocked(this,peerId,!locked);recreate() }
                }.show()
    }
    private fun largeEmoji(text:String)=RabtaUi.text(this,text,if(EmojiMotion.count(text)==1) 54f else 42f).apply {
        includeFontPadding=false;setPadding(dp(8),dp(6),dp(8),dp(6))
        addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View) { EmojiMotion.play(v) }
            override fun onViewDetachedFromWindow(v:View) { v.animate().cancel() }
        })
    }
    private fun forwardButton(m:Db.Message)=UiIcons.image(this,"share_forward",TXT,18,L.t(this,"Forward")).apply {
        isFocusable=true;tag="forward:${m.id}"
        background=RabtaUi.ripple(this@ChatActivity,(AppTheme.surface(this@ChatActivity) and 0x00FFFFFF) or 0xD9000000.toInt(),999)
        setOnClickListener { MessageActions.forward(this@ChatActivity,listOf(m)) }
        setOnLongClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);toggleSelected(m.id);true }
        visibility=if(selectedIds.isEmpty()) View.VISIBLE else View.GONE
    }.also { forwardButtons.add(it) }

    // ── Selection: hold a message, then tap more; the header turns into actions for the chosen messages ──
    private val selectedIds=linkedSetOf<String>()
    /** The messages on screen now, by id, for the selection's actions, and their rows. */
    private val shownMessages=mutableMapOf<String,Db.Message>()
    private val messageRows=mutableMapOf<String,View>()
    /** Each message's time line (and whether it sits on a picture), and whether it continued the one before. */
    private val footers=mutableMapOf<String,Pair<TextView,Boolean>>()
    private val continuedOf=mutableMapOf<String,Boolean>()
    private val forwardButtons=mutableListOf<View>()
    private lateinit var selectionBar:LinearLayout
    private lateinit var selectionCount:TextView
    private lateinit var replyAction:View
    private lateinit var starAction:ImageView
    /** A message's row: while selecting, a tap (or hold) anywhere on it chooses or drops it instead of opening anything. */
    private inner class MessageRow(private val id:String):LinearLayout(this@ChatActivity) {
        private val gestures by lazy { android.view.GestureDetector(context,object:android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e:MotionEvent)=true
            override fun onSingleTapUp(e:MotionEvent):Boolean { toggleSelected(id);return true }
            override fun onLongPress(e:MotionEvent) { toggleSelected(id) }
        }) }
        private var startX=0f
        private var startY=0f
        private var swiping=false
        private var vertical=false
        private val direction get()=if(layoutDirection==View.LAYOUT_DIRECTION_RTL) -1 else 1
        override fun onInterceptTouchEvent(ev:MotionEvent):Boolean {
            if(selectedIds.isNotEmpty()) return true
            when(ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX=ev.x;startY=ev.y;swiping=false;vertical=false }
                MotionEvent.ACTION_MOVE -> {
                    val dx=(ev.x-startX)*direction;val dy=kotlin.math.abs(ev.y-startY)
                    if(dy>dp(12)&&dy>kotlin.math.abs(dx)) vertical=true
                    if(!vertical&&dx>dp(16)&&dx>dy*1.5f) { swiping=true;parent.requestDisallowInterceptTouchEvent(true);return true }
                }
            }
            return false
        }
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev:MotionEvent):Boolean {
            if(selectedIds.isNotEmpty()) { gestures.onTouchEvent(ev);return true }
            if(!swiping) return super.onTouchEvent(ev)
            val distance=((ev.x-startX)*direction).coerceIn(0f,dp(96).toFloat())
            when(ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> translationX=distance*direction
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                    if(ev.actionMasked==MotionEvent.ACTION_UP&&distance>=dp(64)) shownMessages[id]?.let {
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);selectReply(it)
                    }
                    animate().translationX(0f).setDuration(160).start();swiping=false;parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }
    }
    private fun buildSelectionBar()=LinearLayout(this).apply {
        gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(RECV);setPadding(dp(6),dp(8),dp(6),dp(8));visibility=View.GONE
        addView(headerAction("back","Cancel selection") { clearSelection() })
        selectionCount=RabtaUi.text(this@ChatActivity,"",20f,true).apply { setPadding(dp(10),0,dp(4),0);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        addView(selectionCount,LinearLayout.LayoutParams(0,-2,1f))
        replyAction=headerAction("reply","Reply") { selectionAction("reply") }.also { addView(it) }
        starAction=headerAction("star","Star") { selectionAction("star") }.also { addView(it) }
        addView(headerAction("trash","Delete") { selectionAction("delete") })
        addView(headerAction("share_forward","Forward") { selectionAction("forward") })
        addView(headerAction("emoji","React") { shownMessages.values.filter { it.id in selectedIds }.singleOrNull()?.let { m -> clearSelection();MessageActions.react(this@ChatActivity,m,isGroup) { loadHistory() } } })
        addView(headerAction("more","More options") { selectionAction("more") })
    }
    private fun toggleSelected(id:String) {
        if(!shownMessages.containsKey(id)) return
        if(!selectedIds.remove(id)) selectedIds.add(id)
        paintSelection(id);updateSelection()
    }
    private fun clearSelection() {
        if(selectedIds.isEmpty()) return
        val was=selectedIds.toList();selectedIds.clear()
        was.forEach { paintSelection(it) };updateSelection()
    }
    private fun paintSelection(id:String) {
        val row=messageRows[id] ?: return
        val on=id in selectedIds
        row.setBackgroundColor(if(on) (GREEN and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
        androidx.core.view.ViewCompat.setStateDescription(row,if(selectedIds.isEmpty()) null else L.t(this,if(on) "Selected" else "Not selected"))
    }
    private fun selectedMessages()=shownMessages.values.filter { it.id in selectedIds }.sortedBy { it.sentAt }
    /** Count and actions follow the selection: Reply for one message, Star or Unstar. */
    private fun updateSelection() {
        if(!::selectionBar.isInitialized) return
        val on=selectedIds.isNotEmpty()
        selectionBar.visibility=if(on&&!callShown) View.VISIBLE else View.GONE
        if(!callShown) headerBar.visibility=if(on) View.GONE else View.VISIBLE
        forwardButtons.forEach { it.visibility=if(on) View.GONE else View.VISIBLE }
        if(!on) return
        selectionCount.text=selectedIds.size.toString();selectionCount.contentDescription="${selectedIds.size} ${L.t(this,"selected")}"
        replyAction.visibility=if(selectedIds.size==1) View.VISIBLE else View.GONE
        val unstar=selectedMessages().let { chosen -> chosen.isNotEmpty()&&chosen.all { db.meta(it.id).first } }
        starAction.contentDescription=L.t(this,if(unstar) "Unstar" else "Star")
    }
    private fun selectionAction(key:String) {
        val chosen=selectedMessages();if(chosen.isEmpty()) { clearSelection();return }
        when(key) {
            "reply" -> chosen.singleOrNull()?.let { selectReply(it) }
            "star" -> { MessageActions.star(this,chosen);clearSelection();loadHistory() }
            "delete" -> MessageActions.delete(this,chosen) { clearSelection();loadHistory() }
            "forward" -> MessageActions.forward(this,chosen) { clearSelection() }
            "more" -> {
                val items=mutableListOf<Pair<String,()->Unit>>()
                chosen.singleOrNull()?.let { m -> items+=L.t(this,"React") to { clearSelection();MessageActions.react(this,m,isGroup) { loadHistory() } } }
                items+=L.t(this,"Copy") to { MessageActions.copy(this,chosen);clearSelection() }
                if(chosen.any { Transfers.progress.containsKey(it.id) }) items+=L.t(this,"Cancel transfer") to { chosen.forEach { Transfers.cancel(it.id) };clearSelection() }
                if(selectedIds.size<shownMessages.size) items+=L.t(this,"Select all") to { selectedIds.addAll(shownMessages.keys);shownMessages.keys.forEach { paintSelection(it) };updateSelection() }
                android.app.AlertDialog.Builder(this).setItems(items.map { it.first }.toTypedArray()) { _,i -> items[i].second() }.show()
            }
        }
    }

    private fun textLine(t:String)=RabtaUi.text(this,t,16f).apply { setLineSpacing(dp(1).toFloat(),1.05f) }
    /** Up to a few emoji and nothing else (skin tones, joiners and variation selectors allowed). */
    private fun emojiOnly(t:String)=t.isNotBlank()&&t.length<=16&&t.codePoints().allMatch { cp ->
        Character.getType(cp)==Character.OTHER_SYMBOL.toInt()||Character.getType(cp)==Character.MODIFIER_SYMBOL.toInt()||cp==0x200D||cp in 0xFE00..0xFE0F||Character.isWhitespace(cp)
    }
    /** A status reply's quote, like WhatsApp's: "Name • Status", the words or caption, and a photo's thumbnail. */
    private fun statusCard(s:org.json.JSONObject)=LinearLayout(this).apply {
        gravity=Gravity.CENTER_VERTICAL;background=RabtaUi.shape(this@ChatActivity,AppTheme.raised(this@ChatActivity),12);clipToOutline=true
        val bar=s.optString("bg").takeIf { Regex("#[0-9A-Fa-f]{6}").matches(it) }?.let { Color.parseColor(it) } ?: AppTheme.accent(this@ChatActivity)
        addView(View(this@ChatActivity).apply { setBackgroundColor(bar) },LinearLayout.LayoutParams(dp(4),-1))
        val words=s.optString("text").ifBlank { when(s.optString("kind")) { "image"->"📷 Photo";"video"->"🎥 Video";else->"" } }
        val col=LinearLayout(this@ChatActivity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8)) }
        col.addView(RabtaUi.text(this@ChatActivity,"${s.optString("name").ifBlank { "Rabta contact" }} • Status",13f,true,AppTheme.accent(this@ChatActivity)).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END })
        if(words.isNotBlank()) col.addView(RabtaUi.text(this@ChatActivity,words,13f,color=SUB).apply { maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(2),0,0) })
        addView(col,LinearLayout.LayoutParams(0,-2,1f))
        s.optString("thumb").takeIf { it.isNotEmpty() }?.let { MediaHelper.base64ToBitmap(it) }?.let { thumb ->
            addView(ImageView(this@ChatActivity).apply { setImageBitmap(thumb);scaleType=ImageView.ScaleType.CENTER_CROP;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(56),dp(56)))
        }
        contentDescription="Reply to ${s.optString("name")}'s status${if(words.isNotBlank()) ": $words" else ""}"
    }
    /** A call in the chat, like WhatsApp: voice or video, how long or how it ended, on the caller's side. Tap to call back. */
    private fun addCallBubble(c:Db.CallRecord) {
        val missed=c.type.contains("missed",true);val video=c.type.contains("video",true)
        val mine=!missed&&!c.type.startsWith("incoming")
        val title=when { missed->if(video) "Missed video call" else "Missed voice call"; video->"Video call"; else->"Voice call" }
        val detail=when {
            c.duration>0->if(c.duration<60) "${c.duration} sec" else if(c.duration<3600) "${c.duration/60} min" else "${c.duration/3600} hr ${c.duration%3600/60} min"
            missed->"Tap to call back"
            c.type.contains("busy",true)->"Busy"
            c.type.startsWith("incoming")->"Not connected"
            else->"No answer"
        }
        val color=if(missed) AppTheme.error(this) else TXT
        val bubble=bubbleSurface(mine,true)
        val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(2),0,dp(2)) }
        row.addView(UiIcons.image(this,if(video) "video" else "phone",color,20,title).apply { background=RabtaUi.shape(this@ChatActivity,AppTheme.raised(this@ChatActivity),999) },LinearLayout.LayoutParams(dp(40),dp(40)))
        val col=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,dp(14),0) }
        col.addView(RabtaUi.text(this,title,15f,true,color))
        col.addView(RabtaUi.text(this,detail,13f,color=SUB))
        row.addView(col)
        row.addView(RabtaUi.text(this,fmtTime(c.startedAt),11f,color=SUB),LinearLayout.LayoutParams(-2,-2).apply { gravity=Gravity.BOTTOM })
        bubble.addView(row)
        bubble.isFocusable=true;bubble.contentDescription="$title, $detail, ${fmtTime(c.startedAt)}. Tap to call back"
        bubble.setOnClickListener { if(video) startVideoCall() else startCall(true) }
        val wrap=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=if(mine) Gravity.END else Gravity.START;tag="call:${c.id}" }
        wrap.addView(bubble,LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(8) })
        chatBox.addView(wrap,LinearLayout.LayoutParams(-1,-2))
    }
    /**
     * Time and, on your own messages, the tick: clock, ✓, ✓✓, blue ✓✓ or a red mark. The words stay for screen readers.
     * [onMedia]: white, over a photo or video, as in WhatsApp.
     */
    private fun msgFooter(m:Db.Message,onMedia:Boolean=false)=TextView(this).apply {
        textSize=11f;maxLines=1;includeFontPadding=false
        if(onMedia) setShadowLayer(dp(2).toFloat(),0f,dp(1).toFloat(),0x99000000.toInt())
        fillFooter(this,m,onMedia);footers[m.id]=this to onMedia
    }
    private fun fillFooter(view:TextView,m:Db.Message,onMedia:Boolean) {
        val plain=if(onMedia) Color.WHITE else SUB
        view.setTextColor(plain)
        val line=android.text.SpannableStringBuilder(fmtTime(m.sentAt))
        if(m.mine) {
            val color=when { m.status==MessageDelivery.READ -> AppTheme.readTick(this); MessageDelivery.retryable(m.status) -> AppTheme.error(this); else -> plain }
            line.append(" ").append(UiIcons.inline(this,MessageDelivery.tickIcon(m.status),color,16))
        }
        view.text=line
        view.contentDescription=java.text.DateFormat.getDateTimeInstance().format(Date(m.sentAt))+(if(m.mine) ", "+MessageDelivery.label(m.status) else "")
    }
    private fun scrollBottom(smooth:Boolean=false) {
        chatScroll.post {
            val y=(chatBox.height-chatScroll.height).coerceAtLeast(0)
            if(smooth&&android.animation.ValueAnimator.areAnimatorsEnabled())chatScroll.smoothScrollTo(0,y)else chatScroll.scrollTo(0,y)
        }
    }
    private fun updateStatus() {
        // Like WhatsApp: the subtitle is about the contact (online, last seen); our own link only shows while it is down,
        // and not during a quick reconnect.
        val offline=WsClient.showsOffline()
        statusTv.text=when {
            self -> L.t(this,"Message yourself")   // your notes need no connection
            offline -> L.t(this,"Connecting…")
            isGroup -> groupSize?.let { "$it members" } ?: L.t(this,"Group")
            presence.isNotBlank() -> presence
            else -> L.t(this,"tap here for contact info")
        }
        statusTv.contentDescription=if(!offline) statusTv.text else "Your connection: ${ConnectionStatus.text(this)}"
        offlineBanner.visibility=if(!offline||callShown||self) View.GONE else View.VISIBLE
        offlineBanner.text=L.t(this,if(isGroup) "Waiting for network · Group messages need internet  ›" else "Waiting for network · Nearby text still works  ›")
        offlineBanner.contentDescription="${offlineBanner.text}. Tap for connection details"
    }

    // ── Send ──
    private fun sendMsg() {
        val text = input.text.toString().trim(); if (text.isEmpty()) return
        val quote=replyTo
        if(quote!=null&&!WsClient.connected){toast("Replies need internet. Cancel the reply to send plain text nearby.");return}
        val accepted=if(quote==null)sendContent(text,"text")else sendContent(org.json.JSONObject().put("text",text).put("reply",MessageActions.preview(quote)).toString(),"rich")
        if(accepted) { replyTo=null;replyBar.visibility=View.GONE;input.setText("");scrollBottom(true) }
    }
    private fun sendContent(body: String, msgType: String):Boolean {
        if(!isGroup&&Privacy.blocked(this,peerId)){toast("Unblock this contact to send a message");return false}
        // Notes to yourself stay on this phone, so they need no connection at all.
        if(!self && !WsClient.connected && (isGroup || msgType !in setOf("text","sticker") || (msgType=="sticker" && !Stickers.valid(body)))) { toast("Videos, files and media need internet. Nearby supports text and built-in stickers."); return false }
        if(msgType=="text" && body.toByteArray().size>12000) { toast("Message is too long (12 KB maximum)"); return false }
        if(msgType=="sticker") Stickers.remember(this,body)
        scrollAfterSend=true
        Outbox.send(peerId,peerName,isGroup,body,msgType)
        return true
    }

    // Results from the pickers, camera and Contact info.
    override fun onActivityResult(req:Int,res:Int,data:Intent?) {
        super.onActivityResult(req,res,data)
        if(res!=RESULT_OK) return
        if(req==PROFILE) {
            if(Privacy.blocked(this,peerId)&&data?.getStringExtra("action")!="search") { toast("Unblock this contact to call");return }
            when(data?.getStringExtra("action")) { "voice"->startCall(true);"video"->startVideoCall();"search"->openSearch() }
            return
        }
        if(req==102) { data?.data?.let { sendVideoFrom(it) };return }
        if(req==106) {
            // Gallery: photos and videos from the system picker.
            val uri=data?.data ?: return
            if(contentResolver.getType(uri)?.startsWith("video/")==true) sendVideoFrom(uri) else sendPhotoFrom(uri)
            return
        }
        if(req in 102..104) {
            val uri=data?.data ?: return
            toast("Preparing attachment…")
            Thread {
                val result=runCatching { Attachments.pick(this,uri,req==104) }
                uiHandler.post {
                    if(isDestroyed || isFinishing) return@post
                    result.onSuccess { body -> android.app.AlertDialog.Builder(this).setTitle("Send ${Attachments.name(body)}?").setMessage("${Attachments.details(body)} · $shownName").setOnCancelListener { Attachments.discard(this,body) }.setNegativeButton("Cancel") { _,_ -> Attachments.discard(this,body) }.setPositiveButton("Send") { _,_ -> sendContent(body,when(req){102->"video";104->"sticker";else->"file"}) }.show() }
                        .onFailure { toast(it.message ?: "Could not load file") }
                }
            }.start()
            return
        }
        if(req!=101&&req!=105) return
        val uri=(if(req==105) captureUri else data?.data) ?: return
        sendPhotoFrom(uri)
    }
    private fun sendVideoFrom(uri:android.net.Uri) {
        VideoCompressor.prepareWithDialog(this,uri,null,preview=true) { body ->
            android.app.AlertDialog.Builder(this).setTitle("Send ${Attachments.name(body)}?").setMessage("${Attachments.details(body)} · $shownName").setOnCancelListener { Attachments.discard(this,body) }
                .setNegativeButton("Cancel") { _,_ -> Attachments.discard(this,body) }.setPositiveButton("Send") { _,_ -> sendContent(body,"video") }.show()
        }
    }
    /** Shows the compressed photo that will actually be sent, then sends it. */
    private fun sendPhotoFrom(uri:android.net.Uri) {
        toast("Photo load ho rahi hai…")
        Thread {
            val body=MediaHelper.imageToBase64(this,uri)
            val bitmap=body?.let { MediaHelper.base64ToBitmap(it) }
            uiHandler.post {
                if(isFinishing || isDestroyed) return@post
                if(body==null || bitmap==null) { toast("Photo load nahi ho saki"); return@post }
                val preview=ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds=true; maxHeight=dp(400); scaleType=ImageView.ScaleType.FIT_CENTER; setPadding(dp(16),dp(8),dp(16),dp(8)) }
                android.app.AlertDialog.Builder(this).setTitle(if(self) "Keep this photo?" else "Send photo to $shownName?").setView(preview)
                    .setNegativeButton("Cancel",null).setPositiveButton("Send") { _,_ -> sendContent(body,"image") }.show()
            }
        }.start()
    }

    private fun pickAttachment(mime:String,request:Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type=mime; addCategory(Intent.CATEGORY_OPENABLE) },request)
    }
    private var attachSheet:AttachSheet?=null
    /** WhatsApp-style sheet: Gallery, Camera, Contact, Document, Stickers (Location, Poll, Event marked Soon) and recent media. */
    private fun showAttachments() {
        attachSheet=AttachSheet(this,if(self) "Keep for yourself" else "Share with $shownName",
            gallery={ startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/*","video/*")),106) },
            camera={ takePhoto() },
            document={ pickAttachment("*/*",103) },
            contact={ shareContact() },
            stickers={ showPanel();emojiPanel?.showTab(2) },
            pick={ item -> if(item.video) sendVideoFrom(item.uri) else sendPhotoFrom(item.uri) },
            requestAccess={ ActivityCompat.requestPermissions(this,RecentMedia.permissions(this),96) }
        ).also { it.show() }
    }
    /** Sends a Rabta contact as a readable message; Rabta shows it as a card with a Message button. */
    private fun shareContact() {
        val contacts=db.contacts().filter { it.peerId!=peerId&&!Privacy.blocked(this,it.peerId)&&Regex("[0-9A-F]{12}").matches(it.peerId) }
        if(contacts.isEmpty()) { toast("No other contacts to share yet");return }
        android.app.AlertDialog.Builder(this).setTitle("Share a contact").setItems(contacts.map { it.peerName }.toTypedArray()) { _,i ->
            val c=contacts[i]
            sendContent("👤 ${c.peerName.replace('\n',' ').take(60)}\nRabta ID: ${c.peerId}","text")
        }.setNegativeButton("Cancel",null).show()
    }
    private val contactCard=Regex("^👤 (.{1,60})\nRabta ID: ([0-9A-F]{12})$")
    private fun contactView(name:String,id:String)=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;contentDescription="Contact $name, Rabta ID $id"
        val top=LinearLayout(this@ChatActivity).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(2),0,dp(8)) }
        top.addView(StatusPost.avatar(this@ChatActivity,id,name,44),LinearLayout.LayoutParams(dp(44),dp(44)).apply { marginEnd=dp(12) })
        val texts=LinearLayout(this@ChatActivity).apply { orientation=LinearLayout.VERTICAL }
        texts.addView(RabtaUi.text(this@ChatActivity,name,16f,true).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END })
        texts.addView(RabtaUi.text(this@ChatActivity,"Rabta ID · $id",12f,color=SUB))
        top.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
        addView(top)
        addView(View(this@ChatActivity).apply { setBackgroundColor(AppTheme.line(this@ChatActivity)) },LinearLayout.LayoutParams(-1,dp(1)))
        addView(RabtaUi.text(this@ChatActivity,"Message",15f,true,GREEN).apply {
            gravity=Gravity.CENTER;minHeight=dp(44);isFocusable=true;background=RabtaUi.ripple(this@ChatActivity,Color.TRANSPARENT,12)
            setOnClickListener {
                if(id==Session.id) toast("This is your own Rabta ID")
                else startActivity(Intent(this@ChatActivity,ChatActivity::class.java).putExtra("peerId",id).putExtra("peerName",name))
            }
        },LinearLayout.LayoutParams(-1,-2))
    }

    private fun startVoiceRecord() {
        if(recording || VoiceCall.active || !hasMic()) return
        if(!WsClient.connected) { toast("Voice messages need internet");return }
        VoicePlayer.pause()   // recording over a voice message playing would record it too
        if(!MediaHelper.startRecording(this)) { toast("Recording shuru nahi hui"); return }
        recording=true; recordingLocked=false; recordingPaused=false; pausedTotal=0
        recordingStarted=android.os.SystemClock.elapsedRealtime()
        waveform.clear(); showRecordingUi()
        micBtn.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        uiHandler.post(recordingTick)
    }
    /** Slide up (or a screen-reader tap) keeps recording hands-free with pause, delete and send. */
    private fun lockRecording() {
        if(!recording||recordingLocked) return
        recordingLocked=true; showRecordingUi()
        micBtn.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
    }
    private fun togglePause() {
        if(!recording||!recordingLocked) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(!recordingPaused) { if(MediaHelper.pauseRecording()) { recordingPaused=true;pausedAt=now } else toast("This phone cannot pause recording") }
        else if(MediaHelper.resumeRecording()) { recordingPaused=false;pausedTotal+=now-pausedAt }
        showRecordingUi()
    }
    private fun showRecordingUi() {
        val holding=recording&&!recordingLocked
        pillContent.forEach { it.visibility=if(holding) View.GONE else View.VISIBLE }
        if(!holding) { emojiBtn.visibility=if(Experience.easy(this)) View.GONE else View.VISIBLE;val has=!input.text.isNullOrBlank()||Experience.easy(this);stickerBtn.visibility=if(has) View.GONE else View.VISIBLE;cameraBtn.visibility=if(has) View.GONE else View.VISIBLE }
        holdStrip.visibility=if(holding) View.VISIBLE else View.GONE
        lockHint.visibility=if(holding) View.VISIBLE else View.GONE
        lockedPanel.visibility=if(recording&&recordingLocked) View.VISIBLE else View.GONE
        barRow.visibility=if(recording&&recordingLocked) View.GONE else View.VISIBLE
        val icon=pauseBtn.getChildAt(0) as TextView;val label=pauseBtn.getChildAt(1) as TextView
        UiIcons.set(icon,if(recordingPaused) "mic" else "pause",TXT,22,if(recordingPaused) "Resume" else "Pause")
        label.text=if(recordingPaused) "Resume" else "Pause"
        pauseBtn.contentDescription=if(recordingPaused) "Resume recording" else "Pause recording"
    }
    private fun stopVoiceRecord(send:Boolean) {
        if(!recording) return
        recording=false; uiHandler.removeCallbacks(recordingTick)
        recordingLocked=false; recordingPaused=false; showRecordingUi()
        if(!send) { MediaHelper.cancelRecording(); return }
        Thread {
            val result=MediaHelper.stopRecording()
            uiHandler.post { if(!isDestroyed) { if(result!=null) sendContent(result.first,"voice") else toast("Recording bohat chhoti thi; dobara try karein") } }
        }.start()
    }
    private fun hasMic() = ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    private fun checkAudioPermission(then: ()->Unit) { if (hasMic()) then() else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO),98) }

    // ── Voice Call ──
    private fun startCall(initiate: Boolean) {
        if(isGroup||self) return
        val voiceId=if(initiate) "" else intent.getStringExtra("voiceCallId").orEmpty()
        // Already on this call: just show it again.
        if(VoiceCall.with(peerId) && (initiate || VoiceCall.id==voiceId)) { showCallScreen(); return }
        if(initiate && (VoiceCall.active || VideoCall.active)) { toast("Finish your current call first"); return }
        if(WsClient.connected&&WsClient.protocol<2){toast("Calls aren't available right now. Try again later.");return}
        if(!hasMic()) { pendingInitiate=initiate; ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECORD_AUDIO),99); return }
        if(!WsClient.connected) { toast("Call ke liye internet chahiye"); return }
        if(recording) stopVoiceRecord(false)
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
        VoiceCall.start(this,peerId,peerName,initiate,voiceId)
        showCallScreen()
    }
    private fun startVideoCall() {
        if(isGroup||self) return
        if(VideoCall.active) { startActivity(Intent(this,VideoCallActivity::class.java)); return }   // back to the video call going on
        if(VoiceCall.active && !VoiceCall.with(peerId)) { toast("Finish your current call first"); return }
        if(WsClient.connected&&WsClient.protocol<2){toast("Video calls aren't available right now. Try again later.");return}
        if (!WsClient.connected) { toast("Video call ke liye internet chahiye"); return }
        if(VoiceCall.active) VoiceCall.end()
        val cId = ByteArray(6).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02X".format(it)}
        startActivity(Intent(this,VideoCallActivity::class.java).putExtra(VideoCallActivity.PEER_ID,peerId).putExtra(VideoCallActivity.PEER_NAME,peerName).putExtra(VideoCallActivity.IS_CALLER,true).putExtra(VideoCallActivity.CALL_ID,cId))
    }
    private fun toggleMute(btn: TextView) { VoiceCall.toggleMute() }
    private fun toggleSpeaker(btn: TextView) { VoiceCall.toggleSpeaker() }
    private var earpieceLock: android.os.PowerManager.WakeLock?=null
    /** Like a phone call: held to the ear, the proximity sensor turns the screen off instead of the phone locking. */
    private fun earpiece(on:Boolean) {
        if(!on) { earpieceLock?.takeIf { it.isHeld }?.release(android.os.PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY); earpieceLock=null; return }
        val power=getSystemService(android.os.PowerManager::class.java) ?: return
        if(earpieceLock!=null || !power.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        earpieceLock=power.newWakeLock(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,"Rabta:earpiece").apply { setReferenceCounted(false); acquire(4*60*60_000L) }
    }
    private fun updateCallControls() {
        val muted=VoiceCall.muted;val speaker=VoiceCall.speaker
        UiIcons.set(muteControl,if(muted) "muted" else "mic",Color.WHITE,26,label=if(muted) "Unmute microphone" else "Mute microphone")
        muteControl.background=RabtaUi.ripple(this,if(muted) 0xFF256D5C.toInt() else 0xFF2C3B4D.toInt(),20); muteControl.isSelected=muted
        UiIcons.set(speakerControl,"speaker",Color.WHITE,26,label=if(speaker) "Speaker on" else "Speaker off")
        speakerControl.background=RabtaUi.ripple(this,if(speaker) 0xFF256D5C.toInt() else 0xFF2C3B4D.toInt(),20); speakerControl.isSelected=speaker
    }
    /** The call screen for this chat's call; the chat stays underneath. */
    private fun showCallScreen() {
        if(!VoiceCall.with(peerId)) return
        if(!callShown) {
            clearSelection()
            callShown=true
            if(panelOpen()) hidePanel()
            headerBar.visibility=View.GONE; (headerBar.parent as View).setBackgroundColor(DARK); RabtaUi.systemBars(this,true)
            VoicePlayer.stop();replyBar.visibility=View.GONE;offlineBanner.visibility=View.GONE
            callOverlay.visibility=View.VISIBLE; chatScroll.visibility=View.GONE; composerBar.visibility=View.GONE; searchField.visibility=View.GONE
        }
        syncCall()
    }
    /** Back to the chat; the call goes on and the green bar returns to it. */
    private fun hideCallScreen() {
        if(!callShown) return
        callShown=false; earpiece(false); uiHandler.removeCallbacks(callTick)
        headerBar.visibility=View.VISIBLE; (headerBar.parent as View).setBackgroundColor(BEIGE); RabtaUi.systemBars(this,AppTheme.dark(this))
        callOverlay.visibility=View.GONE;updateStatus();if(replyTo!=null)replyBar.visibility=View.VISIBLE
        chatScroll.visibility=View.VISIBLE; composerBar.visibility=View.VISIBLE; searchField.visibility=if(searchQuery.isBlank()) View.GONE else View.VISIBLE
        callBar.refresh()
    }
    /** Shows [VoiceCall]'s state: its status or time, mute and speaker; the call ending closes the call screen. */
    private fun syncCall() {
        uiHandler.removeCallbacks(callTick)
        if(callShown && !VoiceCall.with(peerId)) { hideCallScreen(); return }
        if(!callShown) return
        callStatusTv.text=if(VoiceCall.connectedAt>0 && VoiceCall.status.startsWith("Connected")) "Connected · ${duration(VoiceCall.seconds())}" else VoiceCall.status
        updateCallControls()
        earpiece(resumed && !VoiceCall.speaker)
        if(resumed && VoiceCall.connectedAt>0) uiHandler.postDelayed(callTick,1000)
    }
    private fun endCall() { VoiceCall.end() }

    override fun onRequestPermissionsResult(r:Int, p:Array<out String>, g:IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        val ok = g.isNotEmpty()&&g[0]==PackageManager.PERMISSION_GRANTED
        when(r) { 99->if(ok) startCall(pendingInitiate) else toast("Mic permission chahiye"); 98->if(ok) toast("Ab mic button hold karein"); 97->if(ok) takePhoto() else toast("Camera permission is needed to take a photo"); 96->attachSheet?.reload() }
    }

    // ── RabtaEvents ──
    override fun onConnected() { updateStatus(); loadPresence(); if(openPeer==peerId && !isGroup && !self) WsClient.sendRead(peerId,"all") }
    override fun onDisconnected() { updateStatus() }
    override fun onHistoryChanged(convId:String) { if(convId==peerId) loadHistory() }
    override fun onMessage(fromId:String,fromName:String,msgId:String,body:String,t:Long,msgType:String,groupId:String) {
        val match = if(isGroup) groupId==peerId else fromId==peerId; if (!match) return
        loadHistory()
        if (openPeer==peerId) {
            Thread { db.clearUnread(peerId) }.start()
            if(!isGroup) WsClient.sendRead(fromId,msgId)
        }
    }
    override fun onTyping(fromId:String,kind:String) {
        if (fromId!=peerId||isGroup) return
        uiHandler.post {
            typingTv.text=L.t(this,if(kind=="recording") "recording audio…" else "typing…")
            typingTv.visibility=View.VISIBLE; statusTv.visibility=View.GONE; uiHandler.removeCallbacks(typingHide); uiHandler.postDelayed(typingHide,3000)
        }
    }
    // Pushed by the server as soon as it changes, for the chat that is open (see WsClient.watch).
    override fun onPresence(userId:String,online:Boolean,lastSeen:Long) {
        if (userId!=peerId||isGroup||self) return
        presence=when { online -> L.t(this,"online"); lastSeen>0 -> this.lastSeen(lastSeen); else -> "" }
        updateStatus()
    }
    override fun onVideoEnd(callId:String) {}
}
