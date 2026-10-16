package com.irfan.rabta

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Public, signed, short-lived pairing records. Never serializes a private key or login token. */
object InstantInvitation {
    const val PREFIX="rabta:ic:1:"
    const val LIFE=180000L
    const val SKEW=30000L
    const val MAX_TEXT=4096
    const val OFFER=1
    const val REPLY=2
    const val CONFIRMATION=3
    data class Record(val kind:Int,val id:String,val name:String,val key:String,val nonce:String,
        val offerHash:String,val replyHash:String,val issued:Long,val expires:Long,val signature:String="")
    data class Verified(val record:Record,val raw:String) { val hash get()=digest(raw) }
    private val encoder=Base64.getUrlEncoder().withoutPadding()
    private val decoder=Base64.getUrlDecoder()
    private fun bytes(r:Record)=ByteArrayOutputStream().also { out -> DataOutputStream(out).use { d ->
        d.writeUTF("RABTA-INSTANT-CONNECT");d.writeByte(1);d.writeByte(r.kind)
        listOf(r.id,r.name,r.key,r.nonce,r.offerHash,r.replyHash).forEach { d.writeUTF(it) }
        d.writeLong(r.issued);d.writeLong(r.expires)
    } }.toByteArray()
    private fun signing(payload:ByteArray)="RABTA-INSTANT-CONNECT-V1:"+encoder.encodeToString(payload)
    private fun nonce()=encoder.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun digest(raw:String)=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
    private fun sign(r:Record,pair:KeyPair,now:Long):Verified {
        require(MeshCrypto.b64(pair.public.encoded)==r.key){"Identity key does not match"}
        val payload=bytes(r)
        val signature=MeshCrypto.bytes(MeshCrypto.sign(pair.private,signing(payload)))
        return verify(PREFIX+encoder.encodeToString(payload)+"."+encoder.encodeToString(signature),now)
    }
    fun offer(id:String,name:String,pair:KeyPair,now:Long)=sign(Record(OFFER,id,cleanName(name),MeshCrypto.b64(pair.public.encoded),nonce(),"","",now,now+LIFE),pair,now)
    fun reply(offer:Verified,id:String,name:String,pair:KeyPair,now:Long):Verified {
        val source=verify(offer.raw,now);require(source.record.kind==OFFER){"Scan an invitation first"}
        require(id!=source.record.id){"You cannot pair with yourself"}
        return sign(Record(REPLY,id,cleanName(name),MeshCrypto.b64(pair.public.encoded),nonce(),source.hash,"",now,source.record.expires),pair,now)
    }
    fun confirmation(offer:Verified,reply:Verified,pair:KeyPair,now:Long):Verified {
        verifyReply(offer,reply,now)
        val r=verify(offer.raw,now).record
        return sign(Record(CONFIRMATION,r.id,r.name,r.key,r.nonce,offer.hash,reply.hash,now,r.expires),pair,now)
    }
    fun verifyReply(offer:Verified,reply:Verified,now:Long) {
        val a=verify(offer.raw,now);val b=verify(reply.raw,now)
        require(a.record.kind==OFFER&&b.record.kind==REPLY&&a.record.id!=b.record.id&&b.record.offerHash==a.hash&&b.record.expires==a.record.expires){"Reply does not belong to this invitation"}
    }
    fun verifyConfirmation(offer:Verified,reply:Verified,confirmation:Verified,now:Long) {
        verifyReply(offer,reply,now);val c=verify(confirmation.raw,now).record;val a=verify(offer.raw,now).record
        require(c.kind==CONFIRMATION&&c.id==a.id&&c.key==a.key&&c.nonce==a.nonce&&c.offerHash==offer.hash&&c.replyHash==reply.hash&&c.expires==a.expires){"Confirmation does not match this pairing"}
    }
    fun pairingCode(offer:Verified,reply:Verified)=digest("RABTA-PAIRING-CODE-V1:${offer.hash}:${reply.hash}").take(24).chunked(4).joinToString(" ")
    private fun cleanName(value:String)=value.filterNot { it.isISOControl() }.take(60).ifBlank { "Rabta user" }
    fun verify(raw:String,now:Long):Verified {
        require(raw.length in 100..MAX_TEXT&&raw.startsWith(PREFIX)){"Not an Instant Connect code"}
        val parts=raw.removePrefix(PREFIX).split('.')
        require(parts.size==2&&parts.all { it.matches(Regex("[A-Za-z0-9_-]+")) }){"Invalid invitation encoding"}
        val payload=decoder.decode(parts[0]);val signature=decoder.decode(parts[1])
        require(payload.size<=2400&&signature.size==256&&encoder.encodeToString(payload)==parts[0]&&encoder.encodeToString(signature)==parts[1]){"Invalid invitation size or encoding"}
        val d=DataInputStream(ByteArrayInputStream(payload))
        require(d.readUTF()=="RABTA-INSTANT-CONNECT"&&d.readUnsignedByte()==1){"Unsupported invitation version"}
        val r=Record(d.readUnsignedByte(),d.readUTF(),d.readUTF(),d.readUTF(),d.readUTF(),d.readUTF(),d.readUTF(),d.readLong(),d.readLong(),MeshCrypto.b64(signature))
        require(d.available()==0&&payload.contentEquals(bytes(r))){"Invalid invitation fields"}
        require(r.kind in OFFER..CONFIRMATION&&MeshPolicy.validId(r.id)&&r.name.length in 1..60&&!r.name.any { it.isISOControl() }&&r.nonce.matches(Regex("[A-Za-z0-9_-]{43}"))){"Invalid invitation identity"}
        require(r.issued>0&&r.issued<=now+SKEW&&r.expires>now&&r.expires>r.issued&&r.expires-r.issued<=LIFE){"Invitation expired or phone time is incorrect"}
        val hash=Regex("[0-9A-F]{64}")
        require(when(r.kind) { OFFER->r.offerHash.isEmpty()&&r.replyHash.isEmpty();REPLY->hash.matches(r.offerHash)&&r.replyHash.isEmpty();else->hash.matches(r.offerHash)&&hash.matches(r.replyHash) }){"Invalid pairing binding"}
        require(MeshCrypto.verify(MeshCrypto.publicKey(r.key),signing(payload),r.signature)){"Invitation signature is invalid"}
        return Verified(r,raw)
    }
}
