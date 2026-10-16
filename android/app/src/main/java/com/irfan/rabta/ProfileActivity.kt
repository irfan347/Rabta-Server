package com.irfan.rabta

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ProfileActivity : ThemedActivity() {

    private val GREEN get() = AppTheme.ACCENT
    private val WHITE get() = AppTheme.surface(this@ProfileActivity)
    private val TXT get() = AppTheme.text(this@ProfileActivity)
    private val SUB get() = AppTheme.sub(this@ProfileActivity)
    private val PICK  = 101
    private lateinit var picView: ImageView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        buildUi()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun pill(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(999).toFloat() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL;setBackgroundColor(AppTheme.bg(this@ProfileActivity)) }
        setContentView(root)

        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(AppTheme.bg(this@ProfileActivity)); setPadding(dp(8),dp(10),dp(16),dp(10)) }
        hdr.addView(RabtaUi.iconButton(this,"back","Back") { finish() })
        hdr.addView(TextView(this).apply { text = "Profile & settings"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(TXT) })
        root.addView(hdr)

        val body = ScrollView(this).apply { setBackgroundColor(AppTheme.bg(this@ProfileActivity)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24),dp(32),dp(24),dp(32)); gravity = Gravity.CENTER_HORIZONTAL }
        body.addView(col); root.addView(body,LinearLayout.LayoutParams(-1,0,1f))

        // Avatar / pic
        picView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = true; setOnClickListener { pickPhoto() }
        }
        if (Session.profilePic.isNotEmpty()) {
            picView.background = pill(0xFF607D8B.toInt())
            MediaHelper.base64ToBitmap(Session.profilePic)?.let { picView.setImageBitmap(it) }
        } else {
            // initials
            picView.background = pill(AppTheme.ACCENT)
        }
        val picFrame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        picFrame.addView(picView, LinearLayout.LayoutParams(dp(120), dp(120)))
        picFrame.addView(TextView(this).apply { text = "Tap to change photo"; textSize = 13f; setTextColor(SUB); gravity = Gravity.CENTER; setPadding(0,dp(8),0,0) })
        col.addView(picFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(28) })

        // Info
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable().apply { setColor(WHITE); cornerRadius = dp(16).toFloat() }; setPadding(dp(20),dp(16),dp(20),dp(16)) }
        fun infoRow(label: String, value: String) {
            card.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(AppTheme.accent(this@ProfileActivity)); typeface = Typeface.DEFAULT_BOLD })
            card.addView(TextView(this).apply { text = value; textSize = 18f; setTextColor(TXT); setPadding(0,dp(4),0,dp(14)) })
        }
        infoRow("Name", Session.name)
        card.addView(View(this).apply { setBackgroundColor(AppTheme.line(this@ProfileActivity)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        card.addView(View(this).apply { minimumHeight = dp(8) })
        infoRow("Username", "@${Session.username}")
        infoRow("Rabta ID", Session.id)
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20) })

        col.addView(TextView(this).apply {
            text="Appearance  ·  ${AppTheme.mode(this@ProfileActivity)}"; textSize=16f; setTextColor(TXT); background=pill(WHITE); setPadding(dp(20),dp(18),dp(20),dp(18))
            setOnClickListener { AppTheme.choose(this@ProfileActivity) }
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(16) })
        col.addView(TextView(this).apply {
            text="Nearby mesh\nBluetooth & Wi-Fi · 8 hops · 24-hour expiry\n${MeshBridge.peersConnected} peers connected\nText is saved for later delivery. Keep Nearby permission enabled."
            textSize=14f; setTextColor(SUB); setPadding(0,0,0,dp(20))
        })
        col.addView(TextView(this).apply { text="Offline security code"; setTextColor(GREEN); setPadding(0,0,0,dp(20)); setOnClickListener { MeshSecurity.showIdentity(this@ProfileActivity) } })
        // Logout
        col.addView(TextView(this).apply {
            text = "Logout"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(AppTheme.error(this@ProfileActivity)); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(WHITE); cornerRadius = dp(28).toFloat(); setStroke(dp(1), AppTheme.error(this@ProfileActivity)) }
            setPadding(0, dp(14), 0, dp(14)); isClickable = true
            setOnClickListener {
                MessageService.stop(this@ProfileActivity); Session.logout(); WsClient.disconnect()
                startActivity(Intent(this@ProfileActivity, AuthActivity::class.java))
                finishAffinity()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun pickPhoto() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 1); return
        }
        startActivityForResult(Intent(Intent.ACTION_PICK).apply { type = "image/*" }, PICK)
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) pickPhoto()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != PICK || res != RESULT_OK) return
        val uri = data?.data ?: return
        toast("Photo update ho rahi hai...")
        Thread {
            val b64 = MediaHelper.imageToBase64(this, uri)
            if (b64 != null) {
                Session.profilePic = b64
                WsClient.uploadPhoto(Session.serverUrl, Session.token, b64)
                val bmp = MediaHelper.base64ToBitmap(b64)
                handler.post { bmp?.let { picView.setImageBitmap(it) }; toast("Profile picture update ho gayi! ✓") }
            } else handler.post { toast("Photo load nahi ho saki") }
        }.start()
    }
}
