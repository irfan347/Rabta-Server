package com.irfan.rabta

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Rabta's notifications on a disposable database and a local mock server; no production accounts or servers. */
@RunWith(AndroidJUnit4::class)
class NotificationsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val me = "NT1100000001"
    private val manager get() = ctx.getSystemService(NotificationManager::class.java)
    private fun ui(block: () -> Unit) = inst.runOnMainSync(block)
    private fun waitUi(message: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        do { inst.waitForIdleSync(); var ok = false; ui { ok = check() }; if (ok) return; Thread.sleep(75) } while (System.currentTimeMillis() < end)
        fail(message)
    }
    private fun posted(channel: String): StatusBarNotification? = manager.activeNotifications.firstOrNull { it.notification.channelId == channel }

    private fun fixture(run: (Db) -> Unit) {
        val permissions = mutableListOf("RECORD_AUDIO", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION")
        if (android.os.Build.VERSION.SDK_INT >= 31) permissions += listOf("BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT")
        if (android.os.Build.VERSION.SDK_INT >= 33) permissions += listOf("NEARBY_WIFI_DEVICES", "POST_NOTIFICATIONS")
        permissions.forEach { android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.$it")).use { s -> s.readBytes() } }
        val name = "notifications-${java.util.UUID.randomUUID()}.db"
        val isolated = object : ContextWrapper(ctx) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(ignored: String) = ctx.getDatabasePath(name)
            override fun openOrCreateDatabase(ignored: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = ctx.openOrCreateDatabase(name, mode, factory)
            override fun openOrCreateDatabase(ignored: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) = ctx.openOrCreateDatabase(name, mode, factory, handler)
        }
        val field = Db::class.java.getDeclaredField("INST").apply { isAccessible = true }
        val previous = field.get(null)
        val db = Db(isolated)
        ui {
            MessageService.stop(ctx); Session.init(ctx); WsClient.disconnect()
            Session.id = me; Session.name = "Notifications fixture"; Session.token = "local-notifications-test"; Session.serverUrl = "https://127.0.0.1:8443"
            field.set(null, db)
        }
        ctx.getSharedPreferences("appearance", 0).edit().putString("language", "en").putFloat("scale", 1f).commit()
        try { run(db) } finally {
            ui { WsClient.disconnect(); MessageService.stop(ctx); manager.cancelAll(); field.set(null, previous) }
            inst.waitForIdleSync(); db.close(); ctx.deleteDatabase(name)
            ctx.getSharedPreferences("privacy_$me", 0).edit().clear().commit()
        }
    }

    @Test fun messagePopupRepliesMarksReadAndMutes() = fixture { db ->
        val peer = "NT1100000002"
        fun popup() { ui { db.upsertConversation(peer, "Popup contact", "Hi there", System.currentTimeMillis(), true); ChatNotifications.message(ctx, peer, "Popup contact", false, peer, "Popup contact", "Hi there") } }
        fun action(label: String) = posted(MessageService.MSG_CHANNEL)!!.notification.actions.first { it.title.toString() == label }

        popup()
        waitUi("Message popup is shown") { posted(MessageService.MSG_CHANNEL) != null }
        assertEquals(listOf("Reply", "Mark as read", "Mute"), posted(MessageService.MSG_CHANNEL)!!.notification.actions.map { it.title.toString() })
        val reply = action("Reply")
        assertEquals(ChatNotifications.KEY_REPLY, reply.remoteInputs.single().resultKey)

        // Reply typed in the notification goes out through the outbox and clears the popup.
        val typed = Intent(); RemoteInput.addResultsToIntent(reply.remoteInputs, typed, Bundle().apply { putCharSequence(ChatNotifications.KEY_REPLY, "On my way") })
        reply.actionIntent.send(ctx, 0, typed)
        waitUi("Reply is saved as an outgoing message") { db.messages(peer).any { it.mine && it.body == "On my way" } }
        waitUi("Replying clears the popup") { posted(MessageService.MSG_CHANNEL) == null }

        popup()
        waitUi("Second popup") { posted(MessageService.MSG_CHANNEL) != null }
        action("Mark as read").actionIntent.send()
        waitUi("Mark as read clears the unread count and the popup") { posted(MessageService.MSG_CHANNEL) == null && db.conversations().first { it.peerId == peer }.unread == 0 }

        popup()
        waitUi("Third popup") { posted(MessageService.MSG_CHANNEL) != null }
        action("Mute").actionIntent.send()
        waitUi("Mute clears the popup") { posted(MessageService.MSG_CHANNEL) == null && ChatNotifications.muted(ctx, peer) }
        popup(); Thread.sleep(500)
        assertNull("A muted chat shows no popup", posted(MessageService.MSG_CHANNEL))
    }

    @Test fun missedCallsSayVoiceOrVideo() = fixture { _ ->
        for ((video, words) in listOf(false to "Missed voice call", true to "Missed video call")) {
            ui { manager.cancelAll(); ChatNotifications.missedCall(ctx, "NT1100000004", "Missed contact", video) }
            waitUi(words) { posted(ChatNotifications.MISSED_CHANNEL)?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == words }
            assertEquals(listOf("Call back", "Message"), posted(ChatNotifications.MISSED_CHANNEL)!!.notification.actions.map { it.title.toString() })
        }
    }

    /** Answer and Decline on the ringing notification; a call nobody answered becomes a missed call. */
    @Test fun incomingCallNotificationDeclinesAndMissedCallsAreNoticed() = fixture { db ->
        // Calls ring only from contacts; Rabta silences unknown callers.
        db.saveContact("NT1100000003", "Ringing contact")
        val socket = AtomicReference<WebSocket>(); val ended = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/ws") return MockResponse().setResponseCode(404)
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) { socket.set(ws); ws.send("{\"type\":\"ready\",\"id\":\"$me\",\"protocol\":2}") }
                    override fun onMessage(ws: WebSocket, raw: String) { val o = JSONObject(raw); if (o.optString("type") == "call_end") ended += o.optString("callId") }
                })
            }
        }
        server.start(); val url = server.url("/").toString().trimEnd('/')
        ui { Session.serverUrl = url }
        val list = inst.startActivitySync(Intent(ctx, ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun ring(callId: String) = socket.get().send(JSONObject().put("type", "incoming_call").put("from", "NT1100000003").put("fromName", "Ringing contact").put("callId", callId).toString())
        fun ringing() = manager.activeNotifications.firstOrNull { it.id == MessageService.CALL_NOTIF }?.notification
        fun button(n: Notification, extra: String, label: String): PendingIntent? =
            if (android.os.Build.VERSION.SDK_INT >= 31) n.extras.getParcelable(extra) else n.actions?.firstOrNull { it.title.toString() == label }?.actionIntent
        try {
            waitUi("Signed in to the mock server") { WsClient.connected && socket.get() != null }
            ring("AAAAAA000001")
            waitUi("Ringing notification") { ringing() != null }
            val n = ringing()!!
            assertNotNull("Answer on the notification", button(n, Notification.EXTRA_ANSWER_INTENT, "Answer"))
            button(n, Notification.EXTRA_DECLINE_INTENT, "Decline")!!.send()
            waitUi("Decline tells the caller and stops ringing") { "AAAAAA000001" in ended && ringing() == null }
            Thread.sleep(500)
            assertNull("A declined call is not a missed call", posted(ChatNotifications.MISSED_CHANNEL))

            ring("AAAAAA000002")
            waitUi("Second call rings") { ringing() != null }
            socket.get().send(JSONObject().put("type", "call_end").put("from", "NT1100000003").put("callId", "AAAAAA000002").toString())
            waitUi("The caller hung up: missed voice call") { posted(ChatNotifications.MISSED_CHANNEL)?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Missed voice call" }
        } finally {
            ui { list.finish() }
            server.shutdown()
        }
    }
}
