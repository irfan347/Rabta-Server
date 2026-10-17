package com.irfan.rabta

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.nearby.connection.Payload
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Rabta Drop through two real Routers, Android Keystore identities, SQLite history, FileProvider content and the
 * real Drop screen, joined by a simulated link. These are NOT radio tests: Bluetooth/Wi-Fi throughput is not measured.
 */
@RunWith(AndroidJUnit4::class)
class DropIntegrationTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private fun id() = java.util.UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
    private fun ui(block: () -> Unit) = inst.runOnMainSync(block)
    private fun until(message: String, test: () -> Boolean) {
        val end = System.currentTimeMillis() + 15000
        while (!test() && System.currentTimeMillis() < end) Thread.sleep(40)
        assertTrue(message, test())
    }
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private inner class Link(val account: String, val listener: Mesh.Listener) : MeshTransport {
        override val connected = ConcurrentHashMap<String, String>()
        override val userIdOf = ConcurrentHashMap<String, String>()
        val links = ConcurrentHashMap<String, Link>()
        val streams = ConcurrentHashMap<Long, InputStream>()
        @Volatile override var active = false
        override val discoveredCount get() = links.size
        override val connectingCount = 0
        override fun start(name: String, userId: String) { active = true }
        override fun stop() { active = false }
        override fun retryConnections() {}
        override fun connect(endpointId: String) {}
        override fun sendEnvelopeToAll(json: String, exceptEndpoint: String?) { links.keys.filter { it != exceptEndpoint }.forEach { sendEnvelopeTo(it, json) } }
        override fun sendEnvelopeTo(endpointId: String, json: String) { if (active) links[endpointId]?.takeIf { it.active }?.listener?.onEnvelope(account, json) }
        override fun sendDrop(endpointId: String, message: String): Boolean {
            val peer = links[endpointId]?.takeIf { active && it.active } ?: return false
            peer.listener.onDropControl(account, message); return true
        }
        override fun sendStream(endpointId: String, data: InputStream): Long? {
            val peer = links[endpointId]?.takeIf { active && it.active } ?: return null
            val payload = Payload.fromStream(data); streams[payload.id] = data
            peer.listener.onStream(account, payload); return payload.id
        }
        override fun cancelPayload(payloadId: Long) { streams.remove(payloadId)?.close() }
    }
    private inner class Node(val account: String = id()) {
        lateinit var wire: Link
        val router = Router(ctx, object : Router.UiListener {}) { callback -> Link(account, callback).also { wire = it } }
        init { ui { router.start(account, "Phone $account") }; until("Router must start") { wire.active } }
        val engine get() = Router::class.java.getDeclaredField("drop").apply { isAccessible = true }.get(router) as DropEngine?
        fun store() = DropEngine::class.java.getDeclaredField("store").apply { isAccessible = true }.get(engine) as DropStore
        fun history() = store().recent()
        fun close() {
            ui { router.stop() }
            (Router::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(router) as ExecutorService).awaitTermination(5, TimeUnit.SECONDS)
            ctx.deleteDatabase("mesh_v2_$account.db"); ctx.deleteDatabase("drop_$account.db")
            ctx.getSharedPreferences("mesh_keys_$account", 0).edit().clear().commit()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("rabta_mesh_v2_$account") }
        }
    }
    private fun link(a: Link, b: Link) {
        a.links[b.account] = b; b.links[a.account] = a
        a.connected[b.account] = "Phone ${b.account}"; b.connected[a.account] = "Phone ${a.account}"
        a.userIdOf[b.account] = b.account; b.userIdOf[a.account] = a.account
        ui { a.listener.onConnected(b.account, b.account, "Phone ${b.account}"); b.listener.onConnected(a.account, a.account, "Phone ${a.account}") }
    }

    private lateinit var sender: Node
    private lateinit var receiver: Node
    private lateinit var old: List<String>
    private lateinit var source: File
    private val saved = mutableListOf<String>()
    private var screen: Activity? = null

    @Before fun setUp() {
        Session.init(ctx)
        old = listOf(Session.id, Session.name, Session.username, Session.token, Session.serverUrl)
        sender = Node()
        receiver = Node()
        ctx.getSharedPreferences("appearance", 0).edit().putString("language", "en").putFloat("scale", 1f).commit()
        ui { MessageService.stop(ctx); WsClient.disconnect(); Session.id = receiver.account; Session.name = "Receiver"; Session.username = "drop_fixture"; Session.token = "local-drop-fixture"; Session.serverUrl = "https://127.0.0.1:8443" }
        until("Both Drop engines ready") { sender.engine != null && receiver.engine != null }
        RabtaDrop.attach(receiver.engine!!, receiver.store()) // the Drop screen shows this phone's (the receiver's) transfers
        link(sender.wire, receiver.wire)
        until("Both identities verified over the direct link") { sender.router.direct(receiver.account) && receiver.router.direct(sender.account) }
        source = File(ctx.filesDir, "attachments/drop-fixture-${sender.account}.pdf").also { it.parentFile?.mkdirs(); it.writeBytes(Random(7).nextBytes(5 * DropProtocol.CHUNK + 4321)) }
    }

    @After fun tearDown() {
        ui { screen?.finish() }
        saved.forEach { runCatching { ctx.contentResolver.delete(Uri.parse(it), null, null) } }
        source.delete(); sender.close(); receiver.close()
        ui { Session.id = old[0]; Session.name = old[1]; Session.username = old[2]; Session.token = old[3]; Session.serverUrl = old[4] }
    }

    private fun offerFromSender(): DropEngine.Transfer {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", source)
        sender.engine!!.offer(receiver.account, uri.toString())
        until("Receiver is asked for approval") { receiver.history().any { it.state == DropEngine.State.INCOMING } }
        return receiver.history().single()
    }
    private fun openDrop(): Activity = (inst.startActivitySync(Intent(ctx, DropActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity).also { screen = it }
    private fun labels(a: Activity): List<String> { var out = listOf<String>(); ui { out = all(a.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() } }; return out }
    private fun tap(a: Activity, offer: String, label: String) = ui {
        val card = all(a.window.decorView).first { it.tag == "drop-offer:$offer" }
        all(card).filterIsInstance<TextView>().first { it.text.toString() == label }.performClick()
    }

    @Test fun incomingFileWaitsForApprovalThenArrivesVerified() {
        val offer = offerFromSender()
        assertEquals("application/pdf", offer.mime); assertEquals(source.length(), offer.size)
        val a = openDrop()
        until("Approval card is shown") { labels(a).any { it.endsWith("wants to send you") } && labels(a).contains("Accept") && labels(a).contains(offer.name) }
        Thread.sleep(800)
        assertEquals("Nothing is accepted without the user", DropEngine.State.INCOMING, receiver.history().single().state)
        assertFalse("Nothing is written before approval", File(ctx.noBackupFilesDir, "drop/${offer.id}.part").exists())
        assertEquals(DropEngine.State.WAITING, sender.history().single().state)

        tap(a, offer.id, "Accept")
        until("Receiver saved a verified copy") { receiver.history().single().state == DropEngine.State.COMPLETED }
        until("Sender learns the receiver verified it") { sender.history().single().state == DropEngine.State.COMPLETED }
        val done = receiver.history().single(); saved += done.saved
        val bytes = ctx.contentResolver.openInputStream(Uri.parse(done.saved))!!.use { it.readBytes() }
        assertArrayEquals("Saved file is byte-identical", source.readBytes(), bytes)
        assertFalse("Private partial file removed", File(ctx.noBackupFilesDir, "drop/${offer.id}.part").exists())
        until("Screen shows the verified result") { labels(a).any { it == "Saved to Downloads/Rabta · verified" } && labels(a).contains("Open") }
    }

    @Test fun declinedFileIsNeverSaved() {
        val offer = offerFromSender()
        val a = openDrop()
        until("Approval card is shown") { labels(a).contains("Decline") }
        tap(a, offer.id, "Decline")
        until("Sender sees the decline") { sender.history().single().state == DropEngine.State.DECLINED }
        assertEquals(DropEngine.State.DECLINED, receiver.history().single().state)
        assertTrue(receiver.history().single().saved.isEmpty())
        until("Sender can offer it again") { sender.history().single().retryable }
    }
}
