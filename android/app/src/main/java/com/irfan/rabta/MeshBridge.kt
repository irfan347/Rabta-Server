package com.irfan.rabta

/** Lets screens use the mesh Router that lives inside MessageService. */
object MeshBridge {
    @Volatile private var router: Router? = null

    fun attach(r: Router) { router = r }
    fun detach() { router = null }

    val running get() = router?.running == true
    val peersConnected get() = router?.connected?.size ?: 0

    /** Send a 1-to-1 message over the offline mesh. False if mesh is off. */
    fun sendDirect(toId: String, msgId: String, body: String): Boolean =
        router?.sendDirect(toId, msgId, body) ?: false
}
