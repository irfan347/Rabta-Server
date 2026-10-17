package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test

class NearbySearchTest {
    private val peers=listOf(
        RadarPeer("a","ABCDEF123456","Ali",RadarStage.INCOMING,code="1234"),
        RadarPeer("b","999900001111","Samira",RadarStage.CONNECTED),
        RadarPeer("c","222233334444","علی",RadarStage.FAILED,retryAt=1000)
    )
    @Test fun searchMatchesRealNamesOrIdsWithoutChangingConsentOrOrder() {
        assertEquals(listOf(peers[0]),NearbySearch.filter(peers," aLi "))
        assertEquals(listOf(peers[0]),NearbySearch.filter(peers,"abcdef"))
        assertEquals(listOf(peers[2]),NearbySearch.filter(peers,"علی"))
        assertEquals(RadarStage.INCOMING,NearbySearch.filter(peers,"Ali").single().stage)
        assertEquals("1234",NearbySearch.filter(peers,"Ali").single().code)
        assertEquals(peers,NearbySearch.filter(peers,"  "))
    }
    @Test fun emptySearchResultsNeverSynthesizePeersOrSearchPrivateData() {
        assertTrue(NearbySearch.filter(emptyList(),"").isEmpty())
        assertTrue(NearbySearch.filter(peers,"not discovered").isEmpty())
        assertTrue(NearbySearch.filter(peers,"1234 connection code").isEmpty())
        assertEquals(3,peers.size)
    }
}
