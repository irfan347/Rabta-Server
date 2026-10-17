package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test

class NetworkVisionStateTest {
    private val owner="000000000001"
    private val a="000000000002"
    private val b="000000000003"
    private fun observation(ids:Set<String> = setOf(a),peers:List<MeshPeer> = emptyList())=
        NetworkObservation(owner,MeshRuntimeSnapshot(running=true,active=true,peers=peers),ids)
    @Test fun onlyObservedDevicesBecomeNodesAndConnectedIdsAreDeduplicated() {
        val reducer=NetworkVisionState()
        var s=reducer.render(owner,true,observation(setOf(a,b,owner,"invalid"),listOf(MeshPeer(a,"private name",false))))
        assertEquals(2,s.peers.size);assertEquals(1,s.directCount);assertEquals(2,s.discoveredCount)
        assertFalse(s.peers.first {it.id==a}.keyMatched)
        s=reducer.render(owner,true,observation(setOf(b),listOf(MeshPeer(a,"private name",true))))
        assertEquals(2,s.peers.size);assertEquals(1,s.discoveredCount);assertTrue(s.peers.first {it.id==a}.keyMatched)
        assertFalse(s.peers.first {it.id==a}.discovered)
    }
    @Test fun discoveryLossRemovesDisconnectedNodesAndOffClearsEverything() {
        val reducer=NetworkVisionState()
        assertEquals(1,reducer.render(owner,true,observation()).peers.size)
        assertTrue(reducer.render(owner,true,observation(emptySet())).peers.isEmpty())
        assertTrue(reducer.render(owner,false,observation()).peers.isEmpty())
        assertTrue(reducer.render(owner,true,observation().copy(runtime=MeshRuntimeSnapshot())).peers.isEmpty())
    }
    @Test fun blockedPeersAndOtherAccountsCannotLeakIntoState() {
        val reducer=NetworkVisionState()
        assertTrue(reducer.render(owner,true,observation(),setOf(a)).peers.isEmpty())
        assertTrue(reducer.render(b,true,observation()).peers.isEmpty())
        assertTrue(reducer.render("bad",true,observation()).peers.isEmpty())
    }
    @Test fun relayCountersAndMessagesDoNotInventTopologyOrNodes() {
        val reducer=NetworkVisionState()
        val o=observation(emptySet()).copy(runtime=MeshRuntimeSnapshot(running=true,relayAccepted=7,receiptsAccepted=2),
            stages=mapOf(a to RadarStage.INCOMING),events=listOf(NetworkEvent(1,NetworkEventKind.RELAY_ACCEPTED,a,100)))
        val s=reducer.render(owner,true,o)
        assertTrue(s.peers.isEmpty());assertEquals(7L,s.relays);assertEquals(2L,s.receipts)
        assertNull(s.events.single().peerLabel)
    }
    @Test fun aliasesHideNamesAndSurviveConnectionStateChangesOnlyWithinAccount() {
        val reducer=NetworkVisionState()
        val first=reducer.render(owner,true,observation()).peers.single().label
        assertEquals(first,reducer.render(owner,true,observation(peers=listOf(MeshPeer(a,"secret phone 123",true)))).peers.single().label)
        assertTrue(first.startsWith("Peer "));assertFalse(first.contains(a))
        reducer.clear();assertEquals("Peer 1",reducer.render(owner,true,observation()).peers.single().label)
    }
    @Test fun receiptLatencyUsesFirstLocalQueueTimeAndIsNotRadioRtt() {
        val trace=NetworkVisionTrace()
        trace.queued("message",a,100,1000);trace.queued("message",a,200,1200)
        trace.delivered("message",a,450,1500);trace.delivered("message",a,500,1550)
        assertEquals(350L,trace.receiptTimes()[a]);assertEquals(2,trace.events().size)
        trace.delivered("restored queue",b,1000,2000);assertNull(trace.receiptTimes()[b])
        trace.queued("bad clock",b,2000,2000);trace.delivered("bad clock",b,1999,2001);assertNull(trace.receiptTimes()[b])
    }
    @Test fun telemetryIsBoundedAndClearDropsSessionObservations() {
        val trace=NetworkVisionTrace()
        repeat(1000){trace.received("$it",a,it.toLong())}
        assertEquals(40,trace.events().size)
        trace.clear();assertTrue(trace.events().isEmpty());assertTrue(trace.receiptTimes().isEmpty())
    }
}
