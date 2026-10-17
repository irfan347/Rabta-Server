package com.irfan.rabta

/** Search never changes peer state, consent, or the discovery opt-in. */
object NearbySearch {
    fun filter(peers:List<RadarPeer>,query:String):List<RadarPeer> {
        val term=query.trim()
        return if(term.isEmpty())peers else peers.filter {it.name.contains(term,true)||it.id.contains(term,true)}
    }
}
