package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator

class MeshPolicyTest {
    @Test fun rejectsExpiredAndFuturePackets() {
        val now=100000000L
        assertTrue(MeshPolicy.validTime(now-1,now+1000,now))
        assertFalse(MeshPolicy.validTime(now-1000,now,now))
        assertFalse(MeshPolicy.validTime(now+MeshPolicy.SKEW+1,now+MeshPolicy.LIFE,now))
        assertFalse(MeshPolicy.validTime(now,now+MeshPolicy.LIFE+1,now))
    }
    @Test fun hopBudgetEndsAfterEightReceivers() {
        var hops=8
        repeat(8) { assertTrue(MeshPolicy.canReceive(hops)); hops=MeshPolicy.forwardHops(hops) }
        assertEquals(0,hops); assertFalse(MeshPolicy.canReceive(hops)); assertFalse(MeshPolicy.canReceive(9))
    }
    @Test fun validatesWireIdentity() { assertTrue(MeshPolicy.validId("AB0123456789")); assertFalse(MeshPolicy.validId("../private")) }
    @Test fun hybridEncryptionAndSignaturesRoundTrip() {
        val sender=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val recipient=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val box=MeshCrypto.seal(recipient.public,"Salam 🌙","sender:receiver:id:expiry")
        assertEquals("Salam 🌙",MeshCrypto.open(recipient.private,box,"sender:receiver:id:expiry"))
        assertTrue(runCatching { MeshCrypto.open(sender.private,box,"sender:receiver:id:expiry") }.isFailure)
        assertTrue(runCatching { MeshCrypto.open(recipient.private,box,"changed recipient") }.isFailure)
        val sig=MeshCrypto.sign(sender.private,box.ciphertext)
        assertTrue(MeshCrypto.verify(sender.public,box.ciphertext,sig))
        assertFalse(MeshCrypto.verify(recipient.public,box.ciphertext,sig))
        assertFalse(MeshCrypto.verify(sender.public,box.ciphertext+"x",sig))
    }
    @Test fun expiredCanBeRetriedButNeverRegressesDelivered() {
        assertTrue(MessageDelivery.canTransition("mesh","expired"))
        assertFalse(MessageDelivery.canTransition("delivered","expired"))
        assertTrue(MessageDelivery.canTransition("expired","sending"))
    }
}
