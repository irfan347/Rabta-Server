package com.irfan.rabta

import android.content.Context
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
import java.util.concurrent.atomic.AtomicInteger

/** Wake pushes against a local mock server and a fixture account; no Google or production server is involved. */
@RunWith(AndroidJUnit4::class)
class PushTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private fun ui(block: () -> Unit) = inst.runOnMainSync(block)
    private fun waitUi(message: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        do { inst.waitForIdleSync(); var ok = false; ui { ok = check() }; if (ok) return; Thread.sleep(50) } while (System.currentTimeMillis() < end)
        fail(message)
    }

    /** Rabta's push endpoint and WebSocket for account PU1100000001. */
    private class Server : Dispatcher() {
        val pushes = CopyOnWriteArrayList<String>()
        val sockets = AtomicInteger()
        override fun dispatch(r: RecordedRequest): MockResponse {
            if (r.path == "/api/me/push") {
                pushes += "${r.method} ${JSONObject(r.body.readUtf8()).optString("token")} ${r.getHeader("Authorization")}"
                return MockResponse().setBody("{\"ok\":true,\"push\":true}")
            }
            if (r.path == "/ws") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets.incrementAndGet(); webSocket.send("{\"type\":\"ready\",\"id\":\"PU1100000001\",\"protocol\":2,\"receipts\":true}")
                }
            })
            return MockResponse().setResponseCode(404)
        }
    }

    private fun fixture(run: (Server) -> Unit) {
        Session.init(ctx)
        val old = listOf(Session.id, Session.name, Session.token, Session.serverUrl)
        val prefs = ctx.getSharedPreferences("push", 0); val oldSent = prefs.getString("sent", null)
        val server = Server(); val mock = MockWebServer().apply { dispatcher = server; start() }
        ui {
            MessageService.stop(ctx); WsClient.disconnect()
            Session.id = "PU1100000001"; Session.name = "Push fixture"; Session.token = "local-push-test"; Session.serverUrl = mock.url("/").toString().trimEnd('/')
        }
        prefs.edit().remove("sent").commit()
        try { run(server) } finally {
            ui { WsClient.disconnect(); MessageService.stop(ctx); Session.id = old[0]; Session.name = old[1]; Session.token = old[2]; Session.serverUrl = old[3] }
            prefs.edit().apply { if (oldSent == null) remove("sent") else putString("sent", oldSent) }.commit()
            mock.shutdown()
        }
    }

    /** The server learns this phone's push token once per account and forgets it when the phone signs out. */
    @Test fun serverLearnsThePushTokenOnceAndForgetsItOnSignOut() = fixture { server ->
        val token = "fixture-push-token:" + "Z".repeat(60)
        val prefs = ctx.getSharedPreferences("push", 0)
        Push.newToken(ctx, token)
        waitUi("The token reaches the server") { server.pushes.size == 1 && prefs.getString("sent", "").orEmpty().endsWith(token) }
        assertEquals("POST $token Bearer local-push-test", server.pushes[0])
        Push.newToken(ctx, token); Thread.sleep(500)
        assertEquals("Sent once, not every time Rabta starts", 1, server.pushes.size)
        ui { Push.forget(ctx) }
        waitUi("Signing out tells the server to forget this phone") { server.pushes.size == 2 }
        assertEquals("DELETE $token Bearer local-push-test", server.pushes[1])
    }

    /** The reported problem: with Rabta closed, no message or call arrived. A wake push starts Rabta, which connects. */
    @Test fun wakePushStartsRabtaWhichConnects() = fixture { server ->
        Push.received(ctx, mapOf("type" to "something else"))
        Thread.sleep(500)
        assertEquals("Only wake pushes start Rabta", 0, server.sockets.get())
        Push.received(ctx, mapOf("type" to "wake", "kind" to "call"))
        waitUi("Rabta connects after a wake push") { WsClient.connected && server.sockets.get() >= 1 }
        @Suppress("DEPRECATION")
        val services = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningServices(Int.MAX_VALUE)
        assertTrue("Rabta's message service runs, so the call's notification can show", services.any { it.service.className == MessageService::class.java.name })
    }
}
