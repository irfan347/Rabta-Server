package com.irfan.rabta

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

class AuthActivity : ThemedActivity() {

    private lateinit var usernameField: EditText
    private lateinit var pinField: EditText
    private lateinit var statusText: TextView
    private lateinit var loginBtn: TextView
    private lateinit var registerBtn: TextView
    private lateinit var loading: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        if (Session.loggedIn && !intent.getBooleanExtra("reauth",false)) { goHome(); return }
        buildUi()
        if(intent.getBooleanExtra("reauth",false)){usernameField.setText(Session.username);statusText.text="Sign in again to reconnect. Your local chats stay on this phone.";registerBtn.visibility=View.GONE}
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun pill(c: Int, r: Int = 999) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat()
    }

    private fun buildUi() {
        // Graphite branded header, themed form below
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL;setBackgroundColor(AppTheme.bg(this@AuthActivity));isFocusableInTouchMode=true }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(ScrollView(this).apply { isFillViewport=true; addView(root) })

        // ---- TOP (brand) ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.rabta_graphite))
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        top.addView(RabtaBrand.lockup(this, 48, Color.WHITE, 34f))
        top.addView(TextView(this).apply {
            text = "رابطہ"; textSize = 20f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
        })
        top.addView(TextView(this).apply {
            text = "Offline + online chat"; textSize = 14f
            setTextColor(0xAAFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(top)

        // ---- BOTTOM (white card) ----
        val bot = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(AppTheme.bg(this@AuthActivity)) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        bot.addView(col)
        root.addView(bot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setTextColor(AppTheme.accent(this@AuthActivity))
            typeface = Typeface.DEFAULT_BOLD; setPadding(dp(4), 0, 0, dp(4))
        }
        fun field(hint: String, pwd: Boolean = false) = EditText(this).apply {
            this.hint = hint; textSize = 16f
            setTextColor(AppTheme.text(this@AuthActivity)); setHintTextColor(AppTheme.sub(this@AuthActivity))
            setSingleLine(true); minHeight=dp(52);background=RabtaUi.shape(this@AuthActivity,AppTheme.surface(this@AuthActivity),14,AppTheme.line(this@AuthActivity))
            setPadding(dp(12), dp(14), dp(12), dp(14))
            inputType = if (pwd) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT
        }
        fun divider() = View(this).apply {
            setBackgroundColor(AppTheme.line(this@AuthActivity))
        }.also { col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))) }
        fun lp(t: Int = 0, b: Int = 0) = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(t); bottomMargin = dp(b) }

        // Username
        col.addView(label("Username"), lp(b = 2))
        usernameField = field("e.g. irfan347"); col.addView(usernameField)
        divider()

        // PIN
        col.addView(label("PIN"), lp(t = 18, b = 2))
        pinField = field("4-8 numbers", pwd = true).apply {
            filters=arrayOf(android.text.InputFilter.LengthFilter(8))
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ -> if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { doLogin(); true } else false }
        }
        val pinRow=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        pinRow.addView(pinField,LinearLayout.LayoutParams(0,-2,1f))
        pinRow.addView(TextView(this).apply {
            text="Show"; contentDescription="Show PIN"; setTextColor(AppTheme.accent(this@AuthActivity)); gravity=Gravity.CENTER
            minWidth=dp(48); minHeight=dp(48); isClickable=true
            setOnClickListener {
                val visible=pinField.transformationMethod is android.text.method.PasswordTransformationMethod
                pinField.transformationMethod=if(visible) android.text.method.HideReturnsTransformationMethod.getInstance() else android.text.method.PasswordTransformationMethod.getInstance()
                pinField.setSelection(pinField.length()); text=if(visible) "Hide" else "Show"; contentDescription=if(visible) "Hide PIN" else "Show PIN"
            }
        })
        col.addView(pinRow)
        divider()

        // Status
        statusText = TextView(this).apply {
            textSize = 14f; setTextColor(AppTheme.error(this@AuthActivity)); gravity = Gravity.CENTER;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        col.addView(statusText, lp(t = 14, b = 4))

        loading=RabtaUi.loading(this,"Connecting…").apply { visibility=View.GONE }
        col.addView(loading)
        // Buttons
        loginBtn = bigBtn("LOGIN", AppTheme.ACCENT) { doLogin() }
        registerBtn = bigBtn("SIGN UP", Color.WHITE, outline = true) { doRegister() }
        col.addView(loginBtn, lp(t = 16, b = 10))
        col.addView(registerBtn)

        col.addView(TextView(this).apply {
            text = "Stay close, online or nearby."
            textSize = 12f; setTextColor(AppTheme.sub(this@AuthActivity)); gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })
    }

    private fun bigBtn(text:String,bg:Int,outline:Boolean=false,onClick:()->Unit) =
        RabtaUi.button(this,if(text=="LOGIN") "Sign in" else "Create account",!outline,onClick).apply { gravity=Gravity.CENTER }

    private fun validate(): Boolean {
        val u = usernameField.text.toString().trim()
        val p = pinField.text.toString().trim()
        return when {
            u.length < 3  -> { statusText.text = "Username 3+ characters hona chahiye"; false }
            !u.matches(Regex("[a-zA-Z0-9_]+")) -> { statusText.text = "Sirf letters, numbers aur _"; false }
            !p.matches(Regex("[0-9]{4,8}")) -> { statusText.text = "PIN 4-8 numbers ka hona chahiye"; false }
            else -> true
        }
    }

    private fun setLoading(on: Boolean) {
        loginBtn.isEnabled = !on; registerBtn.isEnabled = !on
        loginBtn.isClickable = !on; registerBtn.isClickable = !on
        loading.visibility=if(on) View.VISIBLE else View.GONE
        loginBtn.alpha = if (on) 0.5f else 1f; registerBtn.alpha = if (on) 0.5f else 1f
    }

    private fun doLogin() {
        if (!validate()) return
        statusText.text = "Login ho raha hai..."; statusText.setTextColor(AppTheme.accent(this@AuthActivity)); setLoading(true)
        signIn(usernameField.text.toString().trim().lowercase(), pinField.text.toString().trim(), null)
    }

    private fun signIn(un: String, pin: String, recoveryCode: String?) {
        setLoading(true)
        WsClient.login(this, Config.SERVER, un, pin, recoveryCode) { ok, msg, data ->
            setLoading(false)
            if (ok && data != null && data.optBoolean("pending")) waitForApproval(un, pin, data.optString("requestId"))
            else if (ok && data != null) saveAndGo(data) else { statusText.text = msg; statusText.setTextColor(AppTheme.error(this)) }
        }
    }

    /** A new phone: the PIN was right, but a trusted phone must allow it (or the recovery code is used). */
    private fun waitForApproval(un: String, pin: String, requestId: String) {
        statusText.text = "Approve this sign-in on your other Rabta phone"; statusText.setTextColor(AppTheme.accent(this))
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var active = true
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Waiting for approval")
            .setMessage("Open Rabta on your trusted phone and tap Allow. Lost that phone? Use your recovery code.")
            .setCancelable(false)
            .setNeutralButton("Use recovery code") { _, _ -> active = false; AccountUi.askRecoveryCode(this) { code -> if (!code.isNullOrBlank()) signIn(un, pin, code) } }
            .setNegativeButton("Cancel") { _, _ -> active = false; statusText.text = "Sign-in cancelled" }.show()
        fun poll() {
            if (!active || isFinishing) return
            WsClient.pollLogin(Config.SERVER, requestId) { ok, msg, data ->
                if (!active) return@pollLogin
                when {
                    ok && data != null && data.has("token") -> { active = false; dialog.dismiss(); saveAndGo(data) }
                    ok -> handler.postDelayed({ poll() }, 3000)
                    else -> { active = false; dialog.dismiss(); statusText.text = msg; statusText.setTextColor(AppTheme.error(this)) }
                }
            }
        }
        handler.postDelayed({ poll() }, 3000)
    }

    private fun doRegister() {
        if (!validate()) return
        val un = usernameField.text.toString().trim().lowercase()
        statusText.text = "Account ban raha hai..."; statusText.setTextColor(AppTheme.accent(this@AuthActivity)); setLoading(true)
        WsClient.register(this, Config.SERVER, un, pinField.text.toString().trim(), un) { ok, msg, data ->
            setLoading(false)
            if (ok && data != null) saveAndGo(data) else { statusText.text = msg; statusText.setTextColor(AppTheme.error(this)) }
        }
    }

    private fun saveAndGo(data: JSONObject) {
        if(intent.getBooleanExtra("reauth",false)&&data.optString("id")!=Session.id){statusText.text="Use the same account as before. Local chats have not been changed.";return}
        WsClient.disconnect()
        Session.id = data.optString("id"); Session.username = data.optString("username")
        Session.name = data.optString("name").ifEmpty { data.optString("username") }
        Session.token = data.optString("token"); Session.serverUrl = Config.SERVER
        val code = data.optString("recoveryCode")
        if (code.isNotEmpty()) AccountUi.showRecoveryCode(this, code) { goHome() } else goHome()
    }

    private fun goHome() { startActivity(Intent(this, ConversationListActivity::class.java)); finish() }
}
