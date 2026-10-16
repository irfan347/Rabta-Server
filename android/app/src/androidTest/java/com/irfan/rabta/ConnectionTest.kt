package com.irfan.rabta
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
@RunWith(AndroidJUnit4::class)
class ConnectionTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private fun until(test:()->Boolean){val limit=System.currentTimeMillis()+10000;while(!test()&&System.currentTimeMillis()<limit)Thread.sleep(30);assertTrue("Connection state did not recover: ${WsClient.status}",test())}
    private fun configure(server:MockWebServer){inst.runOnMainSync {MessageService.stop(ctx);Session.init(ctx);WsClient.disconnect();Session.id="AAAABBBBCCCC";Session.token="expired-test-token";Session.serverUrl=server.url("/").toString().trimEnd('/');WsClient.connect(Session)}}
    @Test fun rejectedSessionDoesNotClaimOnlineAndCanReauthenticate(){
        val server=MockWebServer();val delivered=CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onOpen(ws:WebSocket,r:Response){ws.close(1008,"auth")}}))
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onOpen(ws:WebSocket,r:Response){ws.send("{\"type\":\"ready\",\"id\":\"AAAABBBBCCCC\"}")};override fun onMessage(ws:WebSocket,text:String){delivered.countDown()}}))
        server.start();try{
            configure(server);until {WsClient.authRequired};assertFalse(WsClient.connected);assertFalse(WsClient.send("BBBBCCCCDDDD","000000000001","hello"))
            inst.runOnMainSync {Session.token="fresh-test-token";WsClient.connect(Session)};until {WsClient.connected};assertFalse(WsClient.authRequired)
            assertTrue(WsClient.send("BBBBCCCCDDDD","000000000002","hello"));assertTrue(delivered.await(5,TimeUnit.SECONDS))
            Thread.sleep(400);assertTrue("Stale close callback must not disconnect the new session",WsClient.connected)
        }finally{inst.runOnMainSync {WsClient.disconnect()};server.shutdown()}
    }
    @Test fun serverCloseReconnectsAndUpgradeAloneIsNotOnline(){
        val server=MockWebServer();val opened=CountDownLatch(1);var socket:WebSocket?=null
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onOpen(ws:WebSocket,r:Response){socket=ws;opened.countDown()}}))
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onOpen(ws:WebSocket,r:Response){ws.send("{\"type\":\"ready\",\"id\":\"AAAABBBBCCCC\"}")}}))
        server.start();try{configure(server);assertTrue(opened.await(5,TimeUnit.SECONDS));Thread.sleep(200);assertFalse("HTTP upgrade is not authentication",WsClient.connected);socket!!.close(1000,"restart");until {WsClient.connected};assertFalse(WsClient.authRequired)}finally{inst.runOnMainSync {WsClient.disconnect()};server.shutdown()}
    }
    @Test fun missingNearbyPermissionIsActionable(){val denied=object:android.content.ContextWrapper(ctx){override fun checkSelfPermission(permission:String)=android.content.pm.PackageManager.PERMISSION_DENIED};assertTrue(NearbySetup.problem(denied)!!.contains("permissions"))}
    @Test fun deviceKeystoreDecryptsMeshEnvelope(){Session.init(ctx);val sec=MeshSecurity(ctx,"ABCDEF123456");val pair=sec.pair();val box=MeshCrypto.seal(pair.public,"offline text","routing");assertEquals("offline text",MeshCrypto.open(pair.private,box,"routing"))}
}
