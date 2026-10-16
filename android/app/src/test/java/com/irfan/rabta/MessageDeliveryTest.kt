package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test

class MessageDeliveryTest {
    @Test fun offlineServerStorageIsAcceptedButNotDelivered() {
        assertEquals("sent",MessageDelivery.normalizeReceipt("stored"))
        assertEquals("failed",MessageDelivery.normalizeReceipt("error"))
        assertNull(MessageDelivery.normalizeReceipt("unknown"))
    }
    @Test fun delayedAcknowledgementsNeverUndoAReadReceipt() {
        assertFalse(MessageDelivery.canTransition("read", "sent"))
        assertFalse(MessageDelivery.canTransition("read", "delivered"))
        assertFalse(MessageDelivery.canTransition("read", "failed"))
    }
    @Test fun timeoutDoesNotTurnAnAcknowledgedMessageIntoAFailure() {
        assertTrue(MessageDelivery.canTransition("sending", "failed"))
        assertFalse(MessageDelivery.canTransition("sent", "failed"))
        assertFalse(MessageDelivery.canTransition("delivered", "failed"))
    }
    @Test fun onlyFailedMessagesCanBeRetried() {
        assertTrue(MessageDelivery.canTransition("failed", "sending"))
        assertFalse(MessageDelivery.canTransition("sent", "sending"))
        assertFalse(MessageDelivery.canTransition("read", "sending"))
    }
    @Test fun lateDeliveryRecoversATimeout() {
        assertTrue(MessageDelivery.canTransition("failed", "delivered"))
        assertTrue(MessageDelivery.canTransition("mesh", "read"))
    }
}
