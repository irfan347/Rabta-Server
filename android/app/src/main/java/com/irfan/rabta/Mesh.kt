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
class Mesh(private val ctx: Context, private val cb: Listener) : MeshTransport {

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
    override val connected = java.util.concurrent.ConcurrentHashMap<String, String>()   // endpointId -> display name
    override val userIdOf = java.util.concurrent.ConcurrentHashMap<String, String>()          // endpointId -> stable user id
    private val pendingNames=java.util.concurrent.ConcurrentHashMap<String,Pair<String,String>>()
    private val pendingFiles = HashMap<Long, String>() // payloadId -> endpointId

    @Volatile override var active=false; private set
    @Volatile private var starting=false
    @Volatile private var epoch=0
    private var lifecycle:ConnectionLifecycleCallback?=null
    private var retryAt=0L
    private var selfId=""
    private val found=java.util.concurrent.ConcurrentHashMap<String,Pair<String,String>>()
    private val attempts=MeshAttempts()
    override val discoveredCount get()=found.size
    override val connectingCount get()=attempts.pending(android.os.SystemClock.elapsedRealtime())
    private fun current(generation:Int)=generation==epoch && (starting||active)
    override fun retryConnections() {
        if(!active)return
        found.forEach { (endpoint,person) -> if(selfId<person.second&&!connected.containsKey(endpoint))connect(endpoint) }
    }
    override fun start(name: String, userId: String) {
        if(starting||active||android.os.SystemClock.elapsedRealtime()<retryAt)return
        selfId=userId;advName=name.ifBlank { "Rabta user" }.take(24)+SEP+userId
        starting=true;val attempt=++epoch;var advertising=false;var discovering=false
        val callbacks=lifecycleFor(attempt);lifecycle=callbacks
        cb.onStatus("Starting Bluetooth / Wi-Fi discovery…")
        fun failed(error:Exception) {
            if(attempt!=epoch)return
            epoch++;starting=false;active=false;retryAt=android.os.SystemClock.elapsedRealtime()+10000
            runCatching {client.stopAdvertising();client.stopDiscovery();client.stopAllEndpoints()}
            connected.clear();userIdOf.clear();pendingNames.clear();found.clear();attempts.clear()
            val code=(error as? com.google.android.gms.common.api.ApiException)?.statusCode
            cb.onStatus("Nearby could not start${if(code!=null) " (code $code)" else ""} — retrying; check permissions and radios")
        }
        fun ready() {if(attempt==epoch&&advertising&&discovering){starting=false;active=true;cb.onStatus("Nearby searching — keep both phones open")} }
        try {
            client.stopAdvertising();client.stopDiscovery()
            client.startAdvertising(advName,SERVICE_ID,callbacks,AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnSuccessListener { if(attempt==epoch){advertising=true;ready()} }.addOnFailureListener {failed(it)}
            client.startDiscovery(SERVICE_ID,discoveryFor(attempt),DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnSuccessListener { if(attempt==epoch){discovering=true;ready()} }.addOnFailureListener {failed(it)}
        }catch(e:Exception){failed(e)}
    }

    override fun stop() {
        epoch++;starting=false;active=false;retryAt=0;lifecycle=null
        try {client.stopAdvertising();client.stopDiscovery();client.stopAllEndpoints()}catch(_:Exception){}
        connected.clear();userIdOf.clear();pendingNames.clear();found.clear();attempts.clear();pendingFiles.clear()
    }

    override fun connect(endpointId: String) {
        val callbacks=lifecycle?:return
        if((!active&&!starting)||connected.containsKey(endpointId)||!attempts.begin(endpointId,android.os.SystemClock.elapsedRealtime()))return
        val generation=epoch
        try { client.requestConnection(advName,endpointId,callbacks).addOnFailureListener {
            if(current(generation)){attempts.failed(endpointId);pendingNames.remove(endpointId);cb.onStatus("Nearby connection failed — retrying with backoff")}
        } }catch(_:Exception){attempts.failed(endpointId);cb.onStatus("Nearby connection unavailable — check permissions")}
    }

    override fun sendEnvelopeToAll(json:String,exceptEndpoint:String?) {
        sendBytes(connected.keys.filter { it!=exceptEndpoint },json)
    }
    override fun sendEnvelopeTo(endpointId:String,json:String) { sendBytes(listOf(endpointId),json) }
    private fun sendBytes(targets:List<String>,json:String) {
        if(!active || targets.isEmpty())return
        val generation=epoch
        try {
            client.sendPayload(targets,Payload.fromBytes(("M"+json).toByteArray(Charsets.UTF_8)))
                .addOnFailureListener { if(current(generation))cb.onStatus("Nearby transfer failed — encrypted queue will retry") }
        }catch(_:Exception){if(current(generation))cb.onStatus("Nearby transfer unavailable — encrypted queue retained")}
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
    private fun discoveryFor(generation:Int) = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if(!current(generation))return
            if (info.serviceId==SERVICE_ID) {
                val id=parseUserId(info.endpointName)
                if(!MeshPolicy.validId(id)||id==selfId)return
                if(found.size>=200&&!found.containsKey(endpointId))return
                found[endpointId]=parseName(info.endpointName) to id
                cb.onFound(endpointId,parseName(info.endpointName),id)
            }
        }
        override fun onEndpointLost(endpointId: String) { if(!current(generation))return;found.remove(endpointId);if(!pendingNames.containsKey(endpointId))attempts.remove(endpointId);cb.onLost(endpointId) }
    }

    private fun lifecycleFor(generation:Int) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if(!current(generation))return
            val id=parseUserId(info.endpointName)
            if(!MeshPolicy.validId(id)||id==selfId){client.rejectConnection(endpointId);return}
            attempts.initiated(endpointId,android.os.SystemClock.elapsedRealtime())
            cb.onConnecting(endpointId, parseName(info.endpointName))
            pendingNames[endpointId] = parseName(info.endpointName) to parseUserId(info.endpointName)
            try { client.acceptConnection(endpointId,payloadFor(generation)).addOnFailureListener { if(current(generation)){attempts.failed(endpointId);pendingNames.remove(endpointId);cb.onStatus("Nearby accept failed — retrying")} } } catch(_:Exception){attempts.failed(endpointId);pendingNames.remove(endpointId);cb.onStatus("Nearby permission changed — reconnecting")}
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if(!current(generation))return
            if (result.status.isSuccess) {
                attempts.remove(endpointId)
                val info=pendingNames.remove(endpointId) ?: return
                connected[endpointId]=info.first; userIdOf[endpointId]=info.second
                cb.onConnected(endpointId,info.second,info.first)
            }
            else { attempts.failed(endpointId);pendingNames.remove(endpointId);connected.remove(endpointId); userIdOf.remove(endpointId); cb.onStatus("Nearby connection failed — retrying") }
        }
        override fun onDisconnected(endpointId: String) {
            if(!current(generation))return
            attempts.remove(endpointId);pendingNames.remove(endpointId)
            connected.remove(endpointId); userIdOf.remove(endpointId); cb.onDisconnected(endpointId)
        }
    }

    private fun payloadFor(generation:Int) = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if(!current(generation)||!connected.containsKey(endpointId))return
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
            if(!current(generation))return
            val pid = update.payloadId
            if (pendingFiles.containsKey(pid) && update.status == PayloadTransferUpdate.Status.SUCCESS) {
                cb.onFileDone(pendingFiles[pid] ?: endpointId, pid); pendingFiles.remove(pid)
            }
        }
    }
}
