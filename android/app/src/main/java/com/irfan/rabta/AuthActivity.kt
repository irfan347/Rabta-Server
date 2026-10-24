package com.irfan.rabta

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

class AuthActivity : ThemedActivity() {

    private lateinit var usernameField: EditText
    private lateinit var pinField: EditText
    private lateinit var nameField: EditText
    private lateinit var phoneField: EditText
    private var experience = "Standard"
    private lateinit var confirmField: EditText
    private lateinit var usernameHint: TextView
    private lateinit var statusText: TextView
    private lateinit var loginBtn: TextView
    private lateinit var registerBtn: TextView
    private lateinit var heading: TextView
    private lateinit var loading: View
    private val createOnly = mutableListOf<View>()
    private var creating = false
    private var busy = false
    private var checkGeneration = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        if (Session.loggedIn && !intent.getBooleanExtra("reauth",false)) { goHome(); return }
        experience=savedInstanceState?.getString("experience")?:"Standard"
        buildUi()
        creating = savedInstanceState?.getBoolean("creating") == true && !intent.getBooleanExtra("reauth",false)
        setMode(creating)
        if(intent.getBooleanExtra("reauth",false)){usernameField.setText(Session.username);statusText.text="Sign in again to reconnect. Your local chats stay on this phone.";statusText.setTextColor(AppTheme.sub(this));registerBtn.visibility=View.GONE}
    }
    override fun onSaveInstanceState(state: Bundle) { state.putBoolean("creating", creating);state.putString("experience",experience); super.onSaveInstanceState(state) }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(AppTheme.bg(this@AuthActivity)); isFocusableInTouchMode = true
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(AppTheme.bg(this@AuthActivity)); addView(root) })
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // ---- Hero ----
        root.addView(OrbitMarkView(this), LinearLayout.LayoutParams(dp(148), dp(148)))
        root.addView(TextView(this).apply {
            text = "Rabta"; textSize = 30f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(AppTheme.text(this@AuthActivity))
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0); androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        root.addView(TextView(this).apply { text = "رابطہ"; textSize = 15f; setTextColor(AppTheme.sub(this@AuthActivity)); gravity = Gravity.CENTER })
        root.addView(TextView(this).apply {
            text = "Stay close. Beyond boundaries."; textSize = 15f; setTextColor(AppTheme.accent(this@AuthActivity)); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        })

        if (BuildConfig.BUILD_TYPE == "staging") root.addView(RabtaUi.text(this,
            "Preview environment · Create a test account here. Your regular Rabta account belongs to a different server.",
            13f, color=AppTheme.accent(this)).apply { setPadding(0,dp(8),0,dp(12)) })

        // ---- Form ----
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = RabtaUi.shape(this@AuthActivity, AppTheme.surface(this@AuthActivity), 24, AppTheme.line(this@AuthActivity))
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        root.addView(form, LinearLayout.LayoutParams(-1, -2))
        heading = RabtaUi.text(this, "Welcome back", 20f, true).apply { setPadding(0, 0, 0, dp(16)) }
        form.addView(heading)

        fun lp(t: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(t) }
        fun label(t: String) = RabtaUi.text(this, t, 13f, true, AppTheme.sub(this)).apply { setPadding(dp(2), 0, 0, dp(6)) }
        fun field(hint: String, type: Int) = EditText(this).apply {
            this.hint = hint; textSize = 16f; contentDescription = hint
            setTextColor(AppTheme.text(this@AuthActivity)); setHintTextColor(AppTheme.sub(this@AuthActivity))
            setSingleLine(true); minHeight = dp(52); inputType = type
            background = RabtaUi.shape(this@AuthActivity, AppTheme.raised(this@AuthActivity), 16, AppTheme.line(this@AuthActivity))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        fun secret(hint: String) = field(hint, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(8)); setPadding(dp(16), dp(14), dp(52), dp(14))
        }
        /** A PIN field with its show/hide control drawn inside the field. */
        fun withToggle(f: EditText, what: String) = FrameLayout(this).apply {
            addView(f, FrameLayout.LayoutParams(-1, -2))
            addView(TextView(this@AuthActivity).apply {
                UiIcons.set(this, "eye", AppTheme.sub(this@AuthActivity), 22, "Show $what"); gravity = Gravity.CENTER; isFocusable = true
                background = RabtaUi.ripple(this@AuthActivity, android.graphics.Color.TRANSPARENT, 24)
                setOnClickListener {
                    val hidden = f.transformationMethod is android.text.method.PasswordTransformationMethod
                    f.transformationMethod = if (hidden) android.text.method.HideReturnsTransformationMethod.getInstance() else android.text.method.PasswordTransformationMethod.getInstance()
                    f.setSelection(f.length())
                    UiIcons.set(this, if (hidden) "eye_off" else "eye", if (hidden) AppTheme.accent(this@AuthActivity) else AppTheme.sub(this@AuthActivity), 22, if (hidden) "Hide $what" else "Show $what")
                }
            }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(2) })
        }

        // Display name (create account only)
        val nameLabel = label("Display name"); nameField = field("Your name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(30))
        }
        form.addView(nameLabel); form.addView(nameField, lp().apply { bottomMargin = dp(14) })
        val phoneLabel = label("Phone number")
        phoneField = field("+923001234567", InputType.TYPE_CLASS_PHONE).apply { filters = arrayOf(android.text.InputFilter.LengthFilter(24)) }
        val phoneNote = label("Private account detail. Not SMS verified or used to sign in.")
        form.addView(phoneLabel); form.addView(phoneField); form.addView(phoneNote, lp(6))
        val modeLabel = label("Choose your experience")
        val modeGroup = android.widget.RadioGroup(this)
        Experience.modes.forEachIndexed { i, mode ->
            modeGroup.addView(android.widget.RadioButton(this).apply {
                id = 2000 + i; text = "$mode: ${Experience.descriptions[i]}"; minHeight = dp(48)
                setTextColor(AppTheme.text(this@AuthActivity)); isChecked = mode == experience
            })
        }
        modeGroup.setOnCheckedChangeListener { _, id -> experience = Experience.modes[id - 2000] }
        form.addView(modeLabel, lp(12)); form.addView(modeGroup)
        createOnly += listOf(phoneLabel, phoneField, phoneNote, modeLabel, modeGroup)
        val usernameLabel = label("Username")
        form.addView(usernameLabel)
        usernameField = field("username", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(20))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { if (creating) checkUsername() }
            })
        }
        form.addView(usernameField)
        usernameHint = RabtaUi.text(this, "3–20 letters, numbers or _", 12f, color = AppTheme.sub(this)).apply {
            setPadding(dp(2), dp(6), 0, 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        form.addView(usernameHint)
        form.addView(label("PIN"), lp(14))
        pinField = secret("4–8 digits").apply {
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ -> if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        }
        form.addView(withToggle(pinField, "PIN"))
        val confirmLabel = label("Confirm PIN"); confirmField = secret("Repeat your PIN")
        val confirmBox = withToggle(confirmField, "confirmed PIN")
        form.addView(confirmLabel, lp(14)); form.addView(confirmBox)
        createOnly += listOf(nameLabel, nameField, usernameHint, confirmLabel, confirmBox)

        statusText = TextView(this).apply {
            textSize = 14f; setTextColor(AppTheme.error(this@AuthActivity)); gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        form.addView(statusText, lp(12))
        loading = RabtaUi.loading(this, "Connecting to Rabta…").apply { visibility = View.GONE }
        form.addView(loading)
        loginBtn = RabtaUi.button(this, "Sign in", true) { submit() }.apply { gravity = Gravity.CENTER; textSize = 16f }
        form.addView(loginBtn, lp(12).apply { height = dp(52) })

        registerBtn = RabtaUi.button(this, "Create account") { setMode(!creating) }.apply {
            gravity = Gravity.CENTER; background = RabtaUi.ripple(this@AuthActivity, android.graphics.Color.TRANSPARENT, 16)
            setTextColor(AppTheme.accent(this@AuthActivity))
        }
        root.addView(registerBtn, lp(8))
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
    }

    private fun setMode(create: Boolean) {
        creating = create
        createOnly.forEach { it.visibility = if (create) View.VISIBLE else View.GONE }
        heading.text = if (create) "Create your account" else "Welcome back"
        loginBtn.text = if (create) "Create account" else "Sign in"
        registerBtn.text = if (create) "I already have an account" else "New to Rabta? Create account"
        pinField.imeOptions = if (create) android.view.inputmethod.EditorInfo.IME_ACTION_NEXT else android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        confirmField.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        confirmField.setOnEditorActionListener { _, action, _ -> if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        statusText.text = ""; usernameHint.text = "3–20 letters, numbers or _"; usernameHint.setTextColor(AppTheme.sub(this))
        if (create && usernameField.text.isNotEmpty()) checkUsername()
    }

    /** Live availability while creating an account; the server stays the final judge when the account is created. */
    private fun checkUsername() {
        val name = usernameField.text.toString().trim().lowercase(); val mine = ++checkGeneration
        handler.removeCallbacksAndMessages(null)
        if (name.isEmpty()) { usernameHint.text = "3–20 letters, numbers or _"; usernameHint.setTextColor(AppTheme.sub(this)); return }
        if (!name.matches(Regex("[a-z0-9_]{3,20}"))) { usernameHint.text = "Use 3–20 letters, numbers or _"; usernameHint.setTextColor(AppTheme.error(this)); return }
        usernameHint.text = "Checking @$name…"; usernameHint.setTextColor(AppTheme.sub(this))
        handler.postDelayed({
            WsClient.usernameAvailable(Config.SERVER, name) { code, body ->
                if (mine != checkGeneration || isFinishing || !creating) return@usernameAvailable
                val free = code == 200 && body.optBoolean("available")
                usernameHint.text = when { free -> "✓ @$name is available"; code == 200 -> body.optString("error", "Username already used"); else -> "Couldn't check right now — you can still try" }
                usernameHint.setTextColor(when { free -> AppTheme.teal(this); code == 200 -> AppTheme.error(this); else -> AppTheme.sub(this) })
            }
        }, 400)
    }

    private fun validate(): Boolean {
        val u = usernameField.text.toString().trim()
        val p = pinField.text.toString().trim()
        val error = when {
            creating && !phoneField.text.toString().replace(Regex("[ ()-]"), "").matches(Regex("\\+[1-9][0-9]{7,14}")) -> "Enter your phone number with country code, such as +923001234567"
            creating && nameField.text.toString().isBlank() -> "Enter your display name"
            u.length !in 3..20 || !u.matches(Regex("[a-zA-Z0-9_]+")) -> "Username must be 3–20 letters, numbers or _"
            !p.matches(Regex("[0-9]{4,8}")) -> "PIN must be 4–8 digits"
            creating && confirmField.text.toString().trim() != p -> "The PINs don't match"
            else -> null
        }
        statusText.text = error.orEmpty(); statusText.setTextColor(AppTheme.error(this))
        return error == null
    }

    private fun setLoading(on: Boolean) {
        busy = on
        loginBtn.isEnabled = !on; registerBtn.isEnabled = !on
        loading.visibility = if (on) View.VISIBLE else View.GONE
        loginBtn.alpha = if (on) 0.5f else 1f; registerBtn.alpha = if (on) 0.5f else 1f
    }

    private fun submit() { if (busy) return; if (creating) doRegister() else doLogin() }

    private fun doLogin() {
        if (!validate()) return
        statusText.text = ""
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
        val name = nameField.text.toString().replace(Regex("\\s+"), " ").trim()
        statusText.text = ""; setLoading(true)
        WsClient.register(this, Config.SERVER, un, pinField.text.toString().trim(), name, phoneField.text.toString().replace(Regex("[ ()-]"), "")) { ok, msg, data ->
            setLoading(false)
            if (ok && data != null) { saveAndGo(data); return@register }
            // One account per phone: this phone already has one, so sign-in opens with its username filled in.
            val existing = Regex("\\(@([a-z0-9_]{3,20})\\)").find(msg)?.groupValues?.get(1)
            statusText.text = "Account not created. $msg"; statusText.setTextColor(AppTheme.error(this))
            if (existing != null && !isFinishing) android.app.AlertDialog.Builder(this)
                .setTitle("Account not created")
                .setMessage("This phone already uses @$existing. Your new username and PIN were not registered. Sign in with the existing account's PIN.")
                .setNegativeButton("Stay here", null)
                .setPositiveButton("Sign in to @$existing") { _, _ ->
                    setMode(false); usernameField.setText(existing); pinField.setText("")
                }.show()
        }
    }

    private fun saveAndGo(data: JSONObject) {
        if(intent.getBooleanExtra("reauth",false)&&data.optString("id")!=Session.id){statusText.text="Use the same account as before. Local chats have not been changed.";return}
        WsClient.disconnect()
        Session.id = data.optString("id"); Session.username = data.optString("username")
        Session.name = data.optString("name").ifEmpty { data.optString("username") }
        Session.token = data.optString("token"); Session.serverUrl = Config.SERVER
        if(creating) Experience.set(this, experience)
        val code = data.optString("recoveryCode")
        if (code.isNotEmpty()) AccountUi.showRecoveryCode(this, code) { goHome() } else goHome()
    }

    private fun goHome() { startActivity(Intent(this, ConversationListActivity::class.java)); finish() }
}
