package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test

class MeshAttemptsTest {
    @Test fun pendingRequestBlocksOverlapUntilTimeout() {
        val attempts=MeshAttempts()
        assertTrue(attempts.begin("phone",1000))
        assertFalse(attempts.begin("phone",16000))
        assertEquals(1,attempts.pending(16000))
        assertTrue(attempts.begin("phone",31000))
    }
    @Test fun failuresBackOffAndSuccessOrDisconnectResetsBudget() {
        val attempts=MeshAttempts()
        var time=0L
        for(delay in listOf(15000L,30000L,60000L,120000L,120000L)) {
            assertTrue(attempts.begin("phone",time));attempts.failed("phone")
            assertFalse(attempts.begin("phone",time+delay-1));time+=delay
        }
        attempts.remove("phone")
        assertTrue(attempts.begin("phone",time))
        attempts.clear();assertEquals(0,attempts.pending(time))
    }
    @Test fun incomingAttemptAlsoPreventsCrossedRequests() {
        val attempts=MeshAttempts();attempts.initiated("phone",0)
        assertFalse(attempts.begin("phone",15000))
        attempts.failed("phone");assertTrue(attempts.begin("phone",15000))
    }
}
