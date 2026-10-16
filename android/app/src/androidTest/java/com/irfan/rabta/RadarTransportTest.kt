package com.irfan.rabta

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Tasks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** Real Mesh callbacks with a simulated Nearby SDK boundary. No physical radios exercised. */
@RunWith(AndroidJUnit4::class)
class RadarTransportTest {
    private val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    private fun id()=java.util.UUID.randomUUID().toString().replace("-","").take(12).uppercase()
    private val remote="ABCDEF123456"
    private class Events:Mesh.Listener {
        var found=0;var envelopes=0
        override fun onFound(endpointId:String,name:String,userId:String) { found++ }
        override fun onLost(endpointId:String) {}
        override fun onConnecting(endpointId:String,name:String) {}
        override fun onConnected(endpointId:String,userId:String,name:String) {}
        override fun onDisconnected(endpointId:String) {}
        override fun onEnvelope(endpointId:String,json:String) { envelopes++ }
        override fun onFileStart(endpointId:String,payloadId:Long,name:String,size:Long) {}
        override fun onFileDone(endpointId:String,payloadId:Long) {}
        override fun onStream(endpointId:String,payload:Payload) {}
        override fun onAudio(endpointId:String,frame:ByteArray) {}
        override fun onStatus(msg:String) {}
    }
    private inner class Harness {
        val account=id();val events=Events();val calls=mutableListOf<String>();var payload:PayloadCallback?=null
        val client=Proxy.newProxyInstance(ConnectionsClient::class.java.classLoader,arrayOf(ConnectionsClient::class.java)) { _,method,args ->
            calls.add(method.name)
            if(method.name=="acceptConnection")payload=args!![1] as PayloadCallback
            if(method.returnType==java.lang.Void.TYPE)null else Tasks.forResult<Void>(null)
        } as ConnectionsClient
        val mesh=Mesh(ctx,events) { client }
        val lifecycle=Mesh::class.java.getDeclaredMethod("lifecycleFor",Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(mesh,1) as ConnectionLifecycleCallback
        fun ready() {
            ctx.getSharedPreferences("radar_$account",0).edit().putBoolean("discoverable",true).commit()
            for((name,value) in mapOf("epoch" to 1,"active" to true,"selfId" to account,"lifecycle" to lifecycle))Mesh::class.java.getDeclaredField(name).apply { isAccessible=true;set(mesh,value) }
        }
        fun found() {
            val discovery=Mesh::class.java.getDeclaredMethod("discoveryFor",Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(mesh,1) as EndpointDiscoveryCallback
            discovery.onEndpointFound("endpoint",DiscoveredEndpointInfo("Remote\u0001$remote",Mesh.SERVICE_ID))
        }
        fun incoming() { lifecycle.onConnectionInitiated("endpoint",connectionInfo("Remote\u0001$remote")) }
        fun success() { lifecycle.onConnectionResult("endpoint",ConnectionResolution(Status.RESULT_SUCCESS)) }
        fun close() {mesh.stop();ctx.getSharedPreferences("radar_$account",0).edit().clear().commit();ctx.getSharedPreferences("privacy_$account",0).edit().clear().commit()}
    }
    // SDK versions add optional fields while keeping the same callback contract.
    private fun connectionInfo(name:String):ConnectionInfo {
        val constructor=ConnectionInfo::class.java.declaredConstructors.first { it.parameterTypes.count { t -> t==String::class.java }==2 && it.parameterTypes.any { t -> t==Boolean::class.javaPrimitiveType } }
        var strings=0
        val args=constructor.parameterTypes.map { type -> when(type) {
            String::class.java -> if(strings++==0)name else "1234"
            Boolean::class.javaPrimitiveType -> true
            ByteArray::class.java -> byteArrayOf(1,2,3,4)
            else -> null
        } }.toTypedArray()
        constructor.isAccessible=true;return constructor.newInstance(*args) as ConnectionInfo
    }
    @Test fun optedOutAccountDoesNotTouchNearbyClient() {
        val h=Harness()
        try {
            assertFalse(RadarPrefs.enabled(ctx,h.account));h.mesh.start("Test",h.account)
            assertFalse(h.mesh.active);assertTrue(h.calls.isEmpty());assertFalse(h.mesh.requestConnection("endpoint"))
        }finally{h.close()}
    }
    @Test fun discoveryAndIncomingRequestRequireExplicitApprovalBeforePayloads() {
        val h=Harness()
        try {
            h.ready();h.found();assertEquals(1,h.events.found);assertFalse(h.calls.contains("requestConnection"))
            h.incoming();assertEquals(RadarStage.INCOMING,h.mesh.radarPeers().single().stage)
            assertFalse(h.calls.contains("acceptConnection"));assertTrue(h.mesh.connected.isEmpty())
            assertTrue(h.mesh.approveConnection("endpoint"));assertEquals(1,h.calls.count { it=="acceptConnection" })
            h.payload!!.onPayloadReceived("endpoint",Payload.fromBytes("M{}".toByteArray()));assertEquals(0,h.events.envelopes)
            h.success();assertEquals(remote,h.mesh.userIdOf["endpoint"])
            h.payload!!.onPayloadReceived("endpoint",Payload.fromBytes("M{}".toByteArray()));assertEquals(1,h.events.envelopes)
        }finally{h.close()}
    }
    @Test fun outgoingRequestAcceptsLocallyButRejectAndBlockPreventConnection() {
        val h=Harness()
        try {
            h.ready();h.found();assertTrue(h.mesh.requestConnection("endpoint"));assertFalse(h.mesh.requestConnection("endpoint"))
            h.incoming();assertEquals(1,h.calls.count { it=="acceptConnection" })
            h.mesh.rejectConnection("endpoint");h.success();assertTrue(h.mesh.connected.isEmpty())
            ctx.getSharedPreferences("privacy_${h.account}",0).edit().putStringSet("blocked",setOf(remote)).commit()
            h.mesh.disconnectUser(remote);h.incoming();assertFalse(h.mesh.approveConnection("endpoint"));assertTrue(h.mesh.radarPeers().isEmpty())
            assertEquals(1,h.calls.count { it=="requestConnection" })
        }finally{h.close()}
    }
    @Test fun unsolicitedSuccessAndLateApprovalCannotConnect() {
        val h=Harness()
        try {
            h.ready();h.found();h.incoming();h.success();assertTrue(h.mesh.connected.isEmpty())
            h.mesh.stop();h.lifecycle.onConnectionResult("endpoint",ConnectionResolution(Status.RESULT_SUCCESS))
            assertTrue(h.mesh.connected.isEmpty());assertFalse(h.mesh.approveConnection("endpoint"))
        }finally{h.close()}
    }
}
