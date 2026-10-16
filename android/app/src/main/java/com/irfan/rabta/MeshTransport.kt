package com.irfan.rabta

/** Small boundary around the existing Nearby implementation; tests supply links without radios. */
interface MeshTransport {
    val connected: Map<String,String>
    val userIdOf: Map<String,String>
    val active: Boolean
    val discoveredCount: Int
    val connectingCount: Int
    fun start(name:String,userId:String)
    fun stop()
    fun retryConnections()
    fun connect(endpointId:String)
    fun radarPeers():List<RadarPeer> = emptyList()
    fun requestConnection(endpointId:String):Boolean = false
    fun approveConnection(endpointId:String):Boolean = false
    fun rejectConnection(endpointId:String) {}
    fun disconnectUser(id:String) {}
    fun sendEnvelopeToAll(json:String,exceptEndpoint:String?=null)
    fun sendEnvelopeTo(endpointId:String,json:String)
}

data class MeshPeer(val id:String,val name:String,val keyMatched:Boolean)
data class MeshRuntimeSnapshot(
    val running:Boolean=false,val active:Boolean=false,val discovered:Int=0,val connecting:Int=0,
    val peers:List<MeshPeer> = emptyList(),val relayAccepted:Long=0,val receiptsAccepted:Long=0
)
