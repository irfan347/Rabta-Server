package com.irfan.rabta

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Wake pushes, like WhatsApp's: while Rabta is closed or stopped by the phone, its server asks Firebase to wake it when
 * a message or call waits. The push carries no text; Rabta connects and fetches what waits, as when it is opened.
 * Phones without Google Play services simply keep Rabta's own connection.
 */
object Push {
    private val main = Handler(Looper.getMainLooper())
    private fun prefs(c: Context) = c.getSharedPreferences("push", 0)

    /** Connected: make sure the server has this phone's current token for this account. */
    fun sync(c: Context) {
        if (!Session.loggedIn) return
        val app = c.applicationContext
        runCatching { FirebaseMessaging.getInstance().token.addOnSuccessListener { send(app, it) } }
    }

    /** Firebase gave this phone a new token. */
    fun newToken(c: Context, token: String) {
        val app = c.applicationContext
        main.post { Session.init(app); send(app, token) }
    }

    private fun send(c: Context, token: String) {
        if (!Session.loggedIn || token.isBlank()) return
        val sent = Session.id + "|" + Session.serverUrl + "|" + token
        if (prefs(c).getString("sent", "") == sent) return
        WsClient.registerPush(Session.serverUrl, Session.token, token) { ok -> if (ok) prefs(c).edit().putString("sent", sent).apply() }
    }

    /** A wake from the server: Rabta's service starts and connects, and the waiting message or call arrives as usual. */
    fun received(c: Context, data: Map<String, String>) {
        if (data["type"] != "wake") return
        val app = c.applicationContext
        main.post {
            Session.init(app)
            if (!Session.loggedIn) return@post
            // A high-priority push lets Rabta start its service from the background for a short while.
            MessageService.awake(app, 20_000)
            MessageService.start(app)
            WsClient.connect(Session)
            WsClient.wake()
        }
    }

    /** Signing out: the server forgets this phone, so it no longer wakes for that account. Call before Session.logout. */
    fun forget(c: Context) {
        val token = prefs(c).getString("sent", "").orEmpty().substringAfterLast('|', "")
        prefs(c).edit().remove("sent").apply()
        if (token.isNotEmpty() && Session.token.isNotEmpty()) WsClient.unregisterPush(Session.serverUrl, Session.token, token)
    }
}

/** Receives Firebase's token and wake pushes for [Push]. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Push.newToken(this, token)
    override fun onMessageReceived(message: RemoteMessage) = Push.received(this, message.data)
}
