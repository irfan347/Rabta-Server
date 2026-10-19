package com.irfan.rabta

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * One Rabta account per phone: the server learns a hash of this phone's Android ID (the same for Rabta on this phone,
 * also after reinstalling; a factory reset changes it). The ID itself never leaves the phone. Sign-up and sign-in send
 * it; an account signed in before this rule sends it once after connecting, so it counts for that phone too.
 */
object PhoneId {
    private fun prefs(c: Context) = c.getSharedPreferences("phone_id", 0)

    /** SHA-256 of a Rabta-only prefix and the Android ID, as 64 hex digits; empty when the phone gives none. */
    @SuppressLint("HardwareIds")   // hashed, and only used to keep one account per phone
    fun of(c: Context): String {
        val id = runCatching { Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull().orEmpty()
        return if (id.isBlank()) "" else hash(id)
    }
    fun hash(androidId: String): String =
        MessageDigest.getInstance("SHA-256").digest("rabta-phone|$androidId".toByteArray()).joinToString("") { "%02x".format(it) }

    /** Connected: the server hears which phone this account is on, once per account and phone. */
    fun sync(c: Context) {
        if (!Session.loggedIn) return
        val phone = of(c); if (phone.isEmpty()) return
        val key = Session.id + "|" + Session.serverUrl + "|" + phone
        if (prefs(c).getString("sent", "") == key) return
        WsClient.api("POST", "/api/me/phone", org.json.JSONObject().put("phone", phone)) { status, _ ->
            if (status == 200) prefs(c).edit().putString("sent", key).apply()
        }
    }
}
