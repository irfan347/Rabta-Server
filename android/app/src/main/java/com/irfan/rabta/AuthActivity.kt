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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        if (Session.loggedIn) { goHome(); return }
        buildUi()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun pill(c: Int, r: Int = 999) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat()
    }

    private fun buildUi() {
        // Full green top half, white bottom
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(ScrollView(this).apply { isFillViewport=true; addView(root) })

        // ---- TOP (green) ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(AppTheme.ACCENT)
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        top.addView(TextView(this).apply {
            text = "Rabta"; textSize = 34f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
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
            text = t; textSize = 13f; setTextColor(AppTheme.ACCENT)
            typeface = Typeface.DEFAULT_BOLD; setPadding(dp(4), 0, 0, dp(4))
        }
        fun field(hint: String, pwd: Boolean = false) = EditText(this).apply {
            this.hint = hint; textSize = 16f
            setTextColor(AppTheme.text(this@AuthActivity)); setHintTextColor(AppTheme.sub(this@AuthActivity))
            setSingleLine(true); background = null
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
            text="Show"; contentDescription="Show PIN"; setTextColor(AppTheme.ACCENT); gravity=Gravity.CENTER
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
            textSize = 13f; setTextColor(0xFFD32F2F.toInt()); gravity = Gravity.CENTER
        }
        col.addView(statusText, lp(t = 14, b = 4))

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

    private fun bigBtn(text: String, bg: Int, outline: Boolean = false, onClick: () -> Unit) =
        TextView(this).apply {
            this.text = text; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(16))
            setTextColor(if (outline) AppTheme.ACCENT else Color.WHITE)
            background = if (outline) GradientDrawable().apply {
                setColor(AppTheme.surface(this@AuthActivity)); cornerRadius = dp(28).toFloat()
                setStroke(dp(2), AppTheme.ACCENT)
            } else pill(bg, 28)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

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
        loginBtn.isClickable = !on; registerBtn.isClickable = !on
        loginBtn.alpha = if (on) 0.5f else 1f; registerBtn.alpha = if (on) 0.5f else 1f
    }

    private fun doLogin() {
        if (!validate()) return
        statusText.text = "Login ho raha hai..."; statusText.setTextColor(AppTheme.ACCENT); setLoading(true)
        WsClient.login(this, Config.SERVER, usernameField.text.toString().trim().lowercase(), pinField.text.toString().trim()) { ok, msg, data ->
            setLoading(false)
            if (ok && data != null) saveAndGo(data) else { statusText.text = msg; statusText.setTextColor(0xFFD32F2F.toInt()) }
        }
    }

    private fun doRegister() {
        if (!validate()) return
        val un = usernameField.text.toString().trim().lowercase()
        statusText.text = "Account ban raha hai..."; statusText.setTextColor(AppTheme.ACCENT); setLoading(true)
        WsClient.register(this, Config.SERVER, un, pinField.text.toString().trim(), un) { ok, msg, data ->
            setLoading(false)
            if (ok && data != null) saveAndGo(data) else { statusText.text = msg; statusText.setTextColor(0xFFD32F2F.toInt()) }
        }
    }

    private fun saveAndGo(data: JSONObject) {
        Session.id = data.optString("id"); Session.username = data.optString("username")
        Session.name = data.optString("name").ifEmpty { data.optString("username") }
        Session.token = data.optString("token"); Session.serverUrl = Config.SERVER
        goHome()
    }

    private fun goHome() { startActivity(Intent(this, ConversationListActivity::class.java)); finish() }
}
