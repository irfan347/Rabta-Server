package com.irfan.rabta

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.Payload
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Router, SQLite and Android Keystore with simulated links. These are NOT radio tests. */
@RunWith(AndroidJUnit4::class)
class MeshIntegrationTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private fun id()=java.util.UUID.randomUUID().toString().replace("-","").take(12).uppercase()
    private fun until(message:String,test:()->Boolean) {
        val end=System.currentTimeMillis()+12000
        while(!test()&&System.currentTimeMillis()<end)Thread.sleep(30)
        assertTrue(message,test())
    }
    private fun executor(router:Router)=Router::class.java.getDeclaredField("worker").apply { isAccessible=true }.get(router) as ExecutorService
    private fun barrier(router:Router) { executor(router).submit {}.get(5,TimeUnit.SECONDS) }
    private fun stop(router:Router) { inst.runOnMainSync { router.stop() };assertTrue(executor(router).awaitTermination(5,TimeUnit.SECONDS)) }
    private fun clean(account:String) {
        ctx.deleteDatabase("mesh_v2_$account.db");ctx.deleteDatabase("mesh_test_inbox_$account.db")
        ctx.getSharedPreferences("mesh_keys_$account",0).edit().clear().commit()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("rabta_mesh_v2_$account") }
    }
    private inner class Link(val account:String,val listener:Mesh.Listener):MeshTransport {
        override val connected=ConcurrentHashMap<String,String>()
        override val userIdOf=ConcurrentHashMap<String,String>()
        val links=ConcurrentHashMap<String,Link>()
        val sent=CopyOnWriteArrayList<String>()
        @Volatile override var active=false
        override val discoveredCount get()=links.size
        override val connectingCount=0
        override fun start(name:String,userId:String) { active=true }
        override fun stop() { active=false;links.keys.toList().forEach { endpoint -> links[endpoint]?.let { unlink(this,it) } } }
        override fun retryConnections() {}
        override fun connect(endpointId:String) {}
        override fun sendEnvelopeToAll(json:String,exceptEndpoint:String?) { links.keys.filter { it!=exceptEndpoint }.forEach { sendEnvelopeTo(it,json) } }
        override fun sendEnvelopeTo(endpointId:String,json:String) {
            if(!active)return
            sent.add(json)
            links[endpointId]?.takeIf { it.active }?.listener?.onEnvelope(account,json)
        }
    }
    private fun link(a:Link,b:Link) {
        a.links[b.account]=b;b.links[a.account]=a
        a.connected[b.account]=b.account;b.connected[a.account]=a.account
        a.userIdOf[b.account]=b.account;b.userIdOf[a.account]=a.account
        a.listener.onConnected(b.account,b.account,b.account);b.listener.onConnected(a.account,a.account,a.account)
    }
    private fun unlink(a:Link,b:Link) {
        a.links.remove(b.account);b.links.remove(a.account)
        a.connected.remove(b.account);b.connected.remove(a.account)
        a.userIdOf.remove(b.account);b.userIdOf.remove(a.account)
        a.listener.onDisconnected(b.account);b.listener.onDisconnected(a.account)
    }
    private inner class Node(val account:String=id()) {
        val received=AtomicInteger()
        @Volatile var allowPersistence=true
        val inbox=ctx.openOrCreateDatabase("mesh_test_inbox_$account.db",0,null).apply { execSQL("CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY,body TEXT)") }
        lateinit var wire:Link
        val router=Router(ctx,object:Router.UiListener {
            override fun onMeshMessage(msgId:String,fromId:String,fromName:String,body:String,sentAt:Long,hops:Int,type:String,stored:()->Unit) {
                received.incrementAndGet()
                if(!allowPersistence)return
                inbox.insertWithOnConflict("messages",null,ContentValues().apply { put("id",msgId);put("body",body) },SQLiteDatabase.CONFLICT_IGNORE)
                stored()
            }
        }) { callback -> Link(account,callback).also { wire=it } }
        init { inst.runOnMainSync { router.start(account,account) };until("Router must start") { wire.active } }
        fun count()=inbox.rawQuery("SELECT COUNT(*) FROM messages",null).use { it.moveToFirst();it.getInt(0) }
        fun close() { stop(router);inbox.close() }
    }
    private fun packet(account:String,message:String)=MeshStore(ctx,account).use { store -> store.all().firstOrNull { JSONObject(it).optString("i")==message } }
    private fun senderRecord(a:Node,b:Node,message:String) {
        Session.init(ctx)
        Db.get(ctx).saveMessage(message,b.account,a.account,"offline test",System.currentTimeMillis(),true,"mesh")
    }
    private fun deleteFixtureMessage(message:String) { Db.get(ctx).writableDatabase.delete("messages","id=?",arrayOf(message)) }

    @Test fun directDiscoveryKeyExchangeRetryAndDuplicateDelivery() {
        val a=Node();val b=Node();val message=id()
        try {
            b.allowPersistence=false;senderRecord(a,b,message)
            assertTrue(a.router.sendDirect(b.account,message,"offline test"))
            barrier(a.router)
            assertEquals(1,MeshStore(ctx,a.account).use { it.pending().size })
            link(a.wire,b.wire)
            until("Encrypted payload reaches recipient") { b.received.get()>0 }
            val envelope=packet(a.account,message)!!
            assertFalse(envelope.contains("offline test"))
            assertEquals("No receipt before durable persistence",0L,a.router.snapshot().receiptsAccepted)
            assertNotNull(packet(a.account,message))
            b.allowPersistence=true;unlink(a.wire,b.wire);link(a.wire,b.wire)
            until("Recipient receipt clears sender carry queue") { packet(a.account,message)==null }
            until("Signed receipt is recorded") { a.router.snapshot().receiptsAccepted==1L }
            b.router.onEnvelope(a.account,envelope);b.router.onEnvelope(a.account,envelope)
            barrier(b.router);barrier(a.router)
            assertEquals(1,b.count())
            assertEquals(1L,a.router.snapshot().receiptsAccepted)
            assertTrue(a.router.snapshot().peers.single().keyMatched)
        }finally{a.close();b.close();clean(a.account);clean(b.account);deleteFixtureMessage(message)}
    }

    @Test fun simulatedRelayCarriesAcrossRestartAndVerifiesReceipts() {
        val a=Node();var b=Node();val c=Node();val relayId=b.account;val message=id()
        try {
            senderRecord(a,c,message)
            assertTrue(MeshSecurity(ctx,a.account).pin(c.account,MeshSecurity(ctx,c.account).publicText()))
            link(a.wire,b.wire)
            assertTrue(a.router.sendDirect(c.account,message,"offline test"))
            until("Relay stores ciphertext") { packet(b.account,message)!=null }
            val carried=packet(b.account,message)!!
            assertEquals(7,JSONObject(carried).getInt("h"));assertEquals(0,c.count())
            val tampered=JSONObject(carried).put("c","altered ciphertext").toString()
            c.router.onEnvelope(b.account,tampered);barrier(c.router)
            assertEquals("Tampering cannot reach the inbox",0,c.count())
            b.close();b=Node(relayId)
            assertNotNull("Relay queue survives restart",packet(b.account,message))
            link(b.wire,c.wire)
            until("Recipient persists carried packet") { c.count()==1 }
            until("Relay validates returning receipt") { packet(b.account,message)==null }
            unlink(b.wire,c.wire);link(a.wire,b.wire)
            until("Receipt returns to original sender") { packet(a.account,message)==null && a.router.snapshot().receiptsAccepted==1L }
            assertEquals(1,c.count())
            val pinned=MeshSecurity(ctx,c.account).key(a.account)
            val changed=MeshSecurity(ctx,b.account).publicText()
            assertFalse(MeshSecurity(ctx,c.account).pin(a.account,changed))
            assertEquals(pinned,MeshSecurity(ctx,c.account).key(a.account))
        }finally{a.close();b.close();c.close();clean(a.account);clean(relayId);clean(c.account);deleteFixtureMessage(message)}
    }

    @Test fun fullQueuesAllowIdempotentRetryWithoutReplacingDataOrExtendingExpiry() {
        val account=id();val now=System.currentTimeMillis();val first=MeshStore.Pending("100000000001","100000000002","original","text",now)
        try {
            MeshStore(ctx,account).use { s ->
                assertTrue(s.pending(first))
                for(i in 2..100)assertTrue(s.pending(first.copy(id="%012X".format(i))))
                assertTrue(s.pending(first.copy(sent=now+5000)))
                assertFalse(s.pending(first.copy(body="replacement")))
                assertEquals(now,s.pending().first { it.id==first.id }.sent)
                for(i in 1..500)assertTrue(s.put("%012X".format(i),"packet-$i",now+10000))
                assertTrue(s.put("000000000001","packet-1",now+20000))
                assertFalse(s.put("000000000001","changed",now+20000))
                assertEquals(now+10000,s.readableDatabase.rawQuery("SELECT expires FROM packets WHERE id='000000000001'",null).use { it.moveToFirst();it.getLong(0) })
                assertFalse(s.promote(first,"encrypted",now+MeshPolicy.LIFE))
                assertTrue(s.pending().any { it.id==first.id })
                s.remove("000000000001")
                assertTrue(s.promote(first,"encrypted",now+MeshPolicy.LIFE))
            }
            MeshStore(ctx,account).use { s ->
                assertTrue(s.contains(first.id));assertTrue(s.seen(first.id));assertFalse(s.pending().any { it.id==first.id })
                assertEquals(500,s.all().size)
            }
        }finally{clean(account)}
    }

    @Test fun actualInboxAcknowledgesDuplicatesOnlyAfterStoredOnce() {
        Session.init(ctx);val message=id();val peer=id();val stored=CountDownLatch(2)
        try {
            repeat(2) { Inbox.receive(message,peer,"Mesh fixture","same payload",System.currentTimeMillis(),peer,"Mesh fixture",stored={stored.countDown()}) }
            assertTrue(stored.await(5,TimeUnit.SECONDS))
            assertEquals(1,Db.get(ctx).messages(peer).count { it.id==message })
        }finally{deleteFixtureMessage(message);Db.get(ctx).writableDatabase.delete("conversations","peer_id=?",arrayOf(peer))}
    }

    @Test fun staleNearbyDisconnectCannotRemoveNewGenerationPeer() {
        val notifications=AtomicInteger()
        val listener=object:Mesh.Listener {
            override fun onFound(endpointId:String,name:String,userId:String) {}
            override fun onLost(endpointId:String) {}
            override fun onConnecting(endpointId:String,name:String) {}
            override fun onConnected(endpointId:String,userId:String,name:String) {}
            override fun onDisconnected(endpointId:String) { notifications.incrementAndGet() }
            override fun onEnvelope(endpointId:String,json:String) {}
            override fun onFileStart(endpointId:String,payloadId:Long,name:String,size:Long) {}
            override fun onFileDone(endpointId:String,payloadId:Long) {}
            override fun onStream(endpointId:String,payload:Payload) {}
            override fun onAudio(endpointId:String,frame:ByteArray) {}
            override fun onStatus(msg:String) {}
        }
        val mesh=Mesh(ctx,listener)
        Mesh::class.java.getDeclaredField("epoch").apply { isAccessible=true;setInt(mesh,2) }
        Mesh::class.java.getDeclaredField("active").apply { isAccessible=true;setBoolean(mesh,true) }
        mesh.connected["endpoint"]="New connection";mesh.userIdOf["endpoint"]="100000000001"
        val method=Mesh::class.java.getDeclaredMethod("lifecycleFor",Int::class.javaPrimitiveType).apply { isAccessible=true }
        (method.invoke(mesh,1) as ConnectionLifecycleCallback).onDisconnected("endpoint")
        assertEquals(1,mesh.connected.size);assertEquals(0,notifications.get())
        (method.invoke(mesh,2) as ConnectionLifecycleCallback).onDisconnected("endpoint")
        assertEquals(0,mesh.connected.size);assertEquals(1,notifications.get())
    }
}
