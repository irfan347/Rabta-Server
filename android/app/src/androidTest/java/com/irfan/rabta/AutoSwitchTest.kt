package com.irfan.rabta

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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Rabta AutoSwitch through the real Outbox, WsClient and Inbox against a localhost server.
 * Isolated fixture account and peer; never production accounts or messages.
 */
@RunWith(AndroidJUnit4::class)
class AutoSwitchTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val account = "A5A500000001"
    private val peer = "A5A500000002"
    private val server = MockWebServer()
    private val frames = Collections.synchronizedList(mutableListOf<JSONObject>())
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ackMode = "stored"   // what the fake server answers to a message; "" = no answer
    private lateinit var old: List<String>

    private fun ui(block: () -> Unit) = inst.runOnMainSync(block)
    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10000
        while (!check() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue(what, check())
    }
    private fun db() = Db.get(ctx)
    private fun received(type: String, id: String) = synchronized(frames) { frames.count { it.optString("type") == type && it.optString("id") == id } }
    private fun connect() { ui { WsClient.connect(Session) }; until("Local authenticated socket ready") { WsClient.connected } }
    private fun disconnect() { ui { WsClient.disconnect() }; until("Socket closed") { !WsClient.connected } }
    private fun sendQueued(body: String): String {
        ui { Outbox.send(peer, "AutoSwitch contact", false, body, "text") }
        var id = ""
        until("Message saved before any transport") { db().messages(peer).firstOrNull { it.mine && it.body == body }?.also { id = it.id } != null }
        return id
    }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/ws") return MockResponse().setResponseCode(404)
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) { socket = ws; ws.send(JSONObject().put("type", "ready").put("id", account).put("protocol", 2).put("receipts", true).toString()) }
                    override fun onMessage(ws: WebSocket, text: String) {
                        val o = JSONObject(text); frames.add(o)
                        if (o.optString("type") == "msg" && ackMode.isNotEmpty()) ws.send(JSONObject().put("type", "ack").put("id", o.getString("id")).put("status", ackMode).toString())
                    }
                })
            }
        }
        server.start()
        Session.init(ctx)
        old = listOf(Session.id, Session.name, Session.username, Session.token, Session.serverUrl)
        ui {
            MessageService.stop(ctx); WsClient.disconnect()
            Session.id = account; Session.name = "AutoSwitch fixture"; Session.username = "autoswitch_fixture"; Session.token = "local-autoswitch-test"
            Session.serverUrl = server.url("/").toString().trimEnd('/')
        }
        db().upsertConversation(peer, "AutoSwitch contact", "", System.currentTimeMillis(), false)
    }

    @After fun tearDown() {
        ui { WsClient.disconnect() }
        server.shutdown()
        db().writableDatabase.delete("messages", "conv_id=?", arrayOf(peer)); db().writableDatabase.delete("conversations", "peer_id=?", arrayOf(peer))
        ui { MessageService.stop(ctx); Session.id = old[0]; Session.name = old[1]; Session.username = old[2]; Session.token = old[3]; Session.serverUrl = old[4] }
    }

    @Test fun offlineMessageIsQueuedThenRelayedDeliveredAndNeverResent() {
        val id = sendQueued("Written while offline")
        until("Offline message is Queued, not Failed") { db().status(id) == "queued" }
        connect()
        until("Reconnection sends the queued message") { received("msg", id) == 1 }
        until("Server storage is Relayed") { db().status(id) == "relayed" }
        socket!!.send(JSONObject().put("type", "receipt").put("id", id).put("by", peer).toString())
        until("Recipient receipt is Delivered") { db().status(id) == "delivered" }
        disconnect(); connect(); Thread.sleep(1500)
        assertEquals("A delivered message is not sent again after reconnecting", 1, received("msg", id))
        assertEquals("delivered", db().status(id))
    }

    @Test fun connectionLostBeforeAckRequeuesAndResendsTheSameId() {
        ackMode = ""
        connect()
        val id = sendQueued("Sent just before the connection dropped")
        until("First attempt reaches the server") { received("msg", id) == 1 }
        assertEquals("sending", db().status(id))
        disconnect()
        until("An unacknowledged message goes back to Queued") { db().status(id) == "queued" }
        ackMode = "stored"
        connect()
        until("The same ID is sent again") { received("msg", id) == 2 }
        until("Ack after the retry is Relayed") { db().status(id) == "relayed" }
        assertEquals("One bubble for every attempt", 1, db().messages(peer).count { it.id == id })
    }

    @Test fun lateAndForgedEvidenceNeverOverstatesDelivery() {
        connect()
        val id = sendQueued("Receipt order test")
        until("Relayed") { db().status(id) == "relayed" }
        socket!!.send(JSONObject().put("type", "receipt").put("id", id).put("by", "A5A5FFFFFFFF").toString())
        Thread.sleep(800)
        assertEquals("Only the recipient can confirm delivery", "relayed", db().status(id))
        socket!!.send(JSONObject().put("type", "receipt").put("id", id).put("by", peer).toString())
        until("Delivered") { db().status(id) == "delivered" }
        socket!!.send(JSONObject().put("type", "ack").put("id", id).put("status", "error").toString())
        socket!!.send(JSONObject().put("type", "ack").put("id", id).put("status", "stored").toString())
        Thread.sleep(800)
        assertEquals("Late acks never downgrade", "delivered", db().status(id))
    }

    @Test fun duplicateIncomingCopiesAreStoredOnceAndEachIsConfirmed() {
        connect()
        val incoming = JSONObject().put("type", "msg").put("id", "A5A5ABCDEF01").put("from", peer).put("fromName", "AutoSwitch contact").put("body", "arrives twice").put("msgType", "text").put("t", System.currentTimeMillis())
        socket!!.send(incoming.toString()); socket!!.send(incoming.toString())
        until("Each copy is confirmed so a sender that missed one receipt still gets it") { received("receipt", "A5A5ABCDEF01") == 2 }
        assertEquals(1, db().messages(peer).count { it.id == "A5A5ABCDEF01" })
        assertEquals(peer, synchronized(frames) { frames.first { it.optString("type") == "receipt" }.optString("to") })
    }
}
