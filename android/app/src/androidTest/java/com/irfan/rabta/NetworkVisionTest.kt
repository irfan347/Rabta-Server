package com.irfan.rabta

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real Router/Keystore and Activity with simulated transport observations, not physical radios. */
@RunWith(AndroidJUnit4::class)
class NetworkVisionTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private fun id()=java.util.UUID.randomUUID().toString().replace("-","").take(12).uppercase()
    private fun ui(run:()->Unit)=inst.runOnMainSync(run)
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))}else emptyList()
    private fun waitUi(message:String,test:()->Boolean) {
        val end=System.currentTimeMillis()+12000
        do {inst.waitForIdleSync();var ok=false;ui {ok=test()};if(ok)return;Thread.sleep(100)}while(System.currentTimeMillis()<end)
        fail(message)
    }
    private class Wire:MeshTransport {
        override val connected=ConcurrentHashMap<String,String>()
        override val userIdOf=ConcurrentHashMap<String,String>()
        val discovered=ConcurrentHashMap.newKeySet<String>()
        override var active=false
        var starts=0
        override val discoveredCount get()=discovered.size
        override val connectingCount=0
        override fun start(name:String,userId:String){active=true;starts++}
        override fun stop(){active=false;connected.clear();userIdOf.clear();discovered.clear()}
        override fun discoveredPeerIds()=discovered.toSet()
        override fun retryConnections(){}
        override fun connect(endpointId:String){fail("Vision must not connect")}
        override fun sendEnvelopeToAll(json:String,exceptEndpoint:String?){}
        override fun sendEnvelopeTo(endpointId:String,json:String){}
    }
    private fun prepare(account:String) {
        ui {MessageService.stop(ctx);Session.init(ctx);WsClient.disconnect();Session.id=account;Session.name="PRIVATE OWNER";Session.token="PRIVATE VISION TOKEN";Session.serverUrl="https://127.0.0.1:8443"}
        inst.waitForIdleSync()
        ctx.getSharedPreferences("appearance",0).edit().putString("language","en").putFloat("scale",1f).commit()
    }
    private fun clean(account:String) {
        ctx.deleteDatabase("mesh_v2_$account.db")
        ctx.getSharedPreferences("mesh_keys_$account",0).edit().clear().commit()
        ctx.getSharedPreferences("radar_$account",0).edit().clear().commit()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry("rabta_mesh_v2_$account")}
    }
    private fun launch()=inst.startActivitySync(Intent(ctx,NetworkVisionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NetworkVisionActivity
    private fun labels(a:NetworkVisionActivity)=all(a.window.decorView).filterIsInstance<TextView>().map {it.text.toString()}
    private fun counts(a:NetworkVisionActivity)=all(a.window.decorView).filterIsInstance<TextView>().first {it.tag=="vision-counts"}.text.toString()
    private fun worker(r:Router)=Router::class.java.getDeclaredField("worker").apply {isAccessible=true}.get(r) as ExecutorService
    private fun close(r:Router){ui {r.stop();MeshBridge.detach()};assertTrue(worker(r).awaitTermination(5,TimeUnit.SECONDS))}

    @Test fun emptyScreenKeepsDiscoveryOptedOutAndNavigationIsReal() {
        val account=id();prepare(account);ui {RadarPrefs.setEnabled(ctx,false);MeshBridge.detach()}
        val a=launch()
        try {
            waitUi("Empty state") {labels(a).contains("No nearby devices yet")}
            ui {
                assertTrue(counts(a).startsWith("0 nearby peers"));assertFalse(RadarPrefs.enabled(ctx))
                assertTrue(labels(a).any {it.contains("Verified remote relay links: unavailable")})
                assertFalse((all(a.window.decorView).first {it.tag=="network-graph"} as NetworkVisionView).animationRunning())
                assertFalse(labels(a).any {it.contains(account)||it.contains(Session.token)||it.contains("PRIVATE OWNER")})
                val panel=NearbyPanel(a)
                assertTrue(all(panel.view).filterIsInstance<TextView>().any {it.text=="Network Vision"})
            }
        }finally{ui {a.finish()};inst.waitForIdleSync();clean(account)}
    }

    @Test fun discoveredConnectedVerifiedLostAndOptOutUpdateRealViewWithoutPrivateData() {
        val account=id();val peer=id();prepare(account);val wire=Wire()
        val router=Router(ctx,object:Router.UiListener {}) {wire}
        ui {RadarPrefs.setEnabled(ctx,true);router.start(account,"PRIVATE OWNER");MeshBridge.attach(router)}
        val until=System.currentTimeMillis()+10000
        while(!wire.active&&System.currentTimeMillis()<until)Thread.sleep(30)
        assertTrue(wire.active)
        val a=launch()
        try {
            wire.discovered.add(peer)
            waitUi("Discovered peer") {counts(a).startsWith("1 nearby peers · 0 direct")}
            ui {all(a.window.decorView).first {it.contentDescription=="Peer 1 details"}.performClick()}
            waitUi("Peer details") {labels(a).any {it.startsWith("Peer 1\nDiscovered")}}
            wire.connected["endpoint"]= "PRIVATE PEER NAME";wire.userIdOf["endpoint"]=peer
            router.onConnected("endpoint",peer,"PRIVATE PEER NAME")
            waitUi("Direct connection") {counts(a).contains("1 direct")&&labels(a).any {it.contains("Direct · verifying identity key")}}
            val security=MeshSecurity(ctx,peer);val key=security.publicText()
            router.onEnvelope("endpoint",JSONObject().put("v",2).put("k","key").put("f",peer).put("p",key).put("sig",MeshCrypto.sign(security.pair().private,peer+":"+key)).toString())
            waitUi("Real signed key exchange") {labels(a).any {it.contains("Direct · key matches pinned identity")}}
            wire.discovered.clear()
            waitUi("Live connection retained after advertising loss") {counts(a).contains("1 direct · 0 discovered")}
            ui {
                assertEquals(1,wire.starts)
                assertFalse(labels(a).any {it.contains(peer)||it.contains(account)||it.contains("PRIVATE PEER NAME")||it.contains("PRIVATE VISION TOKEN")})
            }
            wire.connected.clear();wire.userIdOf.clear();router.onDisconnected("endpoint")
            waitUi("Disconnected peers removed") {counts(a).startsWith("0 nearby peers")}
            wire.discovered.add(peer)
            waitUi("Discovery resumes") {counts(a).startsWith("1 nearby peers")}
            ui {RadarPrefs.setEnabled(ctx,false)}
            waitUi("Opt-out removes nodes") {counts(a).startsWith("0 nearby peers")&&labels(a).any {it.contains("Radar is off")}}
        }finally{ui {a.finish()};inst.waitForIdleSync();close(router);clean(account);clean(peer)}
    }

    @Test fun graphTapAndSignedRecipientReceiptProducePrivateDeliveryEvent() {
        val account=id();val peer=id();val message=id();prepare(account);val wire=Wire()
        val router=Router(ctx,object:Router.UiListener {}) {wire}
        ui {RadarPrefs.setEnabled(ctx,true);router.start(account,"PRIVATE OWNER");MeshBridge.attach(router)}
        val until=System.currentTimeMillis()+10000
        while(!wire.active&&System.currentTimeMillis()<until)Thread.sleep(30)
        assertTrue(wire.active)
        wire.discovered.add(peer);wire.connected["endpoint"]="PRIVATE PEER";wire.userIdOf["endpoint"]=peer
        router.onConnected("endpoint",peer,"PRIVATE PEER")
        val a=launch()
        try {
            waitUi("Graph peer") {counts(a).contains("1 direct")}
            ui {
                val graph=all(a.window.decorView).first {it.tag=="network-graph"} as NetworkVisionView
                val x=graph.width/2f;val y=graph.height/2f-(minOf(graph.width,graph.height)/2f-RabtaUi.dp(a,44))
                val now=android.os.SystemClock.uptimeMillis()
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                    val event=MotionEvent.obtain(now,now,action,x,y,0);graph.dispatchTouchEvent(event);event.recycle()
                }
                assertTrue(labels(a).any {it.startsWith("Peer 1\nDirect")})
            }
            val announced=MeshSecurity(ctx,peer);val announcedKey=announced.publicText()
            router.onEnvelope("endpoint",JSONObject().put("v",2).put("k","key").put("f",peer).put("p",announcedKey)
                .put("sig",MeshCrypto.sign(announced.pair().private,peer+":"+announcedKey)).toString())
            waitUi("Pinned recipient key before queueing") {router.networkObservation().runtime.peers.any {it.keyMatched}}
            Db.get(ctx).saveMessage(message,peer,account,"PRIVATE MESSAGE BODY",System.currentTimeMillis(),true,"mesh")
            assertTrue(router.sendDirect(peer,message,"PRIVATE MESSAGE BODY"))
            waitUi("Queued activity") {labels(a).any {it.contains("Nearby message queued")}}
            val security=MeshSecurity(ctx,peer);val key=security.publicText();val sent=System.currentTimeMillis()
            val receipt=JSONObject().put("v",2).put("k","receipt").put("i",id()).put("f",peer).put("t",account)
                .put("s",sent).put("x",sent+MeshPolicy.LIFE).put("h",MeshPolicy.HOPS).put("p",key).put("a",message)
            val aad=JSONArray(listOf(receipt.getString("i"),peer,account,sent,sent+MeshPolicy.LIFE)).toString()
            val signed=JSONArray(listOf(2,"receipt",aad,key,"","","",message)).toString()
            receipt.put("sig",MeshCrypto.sign(security.pair().private,signed));router.onEnvelope("endpoint",receipt.toString())
            waitUi("Verified receipt event") {labels(a).any {it.contains("Recipient delivery confirmed")}}
            ui {
                assertTrue(labels(a).any {it.contains("not radio RTT")})
                assertFalse(labels(a).any {it.contains(message)||it.contains(peer)||it.contains(account)||it.contains("PRIVATE MESSAGE BODY")})
                assertEquals(1L,router.networkObservation().runtime.receiptsAccepted)
            }
        }finally{
            ui {a.finish()};inst.waitForIdleSync();close(router)
            Db.get(ctx).writableDatabase.delete("messages","id=?",arrayOf(message))
            clean(account);clean(peer)
        }
    }
}
