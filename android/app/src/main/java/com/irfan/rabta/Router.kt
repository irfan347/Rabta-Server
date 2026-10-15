package com.irfan.rabta

import android.content.Context
import com.google.android.gms.nearby.connection.Payload
import org.json.JSONObject

/**
 * Offline mesh brain (no internet needed).
 *  - Identity = the user's server ID, so the same person is the same on internet and mesh.
 *  - Addressed messages: every phone forwards, only the recipient shows it.
 *  - Multi-hop (up to 8 hops) over Nearby (Bluetooth/Wi-Fi Direct) and Long Range.
 *  - Carry-delivery: messages are kept and handed to phones that connect later.
 *  - Phones connect to each other automatically, no tapping needed.
 *
 * Envelope JSON: i=msgId f=from n=fromName t=to h=hopsLeft s=sentAt b=body
 */
class Router(private val ctx: Context, private val ui: UiListener) : Mesh.Listener, LongRange.Listener {

    interface UiListener {
        fun onMeshMessage(msgId: String, fromId: String, fromName: String, body: String, sentAt: Long, hops: Int) {}
        fun onPeople() {}
        fun onStatus(msg: String) {}
        fun onAudio(frame: ByteArray) {}
    }

    companion object {
        const val START_HOPS = 8
        private const val MAX_STORE = 500
        private const val MAX_SEEN = 3000
        private const val EXPIRY_MS = 24 * 3600 * 1000L
        private val ID_RE = Regex("^[0-9A-F]{12}$")
    }

    private val mesh = Mesh(ctx, this)
    var myId: String = ""; private set
    private var myName = "Rabta user"
    var running = false; private set

    private val seen = object : LinkedHashMap<String, Boolean>(16, 0.75f, false) {
        override fun removeEldestEntry(e: Map.Entry<String, Boolean>?) = size > MAX_SEEN
    }
    private val store = LinkedHashMap<String, String>()  // id -> envelope (carry-delivery)
    private var lr: LongRange? = null
    var longRangeOn = false; private set

    val connected get() = mesh.connected

    fun start(userId: String, name: String) {
        if (running || !ID_RE.matches(userId)) return
        myId = userId; myName = name.ifBlank { "Rabta user" }
        mesh.start(myName, myId)
        lr = LongRange(ctx, this)
        longRangeOn = lr?.start(myId, myName) == true
        if (!longRangeOn) lr = null
        running = true
    }

    fun stop() {
        mesh.stop(); lr?.stop(); lr = null; longRangeOn = false; running = false
    }

    fun sendAudio(frame: ByteArray) = mesh.sendAudio(frame)

    /** Send a 1-to-1 message into the mesh. Returns false if the mesh isn't running. */
    fun sendDirect(toId: String, msgId: String, body: String): Boolean {
        if (!running || !ID_RE.matches(toId) || !ID_RE.matches(msgId)) return false
        val now = System.currentTimeMillis()
        val env = JSONObject().put("i", msgId).put("f", myId).put("n", myName).put("t", toId)
            .put("h", START_HOPS).put("s", now).put("b", body).toString()
        synchronized(seen) { seen[msgId] = true }
        remember(msgId, env)
        mesh.sendEnvelopeToAll(env)
        if (longRangeOn) lr?.broadcast(msgId, myId, toId, myName, START_HOPS, body)
        return true
    }

    // ---------- incoming (both radio paths) ----------
    override fun onEnvelope(endpointId: String, json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        handleIncoming(o, endpointId)
    }

    private fun handleIncoming(o: JSONObject, sourceEndpoint: String?) {
        val id = o.optString("i")
        if (id.isEmpty()) return
        synchronized(seen) { if (seen.containsKey(id)) return; seen[id] = true }
        val from = o.optString("f")
        if (from == myId) return
        val to = o.optString("t")
        val hopsLeft = o.optInt("h", 0)
        val body = o.optString("b")
        val name = o.optString("n", "Someone")
        val sentAt = o.optLong("s", System.currentTimeMillis())

        if (to == myId) {
            // it's for me: deliver, don't forward further
            ui.onMeshMessage(id, from, name, body, sentAt, START_HOPS - hopsLeft + 1)
            return
        }
        // for someone else: carry it and pass it on
        if (hopsLeft > 1) {
            val fwd = JSONObject(o.toString()).put("h", hopsLeft - 1).toString()
            remember(id, fwd)
            mesh.sendEnvelopeToAll(fwd, exceptEndpoint = sourceEndpoint)
            if (longRangeOn) lr?.broadcast(id, from, to, name, hopsLeft - 1, body)
        } else remember(id, o.toString())
    }

    // a phone came in range: connect automatically (lower ID asks, so both don't ask at once)
    override fun onFound(endpointId: String, name: String, userId: String) {
        if (!running || connected.containsKey(endpointId)) return
        if (userId.isEmpty() || myId < userId) mesh.connect(endpointId)
        ui.onPeople()
    }

    // a phone connected: hand it everything we're carrying
    override fun onConnected(endpointId: String, userId: String, name: String) {
        ui.onPeople(); ui.onStatus("Mesh: $name se juda")
        val now = System.currentTimeMillis()
        synchronized(store) {
            val it = store.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val ts = try { JSONObject(e.value).optLong("s") } catch (_: Exception) { 0L }
                if (now - ts > EXPIRY_MS) { it.remove(); continue }
                mesh.sendEnvelopeTo(endpointId, e.value)
            }
        }
    }

    private fun remember(id: String, env: String) {
        synchronized(store) {
            store[id] = env
            while (store.size > MAX_STORE) store.remove(store.keys.first())
        }
    }

    override fun onLost(endpointId: String) = ui.onPeople()
    override fun onConnecting(endpointId: String, name: String) {}
    override fun onDisconnected(endpointId: String) = ui.onPeople()
    override fun onFileStart(endpointId: String, payloadId: Long, name: String, size: Long) {}
    override fun onFileDone(endpointId: String, payloadId: Long) {}
    override fun onStream(endpointId: String, payload: Payload) {}
    override fun onAudio(endpointId: String, frame: ByteArray) = ui.onAudio(frame)
    override fun onStatus(msg: String) = ui.onStatus(msg)

    // ---------- Long Range ----------
    override fun onLrMessage(id: String, from: String, to: String, name: String, hopsLeft: Int, body: String) {
        val o = JSONObject().put("i", id).put("f", from).put("t", to).put("n", name)
            .put("h", hopsLeft).put("s", System.currentTimeMillis()).put("b", body)
        handleIncoming(o, null)
    }
    override fun onLrHello(userId: String, name: String, rssi: Int) {}
    override fun onLrStatus(msg: String) = ui.onStatus(msg)
}
