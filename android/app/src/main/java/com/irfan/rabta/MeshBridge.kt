package com.irfan.rabta

/** Lets screens use the mesh Router that lives inside MessageService. */
object MeshBridge {
    @Volatile var status="Nearby not started"
    @Volatile private var router: Router? = null

    fun attach(r: Router) { router = r }
    fun detach() { router = null }

    val running get() = router?.running == true
    fun snapshot()=router?.snapshot()?:MeshRuntimeSnapshot()
    fun networkObservation()=router?.networkObservation()?:NetworkObservation()
    fun radarPeers()=router?.radarPeers()?:emptyList()
    fun requestConnection(endpoint:String)=router?.requestConnection(endpoint)==true
    fun approveConnection(endpoint:String)=router?.approveConnection(endpoint)==true
    fun rejectConnection(endpoint:String) { router?.rejectConnection(endpoint) }
    fun blockUser(ctx:android.content.Context,id:String) { Privacy.setBlocked(ctx,id,true);router?.disconnectUser(id) }
    fun setDiscoverable(ctx:android.content.Context,value:Boolean) {
        RadarPrefs.setEnabled(ctx,value)
        if(!value){router?.pauseTransport();status="Radar is off — you are not discoverable";ctx.getSystemService(android.app.NotificationManager::class.java).cancel(MessageService.RADAR_NOTIF)}
        MessageService.restartNearby(ctx)
    }
    val peersConnected get() = router?.connected?.size ?: 0

    fun peerNames():List<String> = router?.connected?.values?.toList() ?: emptyList()

    fun hasKey(peer:String)=router?.hasKey(peer)==true
    /** [peer] is connected right now over a key-verified direct link. */
    fun direct(peer:String)=router?.direct(peer)==true
    /** The Nearby store still holds [msgId] for carrying (sealed or waiting for the contact's key). */
    fun carrying(msgId:String)=router?.carrying(msgId)==true

    /** Send a 1-to-1 message over the offline mesh. False if mesh is off. */
    fun sendDirect(toId: String, msgId: String, body: String, type: String = "text"): Boolean =
        router?.sendDirect(toId, msgId, body, type) ?: false
}

