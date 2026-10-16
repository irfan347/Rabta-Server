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
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : ThemedActivity(), RabtaEvents.Listener {

    companion object { @Volatile var openPeer: String? = null }

    private val GREEN get() = AppTheme.accent(this)
    private val GREEN2 get() = AppTheme.ACCENT
    private val BEIGE get() = AppTheme.bg(this@ChatActivity)
    private val SENT get() = AppTheme.sent(this@ChatActivity)
    private val RECV get() = AppTheme.surface(this@ChatActivity)
    private val TXT get() = AppTheme.text(this@ChatActivity)
    private val SUB get() = AppTheme.sub(this@ChatActivity)
    private val DARK    = 0xFF0C1317.toInt()
    private val DARK2   = 0xFF1F2C34.toInt()
    private val GRAY_BTN= 0xFF2A3942.toInt()
    private val RED     = 0xFFD32F2F.toInt()

    private lateinit var peerId: String
    private lateinit var peerName: String
    private var isGroup = false
    private var replyTo:Db.Message?=null
    private lateinit var replyBar:LinearLayout
    private lateinit var replyPreview:TextView
    private lateinit var offlineBanner:TextView
    private val voiceViews=mutableListOf<VoiceMessageView>()
    private var draftReplyId:String?=null
    private var firstHistory=true
    private var scrollAfterSend=false
    private var renderedHistory:List<Triple<Db.Message,Pair<Boolean,String>,Int?>>?=null
    private var renderedQuery:String?=null
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
    private lateinit var recordingPanel: LinearLayout
    private lateinit var recordingTv: TextView
    private lateinit var muteControl: TextView
    private lateinit var speakerControl: TextView
    private var searchQuery = ""
    private var historyVersion = 0
    private var recording = false
    private var recordingStarted = 0L
    private var connectedAt = 0L
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private val recordingTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val seconds = (android.os.SystemClock.elapsedRealtime()-recordingStarted)/1000
            recordingTv.text = "${duration(seconds)} · Slide left to cancel"
            if(seconds >= 120) stopVoiceRecord(true) else uiHandler.postDelayed(this,250)
        }
    }
    private val callTick = object : Runnable {
        override fun run() {
            if (!inCall || connectedAt == 0L) return
            callStatusTv.text="Connected · ${duration((android.os.SystemClock.elapsedRealtime()-connectedAt)/1000)}"
            uiHandler.postDelayed(this,1000)
        }
    }
    private val callTimeout = Runnable { if(inCall && connectedAt==0L) { endCall(outcome="no answer"); toast("Call ka jawab nahi mila") } }
    private val searchTask = Runnable { loadHistory(true) }
    private fun duration(seconds: Long) = "%d:%02d".format(seconds/60,seconds%60)

    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var db: Db
    private var netCall: InternetVoiceCall? = null
    private var inCall = false
    private var callAudioMuted = false
    private var callSpeaker = false
    private var pendingInitiate = true
    private var callId = -1L
    private var lastTypingSent = 0L

    private val statusTick = object : Runnable {
        override fun run() { updateStatus(); uiHandler.postDelayed(this, 4000) }
    }
    private val typingHide = object : Runnable { override fun run() {
        typingTv.visibility = View.GONE; statusTv.visibility = View.VISIBLE
    }}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        peerId   = intent.getStringExtra("peerId")   ?: ""; if (peerId.isEmpty()) { finish(); return }
        peerName = intent.getStringExtra("peerName") ?: "User"
        isGroup  = intent.getBooleanExtra("isGroup", false)
        db = Db.get(this)
        searchQuery=savedInstanceState?.getString("searchQuery")?:intent.getStringExtra("searchQuery").orEmpty()
        draftReplyId=savedInstanceState?.getString("replyId")
        buildUi()
        input.setText(savedInstanceState?.getString("draft").orEmpty())
        RabtaEvents.add(this)
        loadHistory()
        if (intent.getBooleanExtra("autoAnswer", false)) uiHandler.post { startCall(false) }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        if(newIntent.getStringExtra("peerId")!=peerId) { setIntent(newIntent); recreate(); return }
        setIntent(newIntent)
        if(newIntent.getBooleanExtra("autoAnswer",false)) startCall(false)
    }

    override fun onResume() {
        super.onResume(); if (!::db.isInitialized) return
        openPeer = peerId
        uiHandler.post(statusTick); RabtaEvents.add(this)
        Thread { try { db.clearUnread(peerId) } catch (_: Exception) {} }.start()
        if (!isGroup) WsClient.sendRead(peerId, "all")
    }
    override fun onPause() { voiceViews.forEach { it.stop() }; super.onPause(); openPeer = null; uiHandler.removeCallbacks(statusTick); if(recording) stopVoiceRecord(false) }
    override fun onDestroy() {
        endCall(); if(recording) stopVoiceRecord(false); RabtaEvents.remove(this)
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(state:Bundle) {
        if(::input.isInitialized) { state.putString("draft",input.text.toString());state.putString("searchQuery",searchQuery);state.putString("replyId",replyTo?.id) }
        super.onSaveInstanceState(state)
    }

    override fun onKeyboardVisibilityChanged(visible:Boolean) {
        if(visible&&::chatScroll.isInitialized&&nearBottom()) scrollBottom(true)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun pill(c: Int, r: Int = 999) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun oval(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun fmtTime(t: Long) = android.text.format.DateFormat.getTimeFormat(this).format(Date(t))

    private fun avatar(name: String, sz: Int): TextView {
        val cols = listOf(0xFF6248A5,0xFF325E71,0xFF3D577E,0xFF286F68)
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
                setImageBitmap(bmp);scaleType=ImageView.ScaleType.CENTER_CROP;background=oval(RECV);clipToOutline=true
            },FrameLayout.LayoutParams(dp(40),dp(40))) }
        }
        hdr.addView(headerAvatar,LinearLayout.LayoutParams(dp(40),dp(40)))
        val hdrCol = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),0,0,0) }
        hdrCol.addView(TextView(this).apply { text=peerName; textSize=17f; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setTextColor(TXT); maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END })
        val subRow = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        statusTv  = TextView(this).apply { textSize=12f; setTextColor(SUB);maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END }
        typingTv  = TextView(this).apply { textSize=12f; setTextColor(SUB); text="typing..."; visibility=View.GONE }
        subRow.addView(statusTv); subRow.addView(typingTv)
        hdrCol.addView(subRow)
        hdr.addView(hdrCol, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        if (!isGroup) hdr.addView(headerBtn("📞") { startCall(true) })
        if (!isGroup) hdr.addView(headerBtn("🎥") { startVideoCall() })
        hdr.addView(RabtaUi.iconButton(this,"search","Search chat") {
            if(searchField.visibility==View.VISIBLE) { searchField.setText("");searchField.visibility=View.GONE;input.requestFocus() }
            else { searchField.visibility=View.VISIBLE;searchField.requestFocus();androidx.core.view.WindowCompat.getInsetsController(window,searchField).show(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        })
        hdrCol.setOnClickListener { if(!isGroup) MessageActions.contact(this,peerId) }
        headerBar=hdr
        root.addView(hdr)
        offlineBanner=RabtaUi.text(this,"",12f,color=AppTheme.sub(this)).apply {
            minHeight=dp(48);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))
            background=RabtaUi.ripple(this@ChatActivity,AppTheme.raised(this@ChatActivity),0)
            setOnClickListener { ConnectionHelp.show(this@ChatActivity) }
        }
        root.addView(offlineBanner)
        updateStatus()

        // ── Call overlay ──
        callOverlay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xFF26213F.toInt(),DARK)); visibility=View.GONE }
        // Top: name + status
        val callTop = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(0,dp(32),0,0) }
        callNameTv = TextView(this).apply { text=peerName; textSize=28f; typeface=Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity=Gravity.CENTER }
        callStatusTv = TextView(this).apply { textSize=14f; setTextColor(0xBBFFFFFF.toInt()); gravity=Gravity.CENTER; setPadding(0,dp(6),0,0) }
        callTop.addView(callNameTv); callTop.addView(callStatusTv)
        callOverlay.addView(callTop)
        // Center: avatar
        callAvatarFrame = FrameLayout(this)
        callAvatarFrame.addView(avatar(peerName, dp(112)), FrameLayout.LayoutParams(dp(112),dp(112)).apply { gravity=Gravity.CENTER })
        callOverlay.addView(callAvatarFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        // Bottom: call controls
        val panel = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=pill(DARK2,32); setPadding(dp(20),dp(20),dp(20),dp(24)) }
        val row1 = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
        val speakerBtn = callBtn("🔊","Speaker") { toggleSpeaker(it) }
        speakerControl=speakerBtn.getChildAt(0) as TextView
        val muteBtnLayout = callBtn("🎤","Mic") { toggleMute(it) }
        muteControl=muteBtnLayout.getChildAt(0) as TextView
        val vidBtn2 = callBtn("📹","Video") { startVideoCall() }
        row1.addView(speakerBtn, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        row1.addView(vidBtn2,    LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        row1.addView(muteBtnLayout, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        val row2 = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; setPadding(0,dp(20),0,0) }
        val endBtnPanel = callBtnRed("📵","End") { endCall() }
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
            setBackgroundColor(BEIGE);isVerticalScrollBarEnabled=false;isSmoothScrollingEnabled=true
            descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS;clipToPadding=false
        }
        chatBox = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(16)) }
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
        // ── Input bar ──
        recordingPanel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(RECV);visibility=View.GONE }
        recordingTv=RabtaUi.text(this,"",13f,color=AppTheme.error(this)).apply { setPadding(dp(16),dp(8),dp(16),dp(8));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        recordingPanel.addView(recordingTv)
        val recordActions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.END }
        recordActions.addView(RabtaUi.button(this,"Cancel") { stopVoiceRecord(false) }.apply { layoutParams=LinearLayout.LayoutParams(-2,-2) })
        recordActions.addView(RabtaUi.button(this,"Send voice message") { stopVoiceRecord(true) }.apply { layoutParams=LinearLayout.LayoutParams(-2,-2) })
        recordingPanel.addView(recordActions);root.addView(recordingPanel)
        val bar = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.BOTTOM; setBackgroundColor(RECV); setPadding(dp(6),dp(6),dp(6),dp(6)) }
        bar.addView(RabtaUi.iconButton(this,"attach","attach") { showAttachments() })
        bar.addView(RabtaUi.iconButton(this,"sticker","Stickers") { Stickers.picker(this@ChatActivity,{sendContent(it,"sticker")},{pickAttachment("image/*",104)}) })
        input = EditText(this).apply {
            hint=L.t(this@ChatActivity,"Message"); minHeight=dp(48);textSize=16f; setTextColor(TXT); setHintTextColor(SUB); maxLines=5
            background = RabtaUi.shape(this@ChatActivity,BEIGE,20,AppTheme.line(this@ChatActivity))
            setPadding(dp(14),dp(10),dp(14),dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object:TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){
                    val has = !s.isNullOrBlank()
                    sendBtn.visibility = if(has) View.VISIBLE else View.GONE
                    micBtn.visibility  = if(has) View.GONE   else View.VISIBLE
                    val now = System.currentTimeMillis()
                    if (has && now - lastTypingSent > 2500) { lastTypingSent=now; uiHandler.postDelayed({ WsClient.sendTyping(peerId) }, 300) }
                }
                override fun afterTextChanged(s:Editable?){}
            })
        }
        bar.addView(input, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply { marginStart=dp(4); marginEnd=dp(4) })
        sendBtn = TextView(this).apply {
            UiIcons.set(this,"send",label="Send message"); textSize=20f; setTextColor(Color.WHITE); gravity=Gravity.CENTER
            background=RabtaUi.ripple(this@ChatActivity,GREEN2,24); isFocusable=true;isClickable=true; setOnClickListener{sendMsg()}; visibility=View.GONE
        }
        bar.addView(sendBtn, LinearLayout.LayoutParams(dp(48),dp(48)))
        micBtn = TextView(this).apply {
            UiIcons.set(this,"mic",label="Hold to record; release to send"); textSize=20f; gravity=Gravity.CENTER; background=RabtaUi.ripple(this@ChatActivity,GREEN2,24);isFocusable=true
            setOnClickListener { if(recording) stopVoiceRecord(true) else checkAudioPermission { startVoiceRecord() } }
            setOnTouchListener { _,ev -> when(ev.action){ MotionEvent.ACTION_DOWN->{ checkAudioPermission{startVoiceRecord()}; true }; MotionEvent.ACTION_MOVE->{ if(ev.x < -dp(48)) stopVoiceRecord(false); true }; MotionEvent.ACTION_UP->{ stopVoiceRecord(true); true }; MotionEvent.ACTION_CANCEL->{ stopVoiceRecord(false); true }; else->false } }
        }
        bar.addView(micBtn, LinearLayout.LayoutParams(dp(48),dp(48)))
        composerBar=bar
        root.addView(bar)
    }

    // Shared call controls
    private fun callBtn(icon: String, label: String, onClick: (TextView) -> Unit): LinearLayout {
        val col = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
        val btn = TextView(this).apply { UiIcons.set(this,UiIcons.fromSymbol(icon) ?: "phone",label=label); textSize=24f; gravity=Gravity.CENTER; background=oval(GRAY_BTN); isClickable=true }
        btn.setOnClickListener { onClick(btn) }
        col.addView(btn, LinearLayout.LayoutParams(dp(60),dp(60)))
        col.addView(TextView(this).apply { this.text=label; textSize=12f; setTextColor(0xCCFFFFFF.toInt()); gravity=Gravity.CENTER; setPadding(0,dp(6),0,0) })
        return col
    }
    private fun callBtnRed(icon: String, label: String, onClick: () -> Unit): LinearLayout {
        val col = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
        col.addView(TextView(this).apply { UiIcons.set(this,"end",label=label); textSize=24f; gravity=Gravity.CENTER; background=oval(RED); isClickable=true; setOnClickListener{onClick()} }, LinearLayout.LayoutParams(dp(68),dp(68)))
        col.addView(TextView(this).apply { this.text=label; textSize=12f; setTextColor(0xCCFFFFFF.toInt()); gravity=Gravity.CENTER; setPadding(0,dp(6),0,0) })
        return col
    }
    private fun headerBtn(icon: String, onClick: () -> Unit) = TextView(this).apply { UiIcons.set(this,UiIcons.fromSymbol(icon) ?: "phone",if(icon=="📎") GREEN else TXT); textSize=20f; minWidth=dp(48); minHeight=dp(48); gravity=Gravity.CENTER; setPadding(dp(8),dp(4),dp(8),dp(4)); isClickable=true; setOnClickListener{onClick()} }

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
                db.searchMessages(peerId,query).map { m -> Triple(m,db.meta(m.id),Transfers.progress[m.id]) }
            }
            uiHandler.post {
                if(isDestroyed||isFinishing||version!=historyVersion)return@post
                val unchanged=result.getOrNull()?.let { it==renderedHistory&&query==renderedQuery }?:false
                if(unchanged) { if(forceScroll)scrollBottom(true);return@post }
                voiceViews.forEach { it.stop() };voiceViews.clear();chatBox.removeAllViews()
                result.onFailure {
                    renderedHistory=null;renderedQuery=null
                    chatBox.addView(RabtaUi.empty(this,"chats","Could not load messages","Your saved conversation is still on this phone.","Retry") { loadHistory() })
                }.onSuccess { items ->
                    renderedHistory=items;renderedQuery=query
                    if(items.isEmpty()) {
                        chatBox.addView(if(query.isNotBlank()) RabtaUi.empty(this,"search","No matches","Try another word or clear your search.","Clear search") { searchField.setText("") }
                            else RabtaUi.empty(this,"chats","Start the conversation","Messages you send and receive will appear here."))
                    }
                    var lastDay=""
                    items.forEach { (m,meta,progress) ->
                        val day=dayKey(m.sentAt)
                        if(day!=lastDay) { addDateSeparator(m.sentAt);lastDay=day }
                        addBubble(m,meta,progress)
                    }
                    if(replyTo==null&&draftReplyId!=null) {
                        items.firstOrNull { it.first.id==draftReplyId }?.first?.let { selectReply(it,false) }
                        draftReplyId=null
                    }
                    if(firstHistory||forceScroll||atBottom||scrollAfterSend) scrollBottom(!firstHistory) else chatScroll.post {
                        val anchorView=anchor?.let { saved ->(0 until chatBox.childCount).map { chatBox.getChildAt(it) }.firstOrNull { it.tag==saved.first } }
                        chatScroll.scrollTo(0,if(anchorView!=null&&anchor!=null) (anchorView.top-anchor.second).coerceAtLeast(0) else oldY)
                    }
                    firstHistory=false;scrollAfterSend=false
                }
            }
        }.start()
    }

    private fun selectReply(m:Db.Message,focus:Boolean=true) {
        replyTo=m
        val author=if(m.mine) "You" else m.fromName.ifBlank { peerName }
        replyPreview.text="Reply to $author\n${MessageActions.preview(m)}"
        replyBar.visibility=View.VISIBLE
        if(focus) { input.requestFocus();androidx.core.view.WindowCompat.getInsetsController(window,input).show(androidx.core.view.WindowInsetsCompat.Type.ime()) }
    }

    private fun dayKey(time:Long)=SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date(time))
    private fun addDateSeparator(time:Long) {
        val yesterday=java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR,-1) }
        val title=when(dayKey(time)) {
            dayKey(System.currentTimeMillis())->L.t(this,"Today")
            dayKey(yesterday.timeInMillis)->L.t(this,"Yesterday")
            else->java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(Date(time))
        }
        chatBox.addView(RabtaUi.text(this,title,12f,true,SUB).apply {
            gravity=Gravity.CENTER;setPadding(dp(14),dp(6),dp(14),dp(6));background=RabtaUi.shape(this@ChatActivity,AppTheme.raised(this@ChatActivity),16)
            androidx.core.view.ViewCompat.setAccessibilityHeading(this,true)
        },LinearLayout.LayoutParams(-2,-2).apply { gravity=Gravity.CENTER;topMargin=dp(16);bottomMargin=dp(14) })
    }

    private fun bubbleSurface(mine:Boolean)=object:LinearLayout(this) {
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
        background=RabtaUi.shape(this@ChatActivity,if(mine) SENT else RECV,20,if(mine) null else AppTheme.line(this@ChatActivity)).apply {
            val r=dp(20).toFloat();val corner=dp(6).toFloat()
            cornerRadii=if(mine) floatArrayOf(r,r,r,r,corner,corner,r,r) else floatArrayOf(r,r,r,r,r,r,corner,corner)
        }
        setPadding(dp(14),dp(12),dp(14),dp(8))
    }

    private fun addBubble(m:Db.Message,meta:Pair<Boolean,String>,progress:Int?) {
        val bubble=bubbleSurface(m.mine)
        bubble.tag="bubble:${m.id}"
        if(isGroup&&!m.mine) bubble.addView(RabtaUi.text(this,m.fromName.ifBlank { peerName },13f,true,AppTheme.accent(this)).apply { setPadding(0,0,0,dp(6)) })
        when(m.msgType) {
            "rich" -> {
                val o=runCatching { org.json.JSONObject(m.body) }.getOrNull()
                if(o!=null) {
                    bubble.addView(textLine(o.optString("reply")).apply {
                        textSize=13f;setTextColor(SUB);maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END
                        background=RabtaUi.shape(this@ChatActivity,AppTheme.raised(this@ChatActivity),12)
                        setPadding(dp(12),dp(8),dp(12),dp(8));contentDescription="Quoted message: ${o.optString("reply")}"
                    },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
                    bubble.addView(textLine(o.optString("text")))
                } else bubble.addView(textLine("Reply could not be displayed"))
            }
            "image" -> {
                val bmp=MediaHelper.base64ToBitmap(m.body)
                if(bmp!=null) bubble.addView(ImageView(this).apply {
                    setImageBitmap(bmp);scaleType=ImageView.ScaleType.FIT_CENTER;adjustViewBounds=true
                    background=RabtaUi.shape(this@ChatActivity,AppTheme.raised(this@ChatActivity),12);clipToOutline=true;contentDescription="Photo"
                },LinearLayout.LayoutParams(dp(220),dp(200)))
                else bubble.addView(textLine("Photo unavailable"))
            }
            "voice" -> {
                val voice=VoiceMessageView(this,m.body)
                voiceViews.add(voice);bubble.addView(voice,LinearLayout.LayoutParams(dp(240),-2))
            }
            "file","video" -> {
                bubble.addView(RabtaUi.text(this,if(m.msgType=="video") "Video" else "Document",12f,true,AppTheme.accent(this)))
                bubble.addView(textLine(Attachments.name(m.body)))
                bubble.addView(RabtaUi.text(this,Attachments.details(m.body),12f,color=SUB))
                bubble.addView(RabtaUi.button(this,"Open ${if(m.msgType=="video") "video" else "file"}") { Attachments.open(this,m.body) }.apply { setPadding(0,dp(8),0,dp(8));background=RabtaUi.ripple(this@ChatActivity,Color.TRANSPARENT) })
            }
            "sticker" -> {
                val iv=ImageView(this).apply { contentDescription="Sticker";scaleType=ImageView.ScaleType.FIT_CENTER }
                if(Stickers.valid(m.body))iv.setImageDrawable(Stickers.drawable(m.body))
                else if(runCatching {org.json.JSONObject(m.body).has("remote")}.getOrDefault(false)) FeatureApi.run(this,{Transfers.download(this,m.body)}) { local ->iv.setImageURI(android.net.Uri.fromFile(Attachments.file(this,local))) }
                else runCatching { iv.setImageURI(android.net.Uri.fromFile(Attachments.file(this,m.body))) }
                bubble.addView(iv,LinearLayout.LayoutParams(dp(150),dp(150)))
            }
            else -> bubble.addView(textLine(m.body))
        }
        bubble.addView(msgFooter(m))
        val reactions=runCatching { org.json.JSONObject(meta.second) }.getOrDefault(org.json.JSONObject())
        val emojis=reactions.keys().asSequence().map { reactions.optString(it) }.groupingBy { it }.eachCount().entries.joinToString("  ") { it.key+" "+it.value }
        if(meta.first||emojis.isNotEmpty())bubble.addView(RabtaUi.text(this,(if(meta.first) "★  " else "")+emojis,12f,color=SUB))
        progress?.let { bubble.addView(RabtaUi.text(this,"Uploading $it% · Hold to cancel",12f,color=SUB)) }
        fun actions(v:View) {
            v.setOnLongClickListener { MessageActions.show(this,m,isGroup,{ selectReply(it) },{loadHistory()});true }
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
        val wrap=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=if(m.mine) Gravity.END else Gravity.START;tag="message:${m.id}" }
        wrap.addView(bubble,LinearLayout.LayoutParams(-2,-2).apply { bottomMargin=dp(8) })
        chatBox.addView(wrap,LinearLayout.LayoutParams(-1,-2))
    }

    private fun textLine(t:String)=RabtaUi.text(this,t,16f).apply { setLineSpacing(dp(2).toFloat(),1.1f) }
    private fun msgFooter(m:Db.Message)=RabtaUi.text(this,fmtTime(m.sentAt)+(if(m.mine) "  ·  "+MessageDelivery.label(m.status) else ""),12f,color=SUB).apply {
        gravity=Gravity.END;setPadding(0,dp(8),0,0)
        contentDescription=java.text.DateFormat.getDateTimeInstance().format(Date(m.sentAt))+(if(m.mine) ", "+MessageDelivery.label(m.status) else "")
        if(m.mine) {
            val value=text.toString();val offset=value.indexOf("  ·  ")+5
            text=android.text.SpannableString(value).apply {
                setSpan(android.text.style.ForegroundColorSpan(when(m.status) {
                    "read","delivered"->AppTheme.teal(this@ChatActivity)
                    "failed","expired","canceled"->AppTheme.error(this@ChatActivity)
                    else->SUB
                }),offset,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
    private fun scrollBottom(smooth:Boolean=false) {
        chatScroll.post {
            val y=(chatBox.height-chatScroll.height).coerceAtLeast(0)
            if(smooth&&android.animation.ValueAnimator.areAnimatorsEnabled())chatScroll.smoothScrollTo(0,y)else chatScroll.scrollTo(0,y)
        }
    }
    private fun updateStatus() {
        statusTv.text=if(isGroup) "Group · ${WsClient.status}" else "Your connection: ${WsClient.status}"
        statusTv.contentDescription="Your connection: ${ConnectionStatus.text(this)}"
        offlineBanner.visibility=if(WsClient.connected||inCall) View.GONE else View.VISIBLE
        offlineBanner.text=if(isGroup) "No server connection · Group messages need internet. Tap for connection details."
            else "No server connection · Nearby supports text and built-in stickers. Tap for connection details."
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
        if(!WsClient.connected && (isGroup || msgType !in setOf("text","sticker") || (msgType=="sticker" && !Stickers.valid(body)))) { toast("Videos, files and media need internet. Nearby supports text and built-in stickers."); return false }
        if(msgType=="text" && body.toByteArray().size>12000) { toast("Message is too long (12 KB maximum)"); return false }
        if(msgType=="sticker") Stickers.remember(this,body)
        scrollAfterSend=true
        Outbox.send(peerId,peerName,isGroup,body,msgType)
        return true
    }

    // Preview the compressed image that will actually be sent.
    private fun pickImage() { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE) },101) }
    override fun onActivityResult(req:Int,res:Int,data:Intent?) {
        super.onActivityResult(req,res,data)
        if(res!=RESULT_OK) return
        if(req in 102..104) {
            val uri=data?.data ?: return
            toast("Preparing attachment…")
            Thread {
                val result=runCatching { Attachments.pick(this,uri,req==104) }
                uiHandler.post {
                    if(isDestroyed || isFinishing) return@post
                    result.onSuccess { body -> android.app.AlertDialog.Builder(this).setTitle("Send ${Attachments.name(body)}?").setMessage("${Attachments.details(body)} · $peerName").setOnCancelListener { Attachments.discard(this,body) }.setNegativeButton("Cancel") { _,_ -> Attachments.discard(this,body) }.setPositiveButton("Send") { _,_ -> sendContent(body,when(req){102->"video";104->"sticker";else->"file"}) }.show() }
                        .onFailure { toast(it.message ?: "Could not load file") }
                }
            }.start()
            return
        }
        if(req!=101) return
        val uri=data?.data ?: return
        toast("Photo load ho rahi hai…")
        Thread {
            val body=MediaHelper.imageToBase64(this,uri)
            val bitmap=body?.let { MediaHelper.base64ToBitmap(it) }
            uiHandler.post {
                if(isFinishing || isDestroyed) return@post
                if(body==null || bitmap==null) { toast("Photo load nahi ho saki"); return@post }
                val preview=ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds=true; maxHeight=dp(400); scaleType=ImageView.ScaleType.FIT_CENTER; setPadding(dp(16),dp(8),dp(16),dp(8)) }
                android.app.AlertDialog.Builder(this).setTitle("Send photo to $peerName?").setView(preview)
                    .setNegativeButton("Cancel",null).setPositiveButton("Send") { _,_ -> sendContent(body,"image") }.show()
            }
        }.start()
    }

    private fun pickAttachment(mime:String,request:Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type=mime; addCategory(Intent.CATEGORY_OPENABLE) },request)
    }
    private fun showAttachments() {
        RabtaUi.sheet(this,"Share with $peerName",listOf(
            RabtaUi.SheetAction("Photo") { pickImage() },
            RabtaUi.SheetAction("Video","Up to 8 MB") { pickAttachment("video/*",102) },
            RabtaUi.SheetAction("Document / file","Up to 8 MB") { pickAttachment("*/*",103) },
            RabtaUi.SheetAction("Stickers") { Stickers.picker(this,{sendContent(it,"sticker")},{pickAttachment("image/*",104)}) },
            RabtaUi.SheetAction("Offline security") { MeshSecurity.showContact(this,peerId) }
        ))
    }

    private fun startVoiceRecord() {
        if(recording || inCall || !hasMic()) return
        if(!WsClient.connected) { toast("Voice messages need internet");return }
        if(!MediaHelper.startRecording(this)) { toast("Recording shuru nahi hui"); return }
        recording=true; recordingStarted=android.os.SystemClock.elapsedRealtime()
        recordingPanel.visibility=View.VISIBLE; uiHandler.post(recordingTick)
    }
    private fun stopVoiceRecord(send:Boolean) {
        if(!recording) return
        recording=false; uiHandler.removeCallbacks(recordingTick); recordingPanel.visibility=View.GONE
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
        if(inCall || isGroup) return
        if(WsClient.connected&&WsClient.protocol<2){toast("Server update required for calls. Open Connection details.");return}
        if(!hasMic()) { pendingInitiate=initiate; ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECORD_AUDIO),99); return }
        if(!WsClient.connected) { toast("Call ke liye internet chahiye"); return }
        if(recording) stopVoiceRecord(false)
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
        inCall=true; connectedAt=0L; callAudioMuted=false; callSpeaker=false
        updateCallControls()
        val am=getSystemService(AUDIO_SERVICE) as AudioManager
        previousAudioMode=am.mode; previousSpeaker=am.isSpeakerphoneOn
        am.mode=AudioManager.MODE_IN_COMMUNICATION; am.isSpeakerphoneOn=false
        headerBar.visibility=View.GONE; (headerBar.parent as View).setBackgroundColor(DARK); RabtaUi.systemBars(this,true)
        voiceViews.forEach { it.stop() };replyBar.visibility=View.GONE;offlineBanner.visibility=View.GONE
        callOverlay.visibility=View.VISIBLE; chatScroll.visibility=View.GONE; composerBar.visibility=View.GONE; searchField.visibility=View.GONE
        callStatusTv.text=if(initiate) "Calling…" else "Connecting…"
        callId=db.logCallStart(peerId,peerName,if(initiate)"outgoing" else "incoming")
        val voiceId=if(initiate) ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) } else intent.getStringExtra("voiceCallId").orEmpty()
        netCall=InternetVoiceCall(peerId,voiceId) { status ->
            if(inCall) {
                callStatusTv.text=status
                if(status.startsWith("Connected") && connectedAt==0L) {
                    connectedAt=android.os.SystemClock.elapsedRealtime(); uiHandler.removeCallbacks(callTimeout); uiHandler.post(callTick)
                }
            }
        }
        netCall?.start(initiate)
        uiHandler.postDelayed(callTimeout,45000)
    }
    private fun startVideoCall() {
        if(isGroup) return
        if(WsClient.connected&&WsClient.protocol<2){toast("Server update required for video calls");return}
        if(inCall) endCall()
        if (!WsClient.connected) { toast("Video call ke liye internet chahiye"); return }
        val cId = ByteArray(6).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02X".format(it)}
        startActivity(Intent(this,VideoCallActivity::class.java).putExtra(VideoCallActivity.PEER_ID,peerId).putExtra(VideoCallActivity.PEER_NAME,peerName).putExtra(VideoCallActivity.IS_CALLER,true).putExtra(VideoCallActivity.CALL_ID,cId))
    }
    private fun toggleMute(btn: TextView) {
        callAudioMuted=!callAudioMuted; netCall?.muted=callAudioMuted
        updateCallControls()
    }
    private fun toggleSpeaker(btn: TextView) {
        callSpeaker=!callSpeaker
        try { (getSystemService(AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn=callSpeaker } catch (_:Exception) {}
        updateCallControls()
    }
    private fun updateCallControls() {
        UiIcons.set(muteControl,if(callAudioMuted) "muted" else "mic",label=if(callAudioMuted) "Unmute microphone" else "Mute microphone")
        muteControl.background=oval(if(callAudioMuted) GREEN else GRAY_BTN); muteControl.isSelected=callAudioMuted
        UiIcons.set(speakerControl,"speaker",label=if(callSpeaker) "Speaker on" else "Speaker off")
        speakerControl.background=oval(if(callSpeaker) GREEN else GRAY_BTN); speakerControl.isSelected=callSpeaker
    }
    private fun endCall(sendEnd:Boolean=true,outcome:String="cancelled") {
        if(!inCall) return
        inCall=false; netCall?.stop(sendEnd); netCall=null
        uiHandler.removeCallbacks(callTick); uiHandler.removeCallbacks(callTimeout)
        val seconds=if(connectedAt==0L) 0 else ((android.os.SystemClock.elapsedRealtime()-connectedAt)/1000).toInt()
        if(callId>0) db.logCallEnd(callId,seconds); if(callId>0&&connectedAt==0L)db.callOutcome(callId,outcome)
        callId=-1L; connectedAt=0L
        try { (getSystemService(AUDIO_SERVICE) as AudioManager).apply { isSpeakerphoneOn=previousSpeaker; mode=previousAudioMode } } catch (_:Exception) {}
        headerBar.visibility=View.VISIBLE; (headerBar.parent as View).setBackgroundColor(BEIGE); RabtaUi.systemBars(this,AppTheme.dark(this))
        callOverlay.visibility=View.GONE;updateStatus();if(replyTo!=null)replyBar.visibility=View.VISIBLE
        chatScroll.visibility=View.VISIBLE; composerBar.visibility=View.VISIBLE; searchField.visibility=if(searchQuery.isBlank()) View.GONE else View.VISIBLE
    }

    override fun onRequestPermissionsResult(r:Int, p:Array<out String>, g:IntArray) {
        val ok = g.isNotEmpty()&&g[0]==PackageManager.PERMISSION_GRANTED
        when(r) { 99->if(ok) startCall(pendingInitiate) else toast("Mic permission chahiye"); 98->if(ok) toast("Ab mic button hold karein") }
    }

    // ── RabtaEvents ──
    override fun onConnected() { updateStatus(); if(openPeer==peerId && !isGroup) WsClient.sendRead(peerId,"all") }
    override fun onDisconnected() { updateStatus(); if(inCall) { endCall(false); toast("Call disconnected") } }
    override fun onHistoryChanged(convId:String) { if(convId==peerId) loadHistory() }
    override fun onMessage(fromId:String,fromName:String,msgId:String,body:String,t:Long,msgType:String,groupId:String) {
        val match = if(isGroup) groupId==peerId else fromId==peerId; if (!match) return
        loadHistory()
        if (openPeer==peerId) {
            Thread { db.clearUnread(peerId) }.start()
            if(!isGroup) WsClient.sendRead(fromId,msgId)
        }
    }
    override fun onTyping(fromId:String) {
        if (fromId!=peerId||isGroup) return
        uiHandler.post { typingTv.visibility=View.VISIBLE; statusTv.visibility=View.GONE; uiHandler.removeCallbacks(typingHide); uiHandler.postDelayed(typingHide,3000) }
    }
    override fun onCallAudio(fromId:String,data:String,callId:String) {
        if (fromId!=peerId || callId!=netCall?.id) return
        // Audio alone must never auto-answer an incoming call.
        if(inCall) netCall?.onAudio(data)
    }
    override fun onCallEnd(fromId:String,callId:String) { if(fromId==peerId && callId==netCall?.id) uiHandler.post{endCall(false,"ended before answer");toast("Call khatam")} }
    override fun onCallState(fromId:String,callId:String,state:String) {
        if(fromId!=peerId || callId!=netCall?.id) return
        if(state=="busy") { endCall(false,"busy"); toast("Busy"); return }
        netCall?.state(state)
    }
    override fun onVideoEnd(callId:String) {}
}
