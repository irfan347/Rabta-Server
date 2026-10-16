package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator

class InstantInvitationTest {
    companion object {
        private val a by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
        private val b by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
        private val c by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
        private const val time=1700000000000L
    }
    private fun rejected(action:()->Unit) { assertTrue("Invalid pairing must fail",runCatching(action).isFailure) }
    private fun offer()=InstantInvitation.offer("AAAABBBBCCCC","Irfan",a,time)
    @Test fun signedThreeStepPairingBindsBothIdentitiesAndCode() {
        val offer=offer();val reply=InstantInvitation.reply(offer,"DDDDEEEEFFFF","علی",b,time+1000)
        val complete=InstantInvitation.confirmation(offer,reply,a,time+2000)
        InstantInvitation.verifyReply(offer,reply,time+2000)
        InstantInvitation.verifyConfirmation(offer,reply,complete,time+3000)
        assertEquals("علی",InstantInvitation.verify(reply.raw,time+3000).record.name)
        assertEquals(29,InstantInvitation.pairingCode(offer,reply).length)
        assertEquals(InstantInvitation.pairingCode(offer,reply),InstantInvitation.pairingCode(InstantInvitation.verify(offer.raw,time),InstantInvitation.verify(reply.raw,time+1000)))
        assertEquals(MeshCrypto.b64(a.public.encoded),offer.record.key)
        assertFalse(offer.raw.contains(MeshCrypto.b64(a.private.encoded)))
    }
    @Test fun expirationAndFutureClockAreStrict() {
        val offer=offer()
        InstantInvitation.verify(offer.raw,time+InstantInvitation.LIFE-1)
        rejected { InstantInvitation.verify(offer.raw,time+InstantInvitation.LIFE) }
        rejected { InstantInvitation.verify(offer.raw,time-InstantInvitation.SKEW-1) }
        rejected { InstantInvitation.reply(offer,"DDDDEEEEFFFF","User",b,time+InstantInvitation.LIFE) }
    }
    @Test fun tamperingWrongSignatureAndNoncanonicalEncodingAreRejected() {
        val raw=offer().raw
        val position=raw.indexOf('.')+5
        val altered=raw.substring(0,position)+(if(raw[position]=='A')"B" else "A")+raw.substring(position+1)
        rejected { InstantInvitation.verify(altered,time) }
        rejected { InstantInvitation.verify(raw+"=",time) }
        rejected { InstantInvitation.verify(raw+".extra",time) }
        rejected { InstantInvitation.verify("x".repeat(InstantInvitation.MAX_TEXT+1),time) }
    }
    @Test fun replyAndConfirmationCannotMoveToAnotherInvitationOrRecipient() {
        val first=offer();val second=offer()
        val reply=InstantInvitation.reply(first,"DDDDEEEEFFFF","B",b,time+1)
        val other=InstantInvitation.reply(first,"000000000001","C",c,time+1)
        val completed=InstantInvitation.confirmation(first,reply,a,time+2)
        rejected { InstantInvitation.verifyReply(second,reply,time+3) }
        rejected { InstantInvitation.verifyConfirmation(first,other,completed,time+3) }
        rejected { InstantInvitation.confirmation(first,reply,b,time+3) }
        assertNotEquals(InstantInvitation.pairingCode(first,reply),InstantInvitation.pairingCode(first,other))
    }
    @Test fun nonceIsFreshAndSelfPairingIsRejected() {
        val first=offer();val second=offer()
        assertNotEquals(first.record.nonce,second.record.nonce);assertNotEquals(first.hash,second.hash)
        rejected { InstantInvitation.reply(first,first.record.id,"Self",a,time+1) }
        rejected { InstantInvitation.offer("../bad","User",a,time) }
    }
}
