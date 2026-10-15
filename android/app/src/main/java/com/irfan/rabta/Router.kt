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
class Router(private val ctx:Context,private val ui:UiListener):Mesh.Listener {
    interface UiListener {
        fun onMeshMessage(msgId:String,fromId:String,fromName:String,body:String,sentAt:Long,hops:Int,type:String="text",stored:()->Unit={}) {}
        fun onPeople() {}
        fun onStatus(msg:String) {}
        fun onAudio(frame:ByteArray) {}
    }
    companion object { const val START_HOPS=MeshPolicy.HOPS }
    private val mesh=Mesh(ctx,this)
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private lateinit var store:MeshStore
    private lateinit var security:MeshSecurity
    var myId=""; private set
    private var myName=""
    @Volatile var running=false; private set
    val connected get()=mesh.connected
    val longRangeOn=false // Legacy advertisements cannot safely carry encrypted envelopes.
    private val pulse=object:Runnable { override fun run() { if(!running) return; worker.execute { maintain(); flushPending(); carry() }; main.postDelayed(this,30000) } }
    fun start(userId:String,name:String) {
        if(running || !MeshPolicy.validId(userId)) return
        myId=userId; myName=name; store=MeshStore(ctx,myId); security=MeshSecurity(ctx,myId); running=true
        worker.execute {
            try { security.pair(); maintain(); main.post { if(running) { mesh.start(myName,myId); main.post(pulse) } } }
            catch(_:Exception) { running=false; main.post { ui.onStatus("Offline security key unavailable") } }
        }
    }
    fun stop() { running=false; main.removeCallbacks(pulse); mesh.stop(); worker.execute { store.close() }; worker.shutdown() }
    fun hasKey(peer:String)=running && security.key(peer)!=null
    fun sendDirect(toId:String,msgId:String,body:String,type:String="text"):Boolean {
        if(!running || !MeshPolicy.validId(toId) || !MeshPolicy.validId(msgId) || body.toByteArray().size>12000) return false
        if(type!="text" && !(type=="sticker" && Stickers.valid(body))) return false
        val ok=store.pending(MeshStore.Pending(msgId,toId,body,type,System.currentTimeMillis()))
        if(ok) worker.execute { flushPending(); carry() }
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
                if(store.put(p.id,env.toString(),env.getLong("x"),true)) { store.mark(p.id,env.getLong("x")); store.removePending(p.id); RabtaEvents.postAck(p.id,"mesh") }
            } catch(_:Exception) { main.post { ui.onStatus("Message waiting for offline security key") } }
        }
    }
    private fun keyPacket():String {
        val key=security.publicText()
        return JSONObject().put("v",2).put("k","key").put("f",myId).put("p",key).put("sig",MeshCrypto.sign(security.pair().private,myId+":"+key)).toString()
    }
    private var cursor=0
    private fun carry(endpoint:String?=null) {
        if(!running) return
        val packets=store.all()
        if(endpoint!=null) packets.forEach { mesh.sendEnvelopeTo(endpoint,it) }
        else if(packets.isNotEmpty()) repeat(minOf(32,packets.size)) { mesh.sendEnvelopeToAll(packets[(cursor++)%packets.size]); if(cursor>1000000)cursor=0 }
    }
    override fun onEnvelope(endpointId:String,json:String) {
        if(!running || json.toByteArray().size>MeshPolicy.MAX_PACKET) return
        worker.execute { runCatching { receive(endpointId,JSONObject(json)) } }
    }
    private fun receive(source:String,o:JSONObject) {
        if(!running || o.optInt("v")!=2) return
        val from=o.optString("f"); if(!MeshPolicy.validId(from) || from==myId) return
        val pub=o.optString("p"); val key=MeshCrypto.publicKey(pub)
        if(o.optString("k")=="key") {
            if(!MeshCrypto.verify(key,from+":"+pub,o.optString("sig"))) return
            // Only learn self-announced keys from the directly connected peer.
            if(mesh.userIdOf[source]!=from) return
            if(security.pin(from,pub)) flushPending()
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
                if(Db.get(ctx).conversationForMessage(original)==from) RabtaEvents.postAck(original,"delivered")
                store.mark(id,expiry); return
            }
        }
        if(to==myId && o.getString("k")=="message") {
            // Verify/decrypt even on duplicate; acknowledge only after durable inbox persistence.
            val plain=JSONObject(MeshCrypto.open(security.pair().private,MeshCrypto.Box(o.getString("e"),o.getString("iv"),o.getString("c")),aad(o)))
            val body=plain.getString("body"); val type=plain.optString("type","text")
            if(body.toByteArray().size>12000 || (type!="text" && !(type=="sticker" && Stickers.valid(body)))) return
            ui.onMeshMessage(id,from,plain.optString("name",from),body,sent,START_HOPS-h+1,type) {
                if(running) worker.execute { store.mark(id,expiry); receipt(id,from,expiry) }
            }
            return
        }
        if(store.seen(id)) return
        val remaining=MeshPolicy.forwardHops(h)
        if(remaining==0) { store.mark(id,expiry); return }
        o.put("h",remaining)
        if(store.put(id,o.toString(),expiry)) { store.mark(id,expiry); mesh.sendEnvelopeToAll(o.toString(),source) }
    }
    private fun receipt(original:String,to:String,originalExpiry:Long) {
        val id=MessageDigest.getInstance("SHA-256").digest(("receipt:"+myId+":"+original).toByteArray()).take(6).joinToString("") { "%02X".format(it) }
        val o=packet(id,to,"receipt",originalExpiry-MeshPolicy.LIFE,ack=original)
        store.put(id,o.toString(),o.getLong("x")); mesh.sendEnvelopeToAll(o.toString())
    }
    override fun onFound(endpointId:String,name:String,userId:String) { if(running && MeshPolicy.validId(userId) && myId<userId && !connected.containsKey(endpointId)) mesh.connect(endpointId); ui.onPeople() }
    override fun onConnected(endpointId:String,userId:String,name:String) {
        ui.onPeople()
        worker.execute { maintain(); mesh.sendEnvelopeTo(endpointId,keyPacket()); carry(endpointId) }
    }
    override fun onLost(endpointId:String)=ui.onPeople()
    override fun onConnecting(endpointId:String,name:String) {}
    override fun onDisconnected(endpointId:String)=ui.onPeople()
    override fun onFileStart(endpointId:String,payloadId:Long,name:String,size:Long) {}
    override fun onFileDone(endpointId:String,payloadId:Long) {}
    override fun onStream(endpointId:String,payload:Payload) {}
    override fun onAudio(endpointId:String,frame:ByteArray) {} // Never broadcast call audio to relay peers.
    override fun onStatus(msg:String)=ui.onStatus(msg)
}
