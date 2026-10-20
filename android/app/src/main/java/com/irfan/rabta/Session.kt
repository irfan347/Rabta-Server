package com.irfan.rabta

import android.content.Context

/** Logged-in user state, persisted in SharedPreferences. */
object Session {
    private const val P = "rabta_session"
    private lateinit var ctx: Context

    fun init(c: Context) { ctx = c.applicationContext; Inbox.init(ctx); Outbox.init(ctx); (ctx as? android.app.Application)?.let(OnScreen::init); if (serverUrl.isEmpty()) serverUrl = Config.SERVER }

    var id: String get() = prefs().getString("id", "") ?: ""; set(v) { prefs().edit().putString("id", v).apply() }
    var username: String get() = prefs().getString("username", "") ?: ""; set(v) { prefs().edit().putString("username", v).apply() }
    var name: String get() = prefs().getString("name", "") ?: ""; set(v) { prefs().edit().putString("name", v).apply() }
    var token: String get() = prefs().getString("token", "") ?: ""; set(v) { prefs().edit().putString("token", v).apply() }
    var serverUrl: String get() = prefs().getString("server", "") ?: ""; set(v) { prefs().edit().putString("server", v.trim().trimEnd('/')).apply() }
    var profilePic: String get() = prefs().getString("profile_pic", "") ?: ""; set(v) { prefs().edit().putString("profile_pic", v).apply() }
    /** Whether the server has [profilePic], so contacts see it. */
    var profilePicShared: Boolean get() = prefs().getBoolean("profile_pic_shared", false); set(v) { prefs().edit().putBoolean("profile_pic_shared", v).apply() }

    val loggedIn get() = id.isNotEmpty() && token.isNotEmpty() && serverUrl.isNotEmpty()

    fun logout() { prefs().edit().clear().apply() }
    private fun prefs() = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
}
