package com.irfan.rabta

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject

/** Trusted phones for this account, sign-in requests from new phones, and the recovery code. */
class DevicesActivity : ThemedActivity(), RabtaEvents.Listener {
    private lateinit var content: LinearLayout
    private lateinit var list: LinearLayout
    private fun dp(n: Int) = RabtaUi.dp(this, n)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!Session.loggedIn) { finish(); return }
        content = FeatureUi.page(this, "Phones & recovery")
        content.addView(FeatureUi.text(this, "Only these phones can use your account. A new phone needs your approval here, or your recovery code, even if someone knows your PIN."))
        content.addView(RabtaUi.row(this,"Linked browsers","Use Rabta Web while this phone stays connected","devices") { startActivity(android.content.Intent(this,WebLinksActivity::class.java)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        content.addView(RabtaUi.section(this, "Recovery code"))
        content.addView(RabtaUi.row(this, "Create a new recovery code", "The old code stops working. You'll need your PIN.", "privacy") { newRecoveryCode() })
    }
    override fun onResume() {
        super.onResume(); RabtaEvents.add(this); load()
        getSystemService(android.app.NotificationManager::class.java).cancel(MessageService.DEVICE_NOTIF)
    }
    override fun onPause() { RabtaEvents.remove(this); super.onPause() }
    override fun onDeviceRequest(requestId: String, deviceName: String) = load()

    private fun load() {
        list.removeAllViews(); list.addView(RabtaUi.loading(this, "Loading your phones…"))
        WsClient.api("GET", "/api/devices", null) { code, body ->
            if (isFinishing) return@api
            list.removeAllViews()
            if (code != 200) {
                list.addView(RabtaUi.empty(this, "connection", "Phones unavailable", if (code == 401) "Sign in again with the latest Rabta to manage your phones." else body.optString("error", "Check your connection and try again."), "Retry") { load() })
                return@api
            }
            val requests = body.optJSONArray("requests") ?: org.json.JSONArray()
            for (i in 0 until requests.length()) list.addView(requestCard(requests.getJSONObject(i)))
            list.addView(RabtaUi.section(this, "Trusted phones"))
            val devices = body.optJSONArray("devices") ?: org.json.JSONArray()
            for (i in 0 until devices.length()) list.addView(deviceRow(devices.getJSONObject(i)))
        }
    }

    private fun requestCard(r: JSONObject) = RabtaUi.card(this).apply {
        tag = "device-request:${r.optString("id")}"
        background = RabtaUi.shape(this@DevicesActivity, AppTheme.sent(this@DevicesActivity), RabtaUi.CORNER, AppTheme.accent(this@DevicesActivity))
        addView(RabtaUi.text(this@DevicesActivity, "New sign-in request", 13f, true, AppTheme.accent(this@DevicesActivity)))
        addView(RabtaUi.text(this@DevicesActivity, r.optString("name", "Unknown phone"), 17f, true).apply { setPadding(0, dp(6), 0, 0) })
        addView(RabtaUi.text(this@DevicesActivity, "Someone entered your username and PIN on this phone. Allow only if it is you. Otherwise deny and change your PIN.", 13f, color = AppTheme.sub(this@DevicesActivity)).apply { setPadding(0, dp(6), 0, dp(12)) })
        val actions = LinearLayout(this@DevicesActivity)
        actions.addView(RabtaUi.button(this@DevicesActivity, "Deny") { decide(r.optString("id"), false) }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        actions.addView(RabtaUi.button(this@DevicesActivity, "Allow", true) {
            AlertDialog.Builder(this@DevicesActivity).setTitle("Allow ${r.optString("name")}?").setMessage("This phone will be able to read and send your messages.")
                .setNegativeButton("Cancel", null).setPositiveButton("Allow") { _, _ -> decide(r.optString("id"), true) }.show()
        }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(actions)
    }

    private fun deviceRow(d: JSONObject): LinearLayout {
        val current = d.optBoolean("current")
        val detail = if (current) "This phone" else "Last active ${AccountUi.date(d.optLong("lastSeen"))}"
        return RabtaUi.row(this, d.optString("name", "Phone"), detail, "phone") {
            if (current) return@row
            AlertDialog.Builder(this).setTitle("Remove ${d.optString("name")}?").setMessage("It will be signed out and will need approval or your recovery code to sign in again.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                    WsClient.api("DELETE", "/api/devices/${d.optString("id")}", null) { code, body -> if (code != 200) toast(body.optString("error", "Could not remove")); load() }
                }.show()
        }.apply { tag = "device:${d.optString("id")}" }
    }

    private fun decide(id: String, approve: Boolean) = WsClient.api("POST", "/api/devices/requests/$id", JSONObject().put("approve", approve)) { code, body ->
        toast(if (code == 200) (if (approve) "Phone allowed" else "Sign-in denied") else body.optString("error", "Could not update the request")); load()
    }

    private fun newRecoveryCode() {
        val pin = EditText(this).apply { hint = "PIN"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; setTextColor(AppTheme.text(this@DevicesActivity)) }
        AlertDialog.Builder(this).setTitle("Confirm your PIN").setView(LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(pin, LinearLayout.LayoutParams(-1, -2)) })
            .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ ->
                WsClient.api("POST", "/api/me/recovery", JSONObject().put("pin", pin.text.toString())) { code, body ->
                    if (code == 200) AccountUi.showRecoveryCode(this, body.optString("recoveryCode")) else toast(body.optString("error", "Could not create a code"))
                }
            }.show()
    }
    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
}
