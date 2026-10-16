package com.irfan.rabta

/** Lets screens use the mesh Router that lives inside MessageService. */
object MeshBridge {
    @Volatile var status="Nearby not started"
    @Volatile private var router: Router? = null

    fun attach(r: Router) { router = r }
    fun detach() { router = null }

    val running get() = router?.running == true
    fun snapshot()=router?.snapshot()?:MeshRuntimeSnapshot()
    val peersConnected get() = router?.connected?.size ?: 0

    fun peerNames():List<String> = router?.connected?.values?.toList() ?: emptyList()

    fun hasKey(peer:String)=router?.hasKey(peer)==true

    /** Send a 1-to-1 message over the offline mesh. False if mesh is off. */
    fun sendDirect(toId: String, msgId: String, body: String, type: String = "text"): Boolean =
        router?.sendDirect(toId, msgId, body, type) ?: false
}
