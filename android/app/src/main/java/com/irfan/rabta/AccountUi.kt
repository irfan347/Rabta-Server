package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Account dialogs shared by sign-in, profile and the phones screen. */
object AccountUi {
    private fun dp(a: Activity, n: Int) = RabtaUi.dp(a, n)
    private fun field(a: Activity, value: String, hint: String) = EditText(a).apply {
        setText(value); setSelection(text.length); this.hint = hint; textSize = 16f; setSingleLine(true)
        setTextColor(AppTheme.text(a)); setHintTextColor(AppTheme.sub(a)); contentDescription = hint
        background = RabtaUi.shape(a, AppTheme.bg(a), 16, AppTheme.line(a)); setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14))
    }
    private fun box(a: Activity, vararg views: android.view.View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(a, 24), dp(a, 8), dp(a, 24), 0); views.forEach { addView(it) }
    }
    fun date(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    /** Shown once, when the server issues a new code. The user must confirm before continuing. */
    fun showRecoveryCode(a: Activity, code: String, then: () -> Unit = {}) {
        if (a.isFinishing) { then(); return }
        val codeView = RabtaUi.text(a, code, 24f, true, AppTheme.accent(a)).apply {
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; setTextIsSelectable(true); setPadding(0, dp(a, 12), 0, dp(a, 12)); tag = "recovery-code"
        }
        val info = RabtaUi.text(a, "If you lose this phone, this code signs your account in on a new phone and signs the lost phone out. Rabta shows it only now. Write it down and keep it private.", 14f, color = AppTheme.sub(a))
        val dialog = AlertDialog.Builder(a).setTitle("Save your recovery code").setView(box(a, codeView, info)).setCancelable(false)
            .setNeutralButton("Copy", null).setPositiveButton("I saved it", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                (a.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rabta recovery code", code))
                Toast.makeText(a, "Copied — paste it somewhere safe, then clear your clipboard", Toast.LENGTH_LONG).show()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { dialog.dismiss(); then() }
        }
        dialog.show()
    }

    /** Asks for the recovery code; [submit] receives it, or null if the user cancels. */
    fun askRecoveryCode(a: Activity, submit: (String?) -> Unit) {
        val input = field(a, "", "XXXX-XXXX-XXXX").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        AlertDialog.Builder(a).setTitle("Use recovery code")
            .setView(box(a, RabtaUi.text(a, "This phone will become your trusted phone. Other phones on your account will be signed out.", 14f, color = AppTheme.sub(a)).apply { setPadding(0, 0, 0, dp(a, 12)) }, input))
            .setPositiveButton("Sign in") { _, _ -> submit(input.text.toString().trim()) }
            .setNegativeButton("Cancel") { _, _ -> submit(null) }.show()
    }

    private val bootstrapping = java.util.concurrent.atomic.AtomicBoolean(false)
    /**
     * A phone that was already signed in with an older app version has a token without a device. If the account
     * has no trusted phone yet, this phone becomes it; otherwise the server refuses and the user signs in again.
     */
    fun ensureTrustedPhone(a: Activity) {
        if (!Session.loggedIn || Session.token.split('.').size != 3 || !bootstrapping.compareAndSet(false, true)) return
        val owner = Session.id
        val proof = runCatching { DeviceIdentity.proof("bootstrap:$owner") }.getOrNull() ?: run { bootstrapping.set(false); return }
        WsClient.api("POST", "/api/me/device", JSONObject().put("device", proof)) { code, body ->
            bootstrapping.set(false)
            if (code != 200 || Session.id != owner || body.optString("token").isEmpty()) return@api
            Session.token = body.optString("token"); WsClient.connect(Session)
            body.optString("recoveryCode").takeIf { it.isNotEmpty() }?.let { showRecoveryCode(a, it) }
        }
    }

    fun editName(a: Activity, done: () -> Unit) {
        val input = field(a, Session.name, "Display name")
        val status = RabtaUi.text(a, "Up to 30 characters. You can change it any time.", 13f, color = AppTheme.sub(a)).apply { setPadding(0, dp(a, 8), 0, 0) }
        val dialog = AlertDialog.Builder(a).setTitle("Display name").setView(box(a, input, status)).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().replace(Regex("\\s+"), " ").trim()
                if (name.isEmpty() || name.codePointCount(0, name.length) > 30) { status.text = "Display name must be 1–30 characters"; status.setTextColor(AppTheme.error(a)); return@setOnClickListener }
                status.text = "Saving…"; status.setTextColor(AppTheme.sub(a))
                WsClient.api("POST", "/api/me/name", JSONObject().put("name", name)) { code, body ->
                    if (code == 200) { Session.name = body.optString("name", name); dialog.dismiss(); done() }
                    else { status.text = body.optString("error", "Could not save — try again"); status.setTextColor(AppTheme.error(a)) }
                }
            }
        }
        dialog.show()
    }

    /** Live availability while typing; saving is possible only when the server says the name is free. */
    fun editUsername(a: Activity, nextChangeAt: Long, done: () -> Unit) {
        if (nextChangeAt > System.currentTimeMillis()) {
            AlertDialog.Builder(a).setTitle("Username").setMessage("You can change your username once every 30 days. Next change: ${date(nextChangeAt)}.").setPositiveButton("OK", null).show(); return
        }
        val input = field(a, Session.username, "username").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        val status = RabtaUi.text(a, "3–20 letters, numbers or _. After changing, you can change it again in 30 days.", 13f, color = AppTheme.sub(a)).apply { setPadding(0, dp(a, 8), 0, 0); tag = "username-status" }
        val dialog = AlertDialog.Builder(a).setTitle("Change username").setView(box(a, input, status)).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        val handler = Handler(Looper.getMainLooper())
        var checked = ""; var available = false; var generation = 0
        fun save() = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fun check() {
            val name = input.text.toString().trim().lowercase(); val mine = ++generation
            available = false; save()?.isEnabled = false
            if (!name.matches(Regex("[a-z0-9_]{3,20}"))) { status.text = "Use 3–20 letters, numbers or _"; status.setTextColor(AppTheme.error(a)); return }
            status.text = "Checking…"; status.setTextColor(AppTheme.sub(a))
            handler.postDelayed({
                if (mine != generation) return@postDelayed
                WsClient.api("GET", "/api/username/available?u=" + java.net.URLEncoder.encode(name, "UTF-8"), null) { code, body ->
                    if (mine != generation) return@api
                    available = code == 200 && body.optBoolean("available"); checked = name
                    status.text = if (available) "✓ @$name is available" else body.optString("error", "Could not check — try again")
                    status.setTextColor(if (available) AppTheme.teal(a) else AppTheme.error(a)); save()?.isEnabled = available
                }
            }, 400)
        }
        dialog.setOnShowListener {
            save().isEnabled = false
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { check() }
            })
            save().setOnClickListener {
                if (!available) return@setOnClickListener
                AlertDialog.Builder(a).setTitle("Change to @$checked?").setMessage("You won't be able to change it again for 30 days. Your old username stays reserved for you for 30 days. Chats and contacts are not affected.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Change") { _, _ ->
                        status.text = "Saving…"; status.setTextColor(AppTheme.sub(a)); save().isEnabled = false
                        WsClient.api("POST", "/api/me/username", JSONObject().put("username", checked)) { code, body ->
                            if (code == 200) { Session.username = body.optString("username", checked); dialog.dismiss(); done() }
                            else {
                                status.text = if (code == 429) "You can change it again on ${date(body.optLong("nextUsernameChangeAt"))}" else body.optString("error", "Could not save — try again")
                                status.setTextColor(AppTheme.error(a))
                            }
                        }
                    }.show()
            }
        }
        dialog.show()
    }
}
