package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test

class MessageDeliveryTest {
    @Test fun serverAcksProveRelayNotDelivery() {
        assertEquals("relayed", MessageDelivery.evidence("server:stored"))
        assertEquals("relayed", MessageDelivery.evidence("server:sent"))
        assertEquals("Written to the recipient's socket is not recipient confirmation", "relayed", MessageDelivery.evidence("server:delivered"))
        assertEquals("failed", MessageDelivery.evidence("server:error"))
        assertNull(MessageDelivery.evidence("delivered"))
        assertNull(MessageDelivery.evidence("unknown"))
    }
    @Test fun delayedAcknowledgementsNeverUndoABetterState() {
        for (late in listOf("relayed", "sent", "delivered", "failed", "queued", "sending")) assertFalse(late, MessageDelivery.canTransition("read", late))
        assertFalse(MessageDelivery.canTransition("delivered", "relayed"))
        assertFalse(MessageDelivery.canTransition("direct_delivered", "delivered"))
        assertFalse(MessageDelivery.canTransition("delivered", "direct_delivered"))
        assertFalse(MessageDelivery.canTransition("relayed", "queued"))
    }
    @Test fun timeoutDoesNotTurnAnAcknowledgedMessageIntoAFailure() {
        assertTrue(MessageDelivery.canTransition("sending", "failed"))
        assertFalse(MessageDelivery.canTransition("relayed", "failed"))
        assertFalse(MessageDelivery.canTransition("sent", "failed"))
        assertFalse(MessageDelivery.canTransition("delivered", "failed"))
    }
    @Test fun onlyWaitingMessagesCanBeSentAgain() {
        assertTrue(MessageDelivery.canTransition("failed", "queued"))
        assertTrue(MessageDelivery.canTransition("queued", "sending"))
        assertTrue(MessageDelivery.canTransition("mesh", "sending"))
        assertFalse(MessageDelivery.canTransition("relayed", "sending"))
        assertFalse(MessageDelivery.canTransition("read", "sending"))
    }
    @Test fun lateDeliveryRecoversATimeoutOrExpiry() {
        assertTrue(MessageDelivery.canTransition("failed", "delivered"))
        assertTrue(MessageDelivery.canTransition("expired", "direct_delivered"))
        assertTrue(MessageDelivery.canTransition("mesh", "read"))
        assertTrue(MessageDelivery.canTransition("relayed", "delivered"))
    }
    @Test fun legacyStoredValuesKeepWorkingAndShowAccurateLabels() {
        assertEquals("✓ Relayed", MessageDelivery.label("sent"))
        assertEquals("Queued", MessageDelivery.label("mesh"))
        assertEquals("Queued", MessageDelivery.label("mesh_wait"))
        assertTrue(MessageDelivery.pending("mesh_wait"))
        assertFalse(MessageDelivery.pending("sent"))
        assertTrue(MessageDelivery.delivered("direct_delivered"))
        assertFalse(MessageDelivery.delivered("relayed"))
    }
}
