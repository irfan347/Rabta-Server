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

    private val GREEN get() = AppTheme.ACCENT
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
            if(seconds >= 120) stopVoiceRecord(true) else handler.postDelayed(this,250)
        }
    }
    private val callTick = object : Runnable {
        override fun run() {
            if (!inCall || connectedAt == 0L) return
            callStatusTv.text="Connected · ${duration((android.os.SystemClock.elapsedRealtime()-connectedAt)/1000)}"
            handler.postDelayed(this,1000)
        }
    }
    private val callTimeout = Runnable { if(inCall && connectedAt==0L) { endCall(); toast("Call ka jawab nahi mila") } }
    private val searchTask = Runnable { loadHistory(true) }
    private fun duration(seconds: Long) = "%d:%02d".format(seconds/60,seconds%60)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var db: Db
    private var netCall: InternetVoiceCall? = null
    private var inCall = false
    private var callAudioMuted = false
    private var callSpeaker = false
    private var pendingInitiate = true
    private var callId = -1L
    private var lastTypingSent = 0L

    private val statusTick = object : Runnable {
        override fun run() { updateStatus(); handler.postDelayed(this, 4000) }
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
        searchQuery=intent.getStringExtra("searchQuery").orEmpty()
        buildUi()
        RabtaEvents.add(this)
        loadHistory()
        if (intent.getBooleanExtra("autoAnswer", false)) handler.post { startCall(false) }
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
        handler.post(statusTick); RabtaEvents.add(this)
        Thread { try { db.clearUnread(peerId) } catch (_: Exception) {} }.start()
        if (!isGroup) WsClient.sendRead(peerId, "all")
    }
    override fun onPause() { super.onPause(); openPeer = null; handler.removeCallbacks(statusTick); if(recording) stopVoiceRecord(false) }
    override fun onDestroy() {
        endCall(); if(recording) stopVoiceRecord(false); RabtaEvents.remove(this)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun pill(c: Int, r: Int = 999) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun oval(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun fmtTime(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

    private fun avatar(name: String, sz: Int): TextView {
        val cols = listOf(0xFFE53935,0xFF9C27B0,0xFF3F51B5,0xFF009688,0xFF43A047,0xFFFF5722,0xFF607D8B)
        return TextView(this).apply {
            text = name.filter { it.isLetterOrDigit() }.take(1).uppercase().ifEmpty { "?" }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sz*0.38f); includeFontPadding=false; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = oval(cols[(name.hashCode() and 0x7FFFFFFF)%cols.size].toInt())
        }
    }

    private fun buildUi() {
        window.statusBarColor = AppTheme.bg(this@ChatActivity)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BEIGE); isFocusableInTouchMode=true }
        setContentView(root)

        // ── Header ──
        val hdr = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setBackgroundColor(RECV); setPadding(dp(6),dp(8),dp(10),dp(8)) }
        hdr.addView(TextView(this).apply { UiIcons.set(this,"back",TXT,label="Back"); textSize=22f; setTextColor(Color.WHITE); setPadding(dp(4),dp(4),dp(8),dp(4)); isClickable=true; setOnClickListener{finish()} })
        hdr.addView(avatar(peerName, dp(40)), LinearLayout.LayoutParams(dp(40),dp(40)))
        val hdrCol = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),0,0,0) }
        hdrCol.addView(TextView(this).apply { text=peerName; textSize=17f; typeface=Typeface.DEFAULT_BOLD; setTextColor(TXT); maxLines=1 })
        val subRow = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        statusTv  = TextView(this).apply { textSize=12f; setTextColor(SUB) }
        typingTv  = TextView(this).apply { textSize=12f; setTextColor(SUB); text="typing..."; visibility=View.GONE }
        subRow.addView(statusTv); subRow.addView(typingTv)
        hdrCol.addView(subRow)
        hdr.addView(hdrCol, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        if (!isGroup) hdr.addView(headerBtn("📞") { startCall(true) })
        if (!isGroup) hdr.addView(headerBtn("🎥") { startVideoCall() })
        hdr.addView(TextView(this).apply { UiIcons.set(this,"search",SUB,label="Search chat"); gravity=Gravity.CENTER; setOnClickListener { searchField.visibility=if(searchField.visibility==View.VISIBLE) View.GONE else View.VISIBLE } },LinearLayout.LayoutParams(dp(44),dp(48)))
        headerBar=hdr
        root.addView(hdr)

        // ── Call overlay (WhatsApp style) ──
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
        // Bottom: button panel (WhatsApp style 2-row grid)
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
                    handler.removeCallbacks(searchTask); handler.postDelayed(searchTask,200)
                }
                override fun afterTextChanged(s:Editable?) {}
            })
        }
        root.addView(searchField)

        // ── Chat area ──
        chatScroll = ScrollView(this).apply { setBackgroundColor(BEIGE) }
        chatBox = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(8),dp(10),dp(8)) }
        chatScroll.addView(chatBox)
        root.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))

        // ── Input bar ──
        recordingPanel=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setBackgroundColor(RECV); visibility=View.GONE }
        recordingTv=TextView(this).apply { setTextColor(RED); textSize=13f; setPadding(dp(12),dp(12),0,dp(12)); accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        recordingPanel.addView(recordingTv,LinearLayout.LayoutParams(0,-2,1f))
        recordingPanel.addView(TextView(this).apply { text="Cancel"; setTextColor(RED); minHeight=dp(48); gravity=Gravity.CENTER; setPadding(dp(12),0,dp(12),0); setOnClickListener { stopVoiceRecord(false) } })
        root.addView(recordingPanel)
        val bar = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.BOTTOM; setBackgroundColor(RECV); setPadding(dp(6),dp(6),dp(6),dp(6)) }
        bar.addView(headerBtn("📎") { showAttachments() }.apply { background=null })
        bar.addView(TextView(this).apply { UiIcons.set(this,"sticker",GREEN,label="Stickers"); gravity=Gravity.CENTER; setOnClickListener { Stickers.picker(this@ChatActivity,{sendContent(it,"sticker")},{pickAttachment("image/*",104)}) } },LinearLayout.LayoutParams(dp(40),dp(48)))
        input = EditText(this).apply {
            hint="Message"; textSize=16f; setTextColor(TXT); setHintTextColor(SUB); maxLines=5
            background = GradientDrawable().apply { setColor(BEIGE); cornerRadius=dp(24).toFloat() }
            setPadding(dp(14),dp(10),dp(14),dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object:TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){
                    val has = !s.isNullOrBlank()
                    sendBtn.visibility = if(has) View.VISIBLE else View.GONE
                    micBtn.visibility  = if(has) View.GONE   else View.VISIBLE
                    val now = System.currentTimeMillis()
                    if (has && now - lastTypingSent > 2500) { lastTypingSent=now; handler.postDelayed({ WsClient.sendTyping(peerId) }, 300) }
                }
                override fun afterTextChanged(s:Editable?){}
            })
        }
        bar.addView(input, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply { marginStart=dp(4); marginEnd=dp(4) })
        sendBtn = TextView(this).apply {
            UiIcons.set(this,"send",label="Send message"); textSize=20f; setTextColor(Color.WHITE); gravity=Gravity.CENTER
            background=oval(GREEN2); isClickable=true; setOnClickListener{sendMsg()}; visibility=View.GONE
        }
        bar.addView(sendBtn, LinearLayout.LayoutParams(dp(46),dp(46)))
        micBtn = TextView(this).apply {
            UiIcons.set(this,"mic",label="Hold to record; release to send"); textSize=20f; gravity=Gravity.CENTER; background=oval(GREEN2)
            setOnTouchListener { _,ev -> when(ev.action){ MotionEvent.ACTION_DOWN->{ checkAudioPermission{startVoiceRecord()}; true }; MotionEvent.ACTION_MOVE->{ if(ev.x < -dp(48)) stopVoiceRecord(false); true }; MotionEvent.ACTION_UP->{ stopVoiceRecord(true); true }; MotionEvent.ACTION_CANCEL->{ stopVoiceRecord(false); true }; else->false } }
        }
        bar.addView(micBtn, LinearLayout.LayoutParams(dp(46),dp(46)))
        composerBar=bar
        root.addView(bar)
    }

    // WhatsApp-style call button in panel
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

    // ── Chat history ──
    private fun loadHistory(forceScroll: Boolean = false) {
        val version=++historyVersion
        val query=searchQuery
        val oldY=chatScroll.scrollY
        val atBottom=chatScroll.getChildAt(0).height <= chatScroll.height + oldY + dp(80)
        Thread {
            val msgs=try { db.searchMessages(peerId,query) } catch (_:Exception) { emptyList() }
            handler.post {
                if(isDestroyed || version!=historyVersion) return@post
                chatBox.removeAllViews()
                var lastDay=""
                msgs.forEach { m ->
                    val day=dayKey(m.sentAt)
                    if(day!=lastDay) { addDateSeparator(m.sentAt); lastDay=day }
                    val before=chatBox.childCount
                    addBubble(m.body,m.mine,fmtTime(m.sentAt),m.status,m.msgType,if(isGroup&&!m.mine)m.fromName else "")
                    if(m.mine && m.status in setOf("failed","expired") && chatBox.childCount>before) {
                        val row=chatBox.getChildAt(chatBox.childCount-1) as ViewGroup
                        row.getChildAt(0).apply { isClickable=true; contentDescription="Message failed. Tap to retry"; setOnClickListener { Outbox.retry(m,isGroup) } }
                    }
                }
                if(msgs.isEmpty() && query.isNotBlank()) chatBox.addView(TextView(this).apply { text="Koi message nahi mila — search clear karein"; gravity=Gravity.CENTER; setTextColor(SUB) })
                if(forceScroll || atBottom) scrollBottom() else chatScroll.post { chatScroll.scrollTo(0,oldY) }
            }
        }.start()
    }
    private fun dayKey(time: Long)=SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date(time))
    private fun addDateSeparator(time: Long) {
        val yesterday=java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR,-1) }
        val title=when(dayKey(time)) { dayKey(System.currentTimeMillis())->"Today"; dayKey(yesterday.timeInMillis)->"Yesterday"; else->SimpleDateFormat("dd MMM yyyy",Locale.getDefault()).format(Date(time)) }
        chatBox.addView(TextView(this).apply { text=title; textSize=12f; setTextColor(SUB); gravity=Gravity.CENTER; setPadding(dp(12),dp(6),dp(12),dp(6)); background=pill(RECV,12) },LinearLayout.LayoutParams(-2,-2).apply { gravity=Gravity.CENTER; topMargin=dp(12); bottomMargin=dp(8) })
    }

    // ── Message bubbles ──
    private fun addBubble(body: String, mine: Boolean, time: String, status: String="sent", msgType: String="text", sender: String="") {
        if (msgType=="text" && body.length>3000 && body.matches(Regex("[A-Za-z0-9+/=]+"))) return
        val wrap = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=if(mine) Gravity.END else Gravity.START }
        val bubble = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(if(mine) SENT else RECV); cornerRadius=dp(12).toFloat(); if(!mine) setStroke(dp(1),0x11000000) }
            setPadding(dp(10),dp(8),dp(10),dp(5)); elevation=dp(1).toFloat()
        }
        if (sender.isNotEmpty()) {
            val senderColors = listOf(0xFFE53935,0xFF9C27B0,0xFF3F51B5,0xFF009688)
            bubble.addView(TextView(this).apply { text=sender; textSize=13f; typeface=Typeface.DEFAULT_BOLD; setTextColor(senderColors[(sender.hashCode() and 0x7FFFFFFF)%senderColors.size].toInt()) })
        }
        when (msgType) {
            "image" -> {
                val bmp = MediaHelper.base64ToBitmap(body)
                if (bmp != null) {
                    val iv = ImageView(this).apply { setImageBitmap(bmp); scaleType=ImageView.ScaleType.CENTER_CROP; adjustViewBounds=true; background=pill(AppTheme.line(this@ChatActivity),8) }
                    bubble.addView(iv, LinearLayout.LayoutParams(dp(220),dp(200)))
                } else bubble.addView(textLine("📷 Photo"))
            }
            "voice" -> { voiceNoteBubble(body,mine,time,status); return }
            "file", "video" -> {
                bubble.addView(textLine(if(msgType=="video") "▶  ${Attachments.name(body)}" else "▤  ${Attachments.name(body)}"))
                bubble.addView(textLine("${Attachments.details(body)} · Tap to open").apply { textSize=12f; setTextColor(SUB) })
                bubble.setOnClickListener { Attachments.open(this,body) }
            }
            "sticker" -> {
                val iv=ImageView(this).apply { contentDescription="Sticker"; scaleType=ImageView.ScaleType.FIT_CENTER }
                if(Stickers.valid(body)) iv.setImageDrawable(Stickers.drawable(body))
                else runCatching { iv.setImageURI(android.net.Uri.fromFile(Attachments.file(this,body))) }
                iv.setOnLongClickListener { Stickers.remember(this,body); toast("Sticker saved to Recent"); true }
                bubble.addView(iv,LinearLayout.LayoutParams(dp(150),dp(150)))
            }
            else    -> bubble.addView(textLine(body))
        }
        if (msgType != "voice") bubble.addView(msgFooter(time, mine, status))
        val maxW = (resources.displayMetrics.widthPixels * 0.78).toInt()
        wrap.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin=dp(2); bottomMargin=dp(2)
            if (mine) marginStart=resources.displayMetrics.widthPixels-maxW else marginEnd=resources.displayMetrics.widthPixels-maxW
        })
        chatBox.addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // WhatsApp-style voice note
    private fun voiceNoteBubble(body: String, mine: Boolean, time: String, status: String) {
        val bubble = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(if(mine) SENT else RECV); cornerRadius=dp(12).toFloat(); if(!mine) setStroke(dp(1),0x11000000) }
            setPadding(dp(12),dp(10),dp(12),dp(6)); elevation=dp(1).toFloat()
        }
        // Row: play + waveform + duration
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        var playing = false; var player: android.media.MediaPlayer? = null
        val playBtn = TextView(this).apply {
            UiIcons.set(this,"play",GREEN,label="Play voice note"); textSize=20f; setTextColor(if(mine) 0xFF075E54.toInt() else GREEN)
            background=oval(if(mine) 0x33128C7E else 0x33128C7E); gravity=Gravity.CENTER
            setPadding(dp(10),dp(10),dp(10),dp(10))
            isClickable=true; setOnClickListener {
                if (playing) { player?.stop(); player?.release(); player=null; playing=false; UiIcons.set(this,"play",GREEN,label="Play voice note") }
                else {
                    player=MediaHelper.playVoice(context,body) { playing=false; UiIcons.set(this,"play",GREEN,label="Play voice note"); player=null }
                    if(player!=null) { playing=true; UiIcons.set(this,"pause",GREEN,label="Stop voice note") }
                }
            }
        }
        row.addView(playBtn, LinearLayout.LayoutParams(dp(42),dp(42)).apply { marginEnd=dp(8) })
        // Waveform bars (static, seeded from body hash)
        val waveCol = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.BOTTOM }
        val barHeights = listOf(8,18,12,28,22,16,30,20,14,26,18,12,24,32,18,14,22,28,16,20)
        val barColor = if (mine) 0xFF128C7E.toInt() else 0xFF667781.toInt()
        val barColorPlayed = if (mine) 0xFF075E54.toInt() else GREEN
        barHeights.forEach { h ->
            waveCol.addView(View(this).apply { background=pill(barColor,2) }, LinearLayout.LayoutParams(dp(3),(h*resources.displayMetrics.density).toInt()).apply { marginEnd=dp(2) })
        }
        row.addView(waveCol, LinearLayout.LayoutParams(0,dp(36),1f))
        // Duration estimate
        val durSec = maxOf(1, body.length * 3 / 4 / 4000)
        row.addView(TextView(this).apply {
            text=" ~${duration(durSec.toLong())}"; textSize=12f; setTextColor(SUB); setPadding(dp(6),0,0,0)
        })
        bubble.addView(row)
        bubble.addView(msgFooter(time, mine, status))
        val maxW = (resources.displayMetrics.widthPixels * 0.78).toInt()
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin=dp(2); bottomMargin=dp(2)
            if (mine) marginStart=resources.displayMetrics.widthPixels-maxW else marginEnd=resources.displayMetrics.widthPixels-maxW
        }
        val wrap = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=if(mine) Gravity.END else Gravity.START }
        wrap.addView(bubble, lp)
        chatBox.addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun textLine(t: String) = TextView(this).apply { text=t; textSize=15f; setTextColor(TXT); setLineSpacing(0f,1.2f) }
    private fun msgFooter(time: String, mine: Boolean, status: String) = LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.END or Gravity.BOTTOM; setPadding(dp(4),dp(2),0,0)
        addView(TextView(this@ChatActivity).apply { this.text=time; textSize=11f; setTextColor(SUB) })
        if (mine) addView(TextView(this@ChatActivity).apply {
            this.text = "  " + MessageDelivery.label(status)
            contentDescription=MessageDelivery.label(status)
            textSize=10f; setTextColor(if(status=="read") 0xFF0277BD.toInt() else if(status=="failed") RED else SUB)
        })
    }
    private fun scrollBottom() { handler.post { chatScroll.fullScroll(View.FOCUS_DOWN) } }
    private fun updateStatus() { statusTv.text="Connection: " + ConnectionStatus.text(this) }

    // ── Send ──
    private fun sendMsg() {
        val text = input.text.toString().trim(); if (text.isEmpty()) return
        sendContent(text, "text"); input.setText("")
    }
    private fun sendContent(body: String, msgType: String) {
        if(!WsClient.connected && (isGroup || msgType !in setOf("text","sticker") || (msgType=="sticker" && !Stickers.valid(body)))) { toast("Videos, files and media need internet. Nearby supports text and built-in stickers."); return }
        if(msgType=="text" && body.toByteArray().size>12000) { toast("Message is too long (12 KB maximum)"); return }
        if(msgType=="sticker") Stickers.remember(this,body)
        Outbox.send(peerId,peerName,isGroup,body,msgType)
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
                handler.post {
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
            handler.post {
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
        android.app.AlertDialog.Builder(this).setTitle("Share with $peerName")
            .setItems(arrayOf("Photo", "Video · up to 8 MB", "Document / file · up to 8 MB", "Stickers", "Offline security")) { _,which ->
                when(which) { 0->pickImage(); 1->pickAttachment("video/*",102); 2->pickAttachment("*/*",103); 3->Stickers.picker(this,{sendContent(it,"sticker")},{pickAttachment("image/*",104)}); 4->MeshSecurity.showContact(this,peerId) }
            }.show()
    }

    private fun startVoiceRecord() {
        if(recording || inCall || !hasMic()) return
        if(!MediaHelper.startRecording(this)) { toast("Recording shuru nahi hui"); return }
        recording=true; recordingStarted=android.os.SystemClock.elapsedRealtime()
        recordingPanel.visibility=View.VISIBLE; handler.post(recordingTick)
    }
    private fun stopVoiceRecord(send:Boolean) {
        if(!recording) return
        recording=false; handler.removeCallbacks(recordingTick); recordingPanel.visibility=View.GONE
        if(!send) { MediaHelper.cancelRecording(); return }
        Thread {
            val result=MediaHelper.stopRecording()
            handler.post { if(!isDestroyed) { if(result!=null) sendContent(result.first,"voice") else toast("Recording bohat chhoti thi; dobara try karein") } }
        }.start()
    }
    private fun hasMic() = ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    private fun checkAudioPermission(then: ()->Unit) { if (hasMic()) then() else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO),98) }

    // ── Voice Call ──
    private fun startCall(initiate: Boolean) {
        if(inCall || isGroup) return
        if(!hasMic()) { pendingInitiate=initiate; ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECORD_AUDIO),99); return }
        if(!WsClient.connected) { toast("Call ke liye internet chahiye"); return }
        if(recording) stopVoiceRecord(false)
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
        inCall=true; connectedAt=0L; callAudioMuted=false; callSpeaker=false
        updateCallControls()
        val am=getSystemService(AUDIO_SERVICE) as AudioManager
        previousAudioMode=am.mode; previousSpeaker=am.isSpeakerphoneOn
        am.mode=AudioManager.MODE_IN_COMMUNICATION; am.isSpeakerphoneOn=false
        headerBar.visibility=View.GONE; window.statusBarColor=DARK; window.decorView.systemUiVisibility=0
        callOverlay.visibility=View.VISIBLE; chatScroll.visibility=View.GONE; composerBar.visibility=View.GONE; searchField.visibility=View.GONE
        callStatusTv.text=if(initiate) "Ringing…" else "Connecting…"
        callId=db.logCallStart(peerId,peerName,if(initiate)"outgoing" else "incoming")
        netCall=InternetVoiceCall(peerId) { status ->
            if(inCall) {
                callStatusTv.text=status
                if(status.startsWith("Connected") && connectedAt==0L) {
                    connectedAt=android.os.SystemClock.elapsedRealtime(); handler.removeCallbacks(callTimeout); handler.post(callTick)
                }
            }
        }
        netCall?.start(initiate)
        handler.postDelayed(callTimeout,45000)
    }
    private fun startVideoCall() {
        if(isGroup) return
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
    private fun endCall(sendEnd:Boolean=true) {
        if(!inCall) return
        inCall=false; netCall?.stop(sendEnd); netCall=null
        handler.removeCallbacks(callTick); handler.removeCallbacks(callTimeout)
        val seconds=if(connectedAt==0L) 0 else ((android.os.SystemClock.elapsedRealtime()-connectedAt)/1000).toInt()
        if(callId>0) db.logCallEnd(callId,seconds)
        callId=-1L; connectedAt=0L
        try { (getSystemService(AUDIO_SERVICE) as AudioManager).apply { isSpeakerphoneOn=previousSpeaker; mode=previousAudioMode } } catch (_:Exception) {}
        headerBar.visibility=View.VISIBLE; window.statusBarColor=BEIGE; window.decorView.systemUiVisibility=if(AppTheme.dark(this)) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        callOverlay.visibility=View.GONE; chatScroll.visibility=View.VISIBLE; composerBar.visibility=View.VISIBLE; searchField.visibility=if(searchQuery.isBlank()) View.GONE else View.VISIBLE
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
        handler.post { typingTv.visibility=View.VISIBLE; statusTv.visibility=View.GONE; handler.removeCallbacks(typingHide); handler.postDelayed(typingHide,3000) }
    }
    override fun onCallAudio(fromId:String,data:String) {
        if (fromId!=peerId) return
        // Audio alone must never auto-answer an incoming call.
        if(inCall) netCall?.onAudio(data)
    }
    override fun onCallEnd(fromId:String) { if(fromId==peerId) handler.post{endCall(false);toast("Call khatam")} }
    override fun onVideoEnd(callId:String) {}
}
