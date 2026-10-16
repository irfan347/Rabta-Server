package com.irfan.rabta

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen popup for incoming calls. Appears on lock screen too. */
class IncomingCallActivity : ThemedActivity(), RabtaEvents.Listener {

    private val G = AppTheme.ACCENT
    private val RED = 0xFFD93025.toInt()
    private val BG get() = AppTheme.bg(this@IncomingCallActivity)

    private lateinit var fromId: String
    private lateinit var fromName: String
    private var callId: String = ""
    private var offerSdp: String? = null
    private var isVideo: Boolean = false
    private val handler = Handler(Looper.getMainLooper())
    private var answered = false

    // Auto-decline after 30 seconds
    private val autoDecline = Runnable { if (!answered) decline() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromId   = intent.getStringExtra("fromId")   ?: ""
        fromName = intent.getStringExtra("fromName") ?: "Someone"
        callId   = intent.getStringExtra("callId")   ?: ""
        offerSdp = intent.getStringExtra("offerSdp")
        isVideo  = intent.getBooleanExtra("isVideo", false)

        // Show on lock screen + turn screen on
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        buildUi()
        RabtaEvents.add(this)
        handler.postDelayed(autoDecline, 30_000)
    }

    override fun onCallEnd(fromId: String, callId: String) {
        if (fromId == this.fromId && callId==this.callId && !answered) { answered = true; cancelNotif(); finish() }
    }

    private fun cancelNotif() {
        try { (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(MessageService.CALL_NOTIF) } catch (_: Exception) {}
    }

    override fun onBackPressed() { decline() }
    override fun onDestroy() { handler.removeCallbacks(autoDecline); RabtaEvents.remove(this); super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(BG); setPadding(dp(32), dp(60), dp(32), dp(60))
        }
        setContentView(root)

        // Caller avatar
        root.addView(TextView(this).apply {
            val cols = listOf(0xFF2196F3, 0xFF9C27B0, 0xFF009688)
            text = fromName.filter { it.isLetterOrDigit() }.take(1).uppercase().ifEmpty { "?" }
            textSize = 40f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cols[(fromName.hashCode() and 0x7FFFFFFF) % cols.size].toInt()) }
        }, LinearLayout.LayoutParams(dp(100), dp(100)).apply { gravity = Gravity.CENTER })

        root.addView(TextView(this).apply {
            text = fromName; textSize = 28f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppTheme.text(this@IncomingCallActivity)); gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = if(isVideo) "Incoming video call…" else "Incoming voice call…"; textSize = 16f
            setTextColor(0xFF8696A0.toInt()); gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(60))
        })

        // Buttons row
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }

        fun circleBtn(emoji: String, bg: Int, label: String, onClick: () -> Unit): LinearLayout {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), 0) }
            col.addView(TextView(this).apply {
                UiIcons.set(this,UiIcons.fromSymbol(emoji) ?: "phone",size=28,label=label); textSize = 30f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
                setPadding(dp(22), dp(22), dp(22), dp(22)); isClickable = true; setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(dp(72), dp(72)))
            col.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(0xFF8696A0.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
            return col
        }

        btns.addView(circleBtn("📵", RED, "Decline") { decline() })
        btns.addView(circleBtn(if (isVideo) "🎥" else "📞", G, "Answer") { answer() })
        root.addView(btns)
    }

    private fun answer() {
        answered = true; cancelNotif()
        if (isVideo && offerSdp != null) {
            startActivity(android.content.Intent(this, VideoCallActivity::class.java)
                .putExtra(VideoCallActivity.PEER_ID, fromId).putExtra(VideoCallActivity.PEER_NAME, fromName)
                .putExtra(VideoCallActivity.IS_CALLER, false).putExtra(VideoCallActivity.CALL_ID, callId)
                .putExtra(VideoCallActivity.OFFER_SDP, offerSdp)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } else {
            startActivity(Intent(this, ChatActivity::class.java)
                .putExtra("peerId", fromId).putExtra("peerName", fromName).putExtra("autoAnswer", true).putExtra("voiceCallId",callId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }

    private fun decline() {
        answered = true
        cancelNotif()
        WsClient.sendRaw(org.json.JSONObject().put("type",if(isVideo) "video_end" else "call_end").put("to",fromId).put("callId",callId).toString())
        finish()
    }
}
