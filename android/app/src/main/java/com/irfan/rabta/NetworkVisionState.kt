package com.irfan.rabta

/** Account-scoped, local observations. No remote topology can be derived from packet hop counts. */
data class NetworkObservation(
    val owner:String="", val runtime:MeshRuntimeSnapshot=MeshRuntimeSnapshot(),
    val discoveredIds:Set<String> = emptySet(), val stages:Map<String,RadarStage> = emptyMap(),
    val events:List<NetworkEvent> = emptyList(), val receiptMillis:Map<String,Long> = emptyMap()
)
enum class NetworkEventKind { QUEUED, RECEIVED, DELIVERED, RELAY_ACCEPTED }
data class NetworkEvent(val sequence:Long,val kind:NetworkEventKind,val peerId:String?,val time:Long)
data class VisionPeer(val id:String,val label:String,val connected:Boolean,val keyMatched:Boolean,val discovered:Boolean,
    val stage:RadarStage?,val receiptMillis:Long?)
data class VisionEvent(val sequence:Long,val kind:NetworkEventKind,val peerLabel:String?,val time:Long)
data class VisionState(val enabled:Boolean=false,val active:Boolean=false,val peers:List<VisionPeer> = emptyList(),
    val events:List<VisionEvent> = emptyList(),val relays:Long=0,val receipts:Long=0) {
    val directCount get()=peers.count { it.connected }
    val discoveredCount get()=peers.count { it.discovered }
}

/** Private screen-local aliases: no display names, account IDs, endpoint IDs or message IDs in views. */
class NetworkVisionState {
    private var account=""
    private var nextAlias=1
    private val aliases=linkedMapOf<String,String>()
    fun clear() { account="";nextAlias=1;aliases.clear() }
    fun render(owner:String,enabled:Boolean,observation:NetworkObservation,blocked:Set<String> = emptySet()):VisionState {
        if(account!=owner){clear();account=owner}
        if(!enabled||!MeshPolicy.validId(owner)||observation.owner!=owner||!observation.runtime.running) {
            aliases.clear();nextAlias=1
            return VisionState(enabled=enabled)
        }
        val connected=observation.runtime.peers.filter { MeshPolicy.validId(it.id) }.associateBy { it.id }
        val ids=(observation.discoveredIds+connected.keys).filter { MeshPolicy.validId(it)&&it!=owner&&it !in blocked }.toSet()
        // Remove absent aliases to keep memory bounded. New appearances get new screen-local labels.
        aliases.keys.retainAll(ids)
        ids.sorted().forEach { id -> if(!aliases.containsKey(id))aliases[id]="Peer ${nextAlias++}" }
        val peers=ids.map { id -> VisionPeer(id,aliases.getValue(id),id in connected,connected[id]?.keyMatched==true,
            id in observation.discoveredIds,observation.stages[id],observation.receiptMillis[id]) }
            .sortedWith(compareByDescending<VisionPeer> { it.connected }.thenBy { it.label.substringAfter(' ').toIntOrNull()?:0 })
        val events=observation.events.filter { it.peerId !in blocked && it.peerId!=owner }.takeLast(40).map {
            VisionEvent(it.sequence,it.kind,aliases[it.peerId],it.time)
        }
        return VisionState(true,observation.runtime.active,peers,events,observation.runtime.relayAccepted,observation.runtime.receiptsAccepted)
    }
}

/** Bounded RAM telemetry. Recording never contains bodies, names, keys, tokens or hop-derived links. */
class NetworkVisionTrace {
    private var sequence=0L
    private val events=ArrayDeque<NetworkEvent>()
    private val seen=linkedSetOf<String>()
    private val queued=linkedMapOf<String,Pair<String,Long>>()
    private val receiptTimes=linkedMapOf<String,Long>()
    @Synchronized private fun record(kind:NetworkEventKind,id:String,peer:String?,wall:Long) {
        if(!seen.add("$kind:$id"))return
        if(seen.size>512)seen.remove(seen.first())
        events.addLast(NetworkEvent(++sequence,kind,peer,wall))
        while(events.size>40)events.removeFirst()
    }
    @Synchronized fun queued(id:String,peer:String,elapsed:Long,wall:Long) {
        if(!queued.containsKey(id))queued[id]=peer to elapsed
        while(queued.size>256)queued.remove(queued.keys.first())
        record(NetworkEventKind.QUEUED,id,peer,wall)
    }
    @Synchronized fun received(id:String,peer:String,wall:Long)=record(NetworkEventKind.RECEIVED,id,peer,wall)
    @Synchronized fun relay(id:String,source:String?,wall:Long)=record(NetworkEventKind.RELAY_ACCEPTED,id,source,wall)
    @Synchronized fun delivered(id:String,recipient:String,elapsed:Long,wall:Long) {
        val sent=queued[id]
        if(sent!=null&&sent.first==recipient) {
            queued.remove(id)
            if(elapsed>=sent.second)receiptTimes[recipient]=elapsed-sent.second
            while(receiptTimes.size>200)receiptTimes.remove(receiptTimes.keys.first())
        }
        record(NetworkEventKind.DELIVERED,id,recipient,wall)
    }
    @Synchronized fun events()=events.toList()
    @Synchronized fun receiptTimes()=receiptTimes.toMap()
    @Synchronized fun clear(){events.clear();seen.clear();queued.clear();receiptTimes.clear();sequence=0}
}
