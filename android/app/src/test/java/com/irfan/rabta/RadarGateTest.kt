package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test

class RadarGateTest {
    private val id="ABCDEF123456"
    @Test fun discoveryNeverImpliesConsentAndApprovalIsConnectionScoped() {
        val gate=RadarGate { false }
        assertTrue(gate.found("endpoint",id,"Irfan"));assertNull(gate.connected("endpoint",0))
        assertEquals(RadarGate.Decision.ASK,gate.initiated("endpoint",id,"Irfan","1234",0))
        assertNull(gate.connected("endpoint",1));assertTrue(gate.approve("endpoint",1))
        assertNotNull(gate.connected("endpoint",2))
        gate.failed("endpoint");assertNull(gate.connected("endpoint",3))
    }
    @Test fun localRequestAuthorizesOnlyTheRequestedIdentity() {
        val gate=RadarGate { false };gate.found("endpoint",id,"User")
        assertTrue(gate.request("endpoint",0));assertFalse(gate.request("endpoint",1))
        assertEquals(RadarGate.Decision.REJECT,gate.initiated("endpoint","000000000001","Impostor","1234",2))
        assertEquals(RadarGate.Decision.ACCEPT,gate.initiated("endpoint",id,"User","1234",3))
        assertNotNull(gate.connected("endpoint",4))
    }
    @Test fun rejectAndRestartDoNotBypassCooldownOrAllowLateSuccess() {
        val gate=RadarGate { false }
        assertEquals(RadarGate.Decision.ASK,gate.initiated("first",id,"User","1234",0))
        gate.reject("first",1);assertNull(gate.connected("first",2));gate.clear()
        assertEquals(RadarGate.Decision.REJECT,gate.initiated("rotated",id,"User","1234",60001))
        assertEquals(RadarGate.Decision.ASK,gate.initiated("rotated",id,"User","1234",120001))
    }
    @Test fun expiryAndBlockPreventApprovalAndConnection() {
        val blocks=mutableSetOf<String>();val gate=RadarGate { it in blocks }
        gate.initiated("endpoint",id,"User","1234",0)
        assertFalse(gate.approve("endpoint",60000));assertEquals(listOf("endpoint"),gate.expired(60000))
        gate.initiated("endpoint",id,"User","1234",60001);assertTrue(gate.approve("endpoint",60002))
        blocks.add(id);assertNull(gate.connected("endpoint",60003));assertTrue(gate.snapshot().isEmpty())
        assertFalse(gate.found("new",id,"User"))
    }
    @Test fun rotatingIdsAreBoundedByGlobalInboundBudget() {
        val gate=RadarGate { false }
        repeat(5) { i -> assertEquals(RadarGate.Decision.ASK,gate.initiated("p$i","%012X".format(i+1),"User","1234",0)) }
        assertEquals(RadarGate.Decision.REJECT,gate.initiated("sixth","000000000006","User","1234",1))
    }
    @Test fun duplicateEndpointAndDiscoveredListHaveBounds() {
        val gate=RadarGate { false }
        gate.initiated("first",id,"User","1234",0)
        assertEquals(RadarGate.Decision.IGNORE,gate.initiated("first",id,"User","1234",1))
        assertEquals(RadarGate.Decision.REJECT,gate.initiated("rotated",id,"User","1234",1))
        for(i in 1..199)assertTrue(gate.found("p$i","%012X".format(i),"User"))
        assertFalse(gate.found("overflow","000000000999","User"))
    }
    @Test fun lostDiscoveryDoesNotCancelPendingConsentButNeverRevivesApproval() {
        val gate=RadarGate { false };gate.found("endpoint",id,"User")
        gate.lost("endpoint");assertTrue(gate.snapshot().isEmpty())
        gate.initiated("endpoint",id,"User","1234",0);gate.lost("endpoint")
        assertEquals(RadarStage.INCOMING,gate.snapshot().single().stage)
        gate.expired(60000);assertNull(gate.connected("endpoint",60001))
    }
    @Test fun duplicateAdvertisedIdCannotHideTheIncomingRequest() {
        val gate=RadarGate { false };gate.found("discovered-first",id,"User")
        gate.initiated("request-endpoint",id,"User","1234",0)
        assertEquals("request-endpoint",gate.snapshot().single().endpoint)
        assertFalse(gate.request("discovered-first",1))
    }
}
