package com.irfan.rabta

enum class RadarStage { FOUND, OUTGOING, INCOMING, APPROVED, CONNECTED, FAILED, REJECTED }
data class RadarPeer(val endpoint:String,val id:String,val name:String,val stage:RadarStage=RadarStage.FOUND,val code:String="",val deadline:Long=0,val retryAt:Long=0)

/** Consent is per connection, never inferred from an advertised ID or a saved chat. No radio calls. */
class RadarGate(private val blocked:(String)->Boolean) {
    enum class Decision { ASK, ACCEPT, REJECT, IGNORE }
    companion object { const val REQUEST_LIFE=60000L;const val COOLDOWN=60000L }
    private val peers=linkedMapOf<String,RadarPeer>()
    private val cooldowns=linkedMapOf<String,Long>()
    private val inbound=java.util.ArrayDeque<Long>()
    private fun pending(p:RadarPeer)=p.stage in setOf(RadarStage.OUTGOING,RadarStage.INCOMING,RadarStage.APPROVED)
    private fun cool(id:String,until:Long) {
        cooldowns[id]=maxOf(cooldowns[id]?:0,until)
        // Refuse new requests at capacity rather than evicting unexpired anti-spam history.
    }
    @Synchronized fun found(endpoint:String,id:String,name:String):Boolean {
        if(!MeshPolicy.validId(id)||blocked(id))return false
        val old=peers[endpoint]
        if(old!=null&&old.id!=id&&old.stage!=RadarStage.FOUND)return false
        if(old==null&&peers.size>=200)return false
        peers[endpoint]=if(old?.id==id)old.copy(name=name.take(80)) else RadarPeer(endpoint,id,name.take(80))
        return true
    }
    @Synchronized fun lost(endpoint:String) { if(peers[endpoint]?.stage==RadarStage.FOUND)peers.remove(endpoint) }
    private fun room(now:Long):Boolean {
        cooldowns.entries.removeAll { it.value<=now }
        return cooldowns.size<500 && peers.values.count { pending(it) }<8
    }
    @Synchronized fun request(endpoint:String,now:Long):Boolean {
        val p=peers[endpoint]?:return false
        if(blocked(p.id)||pending(p)||p.stage==RadarStage.CONNECTED||now<(cooldowns[p.id]?:0)||!room(now))return false
        if(peers.values.any { it.endpoint!=endpoint&&it.id==p.id&&(pending(it)||it.stage==RadarStage.CONNECTED) })return false
        cool(p.id,now+COOLDOWN)
        peers[endpoint]=p.copy(stage=RadarStage.OUTGOING,code="",deadline=now+REQUEST_LIFE,retryAt=now+COOLDOWN)
        return true
    }
    @Synchronized fun initiated(endpoint:String,id:String,name:String,code:String,now:Long):Decision {
        if(!MeshPolicy.validId(id)||blocked(id))return Decision.REJECT
        val old=peers[endpoint]
        if(old!=null&&old.id!=id)return Decision.REJECT
        if(old?.stage==RadarStage.OUTGOING) {
            if(old.id!=id||old.deadline<=now)return Decision.REJECT
            peers[endpoint]=old.copy(stage=RadarStage.APPROVED,code=code.take(32))
            return Decision.ACCEPT // This side explicitly pressed Connect.
        }
        if(old!=null&&(pending(old)||old.stage==RadarStage.CONNECTED))return Decision.IGNORE
        while(inbound.isNotEmpty()&&inbound.peekFirst()<=now-60000)inbound.removeFirst()
        if(now<(cooldowns[id]?:0)||inbound.size>=5||!room(now))return Decision.REJECT
        if(peers.values.any { it.id==id&&(pending(it)||it.stage==RadarStage.CONNECTED) })return Decision.REJECT
        if(!found(endpoint,id,name))return Decision.REJECT
        inbound.addLast(now);cool(id,now+COOLDOWN)
        peers[endpoint]=RadarPeer(endpoint,id,name.take(80),RadarStage.INCOMING,code.take(32),now+REQUEST_LIFE,now+COOLDOWN)
        return Decision.ASK
    }
    @Synchronized fun approve(endpoint:String,now:Long):Boolean {
        val p=peers[endpoint]?:return false
        if(p.stage!=RadarStage.INCOMING||p.deadline<=now||blocked(p.id))return false
        peers[endpoint]=p.copy(stage=RadarStage.APPROVED);return true
    }
    @Synchronized fun connected(endpoint:String,now:Long):RadarPeer? {
        val p=peers[endpoint]?:return null
        if(p.stage!=RadarStage.APPROVED||p.deadline<=now||blocked(p.id))return null
        return p.copy(stage=RadarStage.CONNECTED,deadline=0).also { peers[endpoint]=it }
    }
    @Synchronized fun reject(endpoint:String,now:Long) {
        val p=peers[endpoint]?:return
        cool(p.id,now+120000)
        peers[endpoint]=p.copy(stage=RadarStage.REJECTED,code="",deadline=0,retryAt=now+120000)
    }
    @Synchronized fun failed(endpoint:String) {
        peers[endpoint]?.let { peers[endpoint]=it.copy(stage=RadarStage.FAILED,code="",deadline=0) }
    }
    @Synchronized fun expired(now:Long):List<String> = peers.values.filter { pending(it)&&it.deadline<=now }.map { it.endpoint }.also { ids -> ids.forEach { failed(it) } }
    @Synchronized fun removeUser(id:String):List<String> = peers.values.filter { it.id==id }.map { it.endpoint }.also { ids -> ids.forEach { peers.remove(it) } }
    @Synchronized fun snapshot()=peers.values.filterNot { blocked(it.id) }.sortedBy {
        when(it.stage) { RadarStage.INCOMING->0;RadarStage.OUTGOING->1;RadarStage.APPROVED->2;RadarStage.CONNECTED->3;else->4 }
    }.distinctBy { it.id }.toList()
    @Synchronized fun clear() { peers.clear() } // Keep rate limits across transport restarts.
}
