package com.irfan.rabta

import android.content.Context
import org.json.JSONObject

/** Account-scoped invitation state, independent of login/session and all chat databases. */
class InstantPairingStore(private val context:Context,val account:String,private val clock:()->Long={System.currentTimeMillis()}) {
    companion object { private val lock=Any() }
    private val prefs=context.getSharedPreferences("instant_connect_$account",0)
    private val security=MeshSecurity(context,account)
    data class ViewState(val offer:String?=null,val pendingOffer:String?=null,val pendingReply:String?=null,val confirmation:String?=null,val peerId:String="",val peerName:String="")
    private fun now():Long=synchronized(lock) {
        val saved=prefs.getLong("last_time",0);val time=maxOf(clock(),saved)
        if(time>saved)require(prefs.edit().putLong("last_time",time).commit()){"Could not save invitation clock"}
        time
    }
    private fun used()=JSONObject(prefs.getString("used","{}")?:"{}")
    private fun requireUnused(offer:InstantInvitation.Verified) { require(!used().has(offer.hash)){"This invitation has already been used"} }
    private fun identity(record:InstantInvitation.Record) {
        require(MeshPolicy.validId(account)&&record.id!=account){"You cannot pair with yourself"}
        require(!Privacy.blocked(context,record.id,account)){"This user is blocked. Review Privacy settings first"}
        val pinned=security.key(record.id)
        require(pinned==null||pinned==record.key){"Identity key differs from the saved contact. Compare security codes; the saved key was not changed"}
    }
    private fun cache(hash:String,expiry:Long,time:Long):String {
        val used=used();used.keys().asSequence().toList().filter { used.optLong(it)<=time }.forEach { used.remove(it) }
        require(used.length()<512||used.has(hash)){"Too many recent pairings. Wait for invitations to expire"}
        used.put(hash,expiry);return used.toString()
    }
    fun view():ViewState=synchronized(lock) { ViewState(prefs.getString("offer",null),prefs.getString("pending_offer",null),prefs.getString("pending_reply",null),if(paired())prefs.getString("confirmation",null)else null,prefs.getString("peer_id","")?:"",prefs.getString("peer_name","")?:"") }
    fun verify(raw:String)=InstantInvitation.verify(raw,now())
    fun inspect(raw:String):InstantInvitation.Verified=synchronized(lock) {
        val packet=verify(raw)
        when(packet.record.kind) {
            InstantInvitation.OFFER -> { identity(packet.record);requireUnused(packet) }
            InstantInvitation.REPLY -> {
                val offer=verify(prefs.getString("offer",null)?:error("No active invitation. Create a new invitation"))
                require(offer.record.id==account&&offer.record.key==security.publicText()){"Local identity key changed; create a new invitation"}
                requireUnused(offer);InstantInvitation.verifyReply(offer,packet,now());identity(packet.record)
            }
            InstantInvitation.CONFIRMATION -> {
                val offer=verify(prefs.getString("pending_offer",null)?:error("No pending pairing, or invitation already used"))
                val reply=verify(prefs.getString("pending_reply",null)?:error("No pending pairing reply"))
                require(reply.record.id==account&&reply.record.key==security.publicText()){"Local identity key changed; create a new pairing reply"}
                requireUnused(offer);InstantInvitation.verifyConfirmation(offer,reply,packet,now());identity(offer.record)
            }
        }
        packet
    }
    fun create(name:String):InstantInvitation.Verified=synchronized(lock) {
        val time=now();val offer=InstantInvitation.offer(account,name,security.pair(),time)
        require(prefs.edit().putString("offer",offer.raw).remove("pending_offer").remove("pending_reply").remove("confirmation").putLong("last_time",time).commit()){"Could not save invitation"}
        offer
    }
    fun respond(raw:String,name:String):InstantInvitation.Verified=synchronized(lock) {
        val offer=inspect(raw);require(offer.record.kind==InstantInvitation.OFFER){"Scan an invitation first"}
        val time=now()
        if(prefs.getString("pending_offer",null)==raw)prefs.getString("pending_reply",null)?.let { return@synchronized verify(it) }
        val reply=InstantInvitation.reply(offer,account,name,security.pair(),time)
        require(prefs.edit().remove("offer").remove("confirmation").putString("pending_offer",raw).putString("pending_reply",reply.raw).putLong("last_time",time).commit()){"Could not save pairing reply"}
        reply
    }
    fun approve(raw:String):InstantInvitation.Verified=synchronized(lock) {
        val reply=inspect(raw);require(reply.record.kind==InstantInvitation.REPLY){"Scan a pairing reply first"}
        val time=now();val offer=verify(prefs.getString("offer",null)?:error("Invitation is no longer active"))
        val confirmation=InstantInvitation.confirmation(offer,reply,security.pair(),time)
        // Consume durably before pinning. A crash/save failure cannot make an invitation reusable.
        require(prefs.edit().remove("offer").putString("used",cache(offer.hash,offer.record.expires,time)).putString("confirmation",confirmation.raw)
            .putString("peer_id",reply.record.id).putString("peer_name",reply.record.name).putBoolean("paired",false).putLong("last_time",time).commit()){"Could not consume invitation"}
        require(security.pin(reply.record.id,reply.record.key)){"Pairing key could not be saved. Create a new invitation; the previous invitation remains consumed"}
        require(prefs.edit().putBoolean("paired",true).commit()){"Could not confirm saved pairing"}
        confirmation
    }
    fun finish(raw:String):String=synchronized(lock) {
        val confirmation=inspect(raw);require(confirmation.record.kind==InstantInvitation.CONFIRMATION){"Scan the issuer's confirmation"}
        val time=now();val offer=verify(prefs.getString("pending_offer",null)?:error("No pending pairing"))
        require(prefs.edit().remove("pending_offer").remove("pending_reply").remove("confirmation").putString("used",cache(offer.hash,offer.record.expires,time))
            .putString("peer_id",offer.record.id).putString("peer_name",offer.record.name).putBoolean("paired",false).putLong("last_time",time).commit()){"Could not consume pairing confirmation"}
        require(security.pin(offer.record.id,offer.record.key)){"Pairing key could not be saved. Ask for a new invitation"}
        require(prefs.edit().putBoolean("paired",true).commit()){"Could not confirm saved pairing"}
        offer.record.id
    }
    fun paired()=prefs.getBoolean("paired",false)
    fun cancel()=synchronized(lock) { require(prefs.edit().remove("offer").remove("pending_offer").remove("pending_reply").remove("confirmation").commit()){"Could not cancel invitation"} }
}
