package com.irfan.rabta

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

/**
 * Transport layer over Google Nearby Connections (Bluetooth + Wi-Fi Direct, no internet).
 * It only moves bytes between directly-connected phones. The multi-hop mesh logic
 * (forwarding, carry-delivery, dedup) lives in Router.
 *
 * Endpoint name carries both the display name and a stable user id:  "name\u0001userId".
 */
class Mesh(private val ctx: Context, private val cb: Listener) {

    interface Listener {
        fun onFound(endpointId: String, name: String, userId: String)
        fun onLost(endpointId: String)
        fun onConnecting(endpointId: String, name: String)
        fun onConnected(endpointId: String, userId: String, name: String)
        fun onDisconnected(endpointId: String)
        fun onEnvelope(endpointId: String, json: String)     // a mesh message ("M" payloads)
        fun onFileStart(endpointId: String, payloadId: Long, name: String, size: Long)
        fun onFileDone(endpointId: String, payloadId: Long)
        fun onStream(endpointId: String, payload: Payload)
        fun onAudio(endpointId: String, frame: ByteArray)
        fun onStatus(msg: String)
    }

    companion object {
        const val SERVICE_ID = "com.irfan.rabta.v1"
        private const val SEP = "\u0001"
    }

    private val client by lazy { Nearby.getConnectionsClient(ctx) }
    private var advName = "Rabta user"
    val connected = LinkedHashMap<String, String>()   // endpointId -> display name
    val userIdOf = HashMap<String, String>()          // endpointId -> stable user id
    private val pendingFiles = HashMap<Long, String>() // payloadId -> endpointId

    fun start(name: String, userId: String) {
        advName = name.ifBlank { "Rabta user" } + SEP + userId
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(advName, SERVICE_ID, lifecycle, advertisingOptions)
            .addOnSuccessListener { cb.onStatus("Online - looking for people nearby") }
            .addOnFailureListener { cb.onStatus("Couldn't go online: ${it.message}") }
        client.startDiscovery(SERVICE_ID, discoveryCb, discoveryOptions)
            .addOnFailureListener { cb.onStatus("Discovery failed: ${it.message}") }
    }

    fun stop() {
        try { client.stopAdvertising() } catch (_: Exception) {}
        try { client.stopDiscovery() } catch (_: Exception) {}
        try { client.stopAllEndpoints() } catch (_: Exception) {}
        connected.clear(); userIdOf.clear()
    }

    fun connect(endpointId: String) {
        client.requestConnection(advName, endpointId, lifecycle)
            .addOnFailureListener { cb.onStatus("Connect failed: ${it.message}") }
    }

    // ---------- sending bytes ----------
    fun sendEnvelopeToAll(json: String, exceptEndpoint: String? = null) {
        val targets = connected.keys.filter { it != exceptEndpoint }
        if (targets.isEmpty()) return
        client.sendPayload(targets, Payload.fromBytes(("M" + json).toByteArray(Charsets.UTF_8)))
    }

    fun sendEnvelopeTo(endpointId: String, json: String) {
        client.sendPayload(endpointId, Payload.fromBytes(("M" + json).toByteArray(Charsets.UTF_8)))
    }

    fun sendFile(uri: android.net.Uri, displayName: String, size: Long) {
        if (connected.isEmpty()) { cb.onStatus("No one connected yet"); return }
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return
            val filePayload = Payload.fromFile(pfd)
            val header = "F${filePayload.id}:$size:$displayName"
            client.sendPayload(connected.keys.toList(), Payload.fromBytes(header.toByteArray(Charsets.UTF_8)))
            client.sendPayload(connected.keys.toList(), filePayload)
        } catch (e: Exception) { cb.onStatus("Couldn't send file: ${e.message}") }
    }

    fun sendStream(payload: Payload) { if (connected.isNotEmpty()) client.sendPayload(connected.keys.toList(), payload) }

    /** Live call audio: small frame sent as its own BYTES payload (prefix 'A'). */
    fun sendAudio(frame: ByteArray) {
        if (connected.isEmpty()) return
        val buf = ByteArray(frame.size + 1)
        buf[0] = 'A'.code.toByte()
        System.arraycopy(frame, 0, buf, 1, frame.size)
        client.sendPayload(connected.keys.toList(), Payload.fromBytes(buf))
    }

    // ---------- name parsing ----------
    private fun parseName(raw: String) = raw.substringBefore(SEP)
    private fun parseUserId(raw: String) = raw.substringAfter(SEP, "")

    // ---------- callbacks ----------
    private val discoveryCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId == SERVICE_ID) cb.onFound(endpointId, parseName(info.endpointName), parseUserId(info.endpointName))
        }
        override fun onEndpointLost(endpointId: String) { cb.onLost(endpointId) }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            cb.onConnecting(endpointId, parseName(info.endpointName))
            connected[endpointId] = parseName(info.endpointName)
            userIdOf[endpointId] = parseUserId(info.endpointName)
            client.acceptConnection(endpointId, payloadCb)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess)
                cb.onConnected(endpointId, userIdOf[endpointId] ?: "", connected[endpointId] ?: "User")
            else { connected.remove(endpointId); userIdOf.remove(endpointId); cb.onStatus("Connection failed") }
        }
        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId); userIdOf.remove(endpointId); cb.onDisconnected(endpointId)
        }
    }

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val raw = payload.asBytes() ?: return
                    if (raw.isEmpty()) return
                    when (raw[0].toInt().toChar()) {
                        'A' -> cb.onAudio(endpointId, raw.copyOfRange(1, raw.size))
                        'M' -> cb.onEnvelope(endpointId, String(raw, 1, raw.size - 1, Charsets.UTF_8))
                        'F' -> {
                            val body = String(raw, 1, raw.size - 1, Charsets.UTF_8)
                            val c1 = body.indexOf(':'); val c2 = body.indexOf(':', c1 + 1)
                            if (c1 > 0 && c2 > c1) {
                                val pid = body.substring(0, c1).toLongOrNull() ?: return
                                val size = body.substring(c1 + 1, c2).toLongOrNull() ?: 0
                                val name = body.substring(c2 + 1)
                                pendingFiles[pid] = endpointId
                                cb.onFileStart(endpointId, pid, name, size)
                            }
                        }
                    }
                }
                Payload.Type.FILE -> pendingFiles[payload.id] = endpointId
                Payload.Type.STREAM -> cb.onStream(endpointId, payload)
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val pid = update.payloadId
            if (pendingFiles.containsKey(pid) && update.status == PayloadTransferUpdate.Status.SUCCESS) {
                cb.onFileDone(pendingFiles[pid] ?: endpointId, pid); pendingFiles.remove(pid)
            }
        }
    }
}
