package com.irfan.rabta

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.connection.Payload
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.Executors

/** V2 store-carry-forward. Relays only carry ciphertext. V1 plaintext is not forwarded. */
class Router(private val ctx:Context,private val ui:UiListener,transportFactory:((Mesh.Listener)->MeshTransport)?=null):Mesh.Listener {
    interface UiListener {
        fun onMeshMessage(msgId:String,fromId:String,fromName:String,body:String,sentAt:Long,hops:Int,type:String="text",stored:()->Unit={}) {}
        fun onPeople() {}
        fun onStatus(msg:String) {}
        fun onAudio(frame:ByteArray) {}
    }
    companion object { const val START_HOPS=MeshPolicy.HOPS }
    private val mesh:MeshTransport=transportFactory?.invoke(this)?:Mesh(ctx,this)
    private val matchedKeys=java.util.concurrent.ConcurrentHashMap<String,String>()
    private val relayAccepted=java.util.concurrent.atomic.AtomicLong()
    private val receiptsAccepted=java.util.concurrent.atomic.AtomicLong()
    private var transportPaused=true
    fun snapshot()=MeshRuntimeSnapshot(running,mesh.active,mesh.discoveredCount,mesh.connectingCount,
        mesh.connected.map { (endpoint,name)->MeshPeer(mesh.userIdOf[endpoint].orEmpty(),name,matchedKeys[endpoint]==mesh.userIdOf[endpoint] && matchedKeys.containsKey(endpoint)) },relayAccepted.get(),receiptsAccepted.get())
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private lateinit var store:MeshStore
    private lateinit var security:MeshSecurity
    var myId=""; private set
    private var myName=""
    @Volatile private var securityReady=false
    fun retryTransport(){if(running&&securityReady){transportPaused=false;mesh.start(myName,myId);mesh.retryConnections()}}
    fun pauseTransport(){if(!transportPaused){transportPaused=true;mesh.stop();matchedKeys.clear()}}
    @Volatile var running=false; private set
    val connected get()=mesh.connected
    val longRangeOn=false // Legacy advertisements cannot safely carry encrypted envelopes.
    private val pulse=object:Runnable { override fun run() { if(!running) return; enqueue { maintain(); flushPending(); carry() }; main.postDelayed(this,30000) } }
    fun start(userId:String,name:String) {
        if(running || !MeshPolicy.validId(userId)) return
        myId=userId; myName=name; store=MeshStore(ctx,myId); security=MeshSecurity(ctx,myId); running=true
        worker.execute {
            try { security.pair(); maintain(); securityReady=true;main.post { if(running) { retryTransport(); main.post(pulse) } } }
            catch(_:Exception) { running=false; main.post { ui.onStatus("Offline security key unavailable") } }
        }
    }
    @Synchronized private fun enqueue(action:()->Unit) {
        if(!running||worker.isShutdown)return
        worker.execute { if(running)runCatching(action).onFailure { main.post { if(running)ui.onStatus("Nearby queue operation failed — retained messages will retry") } } }
    }
    @Synchronized fun stop() {
        if(worker.isShutdown)return
        running=false;securityReady=false;main.removeCallbacks(pulse);mesh.stop();matchedKeys.clear()
        worker.execute { if(::store.isInitialized)store.close() };worker.shutdown()
    }
    fun hasKey(peer:String)=running && security.key(peer)!=null
    @Synchronized fun sendDirect(toId:String,msgId:String,body:String,type:String="text"):Boolean {
        if(!running || toId==myId || !MeshPolicy.validId(toId) || !MeshPolicy.validId(msgId) || body.toByteArray().size>12000) return false
        if(type!="text" && !(type=="sticker" && Stickers.valid(body))) return false
        if(store.contains(msgId))return true
        val ok=store.pending(MeshStore.Pending(msgId,toId,body,type,System.currentTimeMillis()))
        if(ok) enqueue { flushPending(); carry() }
        return ok
    }
    private fun maintain() {
        store.prune(System.currentTimeMillis()).forEach { id -> RabtaEvents.postAck(id,"expired") }
    }
    private fun aad(o:JSONObject)=JSONArray(listOf(o.getString("i"),o.getString("f"),o.getString("t"),o.getLong("s"),o.getLong("x"))).toString()
    private fun signed(o:JSONObject)=JSONArray(listOf(o.getInt("v"),o.getString("k"),aad(o),o.getString("p"),o.optString("e"),o.optString("iv"),o.optString("c"),o.optString("a"))).toString()
    private fun packet(id:String,to:String,kind:String,sent:Long,plain:String="",ack:String=""):JSONObject {
        val o=JSONObject().put("v",2).put("k",kind).put("i",id).put("f",myId).put("t",to).put("s",sent).put("x",sent+MeshPolicy.LIFE).put("h",START_HOPS).put("p",security.publicText()).put("a",ack)
        if(kind=="message") {
            val key=security.key(to) ?: error("Contact key unavailable")
            val box=MeshCrypto.seal(MeshCrypto.publicKey(key),plain,aad(o))
            o.put("e",box.key).put("iv",box.iv).put("c",box.ciphertext)
        }
        o.put("sig",MeshCrypto.sign(security.pair().private,signed(o))); return o
    }
    private fun flushPending() {
        if(!running) return
        for(p in store.pending()) {
            if(System.currentTimeMillis()-p.sent>=MeshPolicy.LIFE) continue
            if(security.key(p.to)==null) continue
            try {
                val plain=JSONObject().put("body",p.body).put("type",p.type).put("name",myName).toString()
                val env=packet(p.id,p.to,"message",p.sent,plain)
                if(store.promote(p,env.toString(),env.getLong("x"))) RabtaEvents.postAck(p.id,"mesh")
                else main.post { ui.onStatus("Nearby carry queue full — pending message retained") }
            } catch(_:Exception) { main.post { ui.onStatus("Message waiting for offline security key") } }
        }
    }
    private fun keyPacket():String {
        val key=security.publicText()
        return JSONObject().put("v",2).put("k","key").put("f",myId).put("p",key).put("sig",MeshCrypto.sign(security.pair().private,myId+":"+key)).toString()
    }
    private var cursor=0
    private fun carry(endpoint:String?=null) {
        if(!running || mesh.connected.isEmpty()) return
        val packets=store.all()
        if(packets.isEmpty())return
        // Bounded replay even on reconnect; later pulses rotate through the durable queue.
        repeat(minOf(32,packets.size)) {
            val packet=packets[(cursor++)%packets.size]
            if(endpoint==null)mesh.sendEnvelopeToAll(packet) else mesh.sendEnvelopeTo(endpoint,packet)
            if(cursor>1000000)cursor=0
        }
    }
    override fun onEnvelope(endpointId:String,json:String) {
        if(!running || json.toByteArray().size>MeshPolicy.MAX_PACKET) return
        enqueue { receive(endpointId,JSONObject(json)) }
    }
    private fun receive(source:String,o:JSONObject) {
        if(!running || o.optInt("v")!=2) return
        val from=o.optString("f"); if(!MeshPolicy.validId(from) || from==myId) return
        val pub=o.optString("p"); val key=MeshCrypto.publicKey(pub)
        if(o.optString("k")=="key") {
            if(!MeshCrypto.verify(key,from+":"+pub,o.optString("sig"))) return
            // Only learn self-announced keys from the directly connected peer.
            if(mesh.userIdOf[source]!=from) return
            if(security.pin(from,pub)) { matchedKeys[source]=from;flushPending();carry(source) }
            else main.post { ui.onStatus("Nearby identity key changed — compare contact security codes") }
            return
        }
        val id=o.optString("i"); val to=o.optString("t"); val h=o.optInt("h")
        if(!MeshPolicy.validId(id) || !MeshPolicy.validId(to) || !MeshPolicy.canReceive(h)) return
        val sent=o.optLong("s"); val expiry=o.optLong("x")
        if(!MeshPolicy.validTime(sent,expiry,System.currentTimeMillis())) return
        if(o.optString("k") !in setOf("message","receipt")) return
        if(!MeshCrypto.verify(key,signed(o),o.optString("sig"))) return
        if(!security.pin(from,pub)) return // changed identities never silently replace pinned keys
        if(o.getString("k")=="receipt") {
            val original=o.optString("a")
            if(!MeshPolicy.validId(original)) return
            val packet=store.all().firstOrNull { JSONObject(it).optString("i")==original }?.let { JSONObject(it) }
            if(packet!=null && packet.optString("t")==from && packet.optString("f")==to) store.remove(original)
            if(to==myId) {
                if(!store.seen(id) && Db.get(ctx).status(original)!=null && Db.get(ctx).conversationForMessage(original)==from) {
                    receiptsAccepted.incrementAndGet();RabtaEvents.postAck(original,"delivered")
                }
                store.mark(id,expiry); return
            }
        }
        if(to==myId && o.getString("k")=="message") {
            // Verify/decrypt even on duplicate; acknowledge only after durable inbox persistence.
            val plain=JSONObject(MeshCrypto.open(security.pair().private,MeshCrypto.Box(o.getString("e"),o.getString("iv"),o.getString("c")),aad(o)))
            val body=plain.getString("body"); val type=plain.optString("type","text")
            if(body.toByteArray().size>12000 || (type!="text" && !(type=="sticker" && Stickers.valid(body)))) return
            ui.onMeshMessage(id,from,plain.optString("name",from),body,sent,START_HOPS-h+1,type) {
                enqueue { store.mark(id,expiry); receipt(id,from,expiry) }
            }
            return
        }
        if(store.seen(id)) return
        val remaining=MeshPolicy.forwardHops(h)
        if(remaining==0) { store.mark(id,expiry); return }
        o.put("h",remaining)
        if(store.put(id,o.toString(),expiry)) { relayAccepted.incrementAndGet();store.mark(id,expiry);mesh.sendEnvelopeToAll(o.toString(),source) }
    }
    private fun receipt(original:String,to:String,originalExpiry:Long) {
        val id=MessageDigest.getInstance("SHA-256").digest(("receipt:"+myId+":"+original).toByteArray()).take(6).joinToString("") { "%02X".format(it) }
        val o=packet(id,to,"receipt",originalExpiry-MeshPolicy.LIFE,ack=original)
        store.put(id,o.toString(),o.getLong("x")); mesh.sendEnvelopeToAll(o.toString())
    }
    override fun onFound(endpointId:String,name:String,userId:String) { if(running && MeshPolicy.validId(userId) && myId<userId && !connected.containsKey(endpointId)) mesh.connect(endpointId); ui.onPeople() }
    override fun onConnected(endpointId:String,userId:String,name:String) {
        ui.onStatus("Nearby: ${connected.size} phone(s) connected");ui.onPeople()
        enqueue { maintain();mesh.sendEnvelopeTo(endpointId,keyPacket());carry(endpointId) }
    }
    override fun onLost(endpointId:String)=ui.onPeople()
    override fun onConnecting(endpointId:String,name:String) {}
    override fun onDisconnected(endpointId:String) {matchedKeys.remove(endpointId);ui.onStatus(if(connected.isEmpty())"Nearby peer disconnected — searching again" else "Nearby: ${connected.size} phone(s) connected");ui.onPeople()}
    override fun onFileStart(endpointId:String,payloadId:Long,name:String,size:Long) {}
    override fun onFileDone(endpointId:String,payloadId:Long) {}
    override fun onStream(endpointId:String,payload:Payload) {}
    override fun onAudio(endpointId:String,frame:ByteArray) {} // Never broadcast call audio to relay peers.
    override fun onStatus(msg:String)=ui.onStatus(msg)
}
