package com.irfan.rabta

import com.irfan.rabta.DeliveryCoordinator.OnlineResult
import com.irfan.rabta.DeliveryCoordinator.Outgoing
import org.junit.Assert.*
import org.junit.Test

/** AutoSwitch with fake transports and a fake clock; no network, radios or production data. */
class DeliveryCoordinatorTest {
    private var now = 1_000_000L
    private val states = LinkedHashMap<String, String>()
    private val messages = LinkedHashMap<String, Outgoing>()
    private val store = object : DeliveryCoordinator.Store {
        override fun state(id: String) = states[id]
        override fun setState(id: String, state: String): Boolean {
            val old = states[id] ?: return false
            if (!MessageDelivery.canTransition(old, state)) return false
            states[id] = state; return true
        }
        override fun outgoing(id: String) = messages[id]
        override fun pending() = messages.values.filter { MessageDelivery.pending(states.getValue(it.id)) }
    }
    private val online = object : DeliveryCoordinator.OnlineLink {
        override var connected = false
        var result = OnlineResult.WRITTEN
        val sent = mutableListOf<String>()
        override fun send(m: Outgoing): OnlineResult { if (result == OnlineResult.WRITTEN) sent += m.id; return result }
    }
    private val nearby = object : DeliveryCoordinator.NearbyLink {
        override var running = false
        var directPeers = setOf<String>()
        val carried = LinkedHashSet<String>()
        val sends = mutableListOf<String>()
        override fun direct(peerId: String) = peerId in directPeers
        override fun carrying(id: String) = id in carried
        override fun send(m: Outgoing): Boolean { sends += m.id; carried += m.id; return true }
    }
    private val autoSwitch = DeliveryCoordinator(store, online, nearby) { now }

    private fun message(id: String, type: String = "text", group: Boolean = false): Outgoing {
        val m = Outgoing(id, if (group) "GROUP0000001" else "PEER00000001", group, "hello", type, now)
        messages[id] = m; states[id] = MessageDelivery.QUEUED; return m
    }

    @Test fun offlineMessageIsQueuedNotFailed() {
        autoSwitch.attempt(message("A00000000001"))
        assertEquals("queued", states["A00000000001"])
        assertTrue(online.sent.isEmpty() && nearby.sends.isEmpty())
        assertNotNull("A retry is scheduled", autoSwitch.nextWake())
    }

    @Test fun reconnectionSendsQueuedMessagesWithTheSameId() {
        autoSwitch.attempt(message("A00000000001"))
        online.connected = true; autoSwitch.onLinkUp()
        assertEquals(listOf("A00000000001"), online.sent)
        assertEquals("sending", states["A00000000001"])
        autoSwitch.onEvidence("A00000000001", "server:stored")
        assertEquals("relayed", states["A00000000001"])
        autoSwitch.onReceipt("A00000000001", "PEER00000001", direct = false)
        assertEquals("delivered", states["A00000000001"])
    }

    @Test fun serverAckIsRelayedNeverDelivered() {
        online.connected = true; autoSwitch.attempt(message("A00000000001"))
        autoSwitch.onEvidence("A00000000001", "server:delivered")
        assertEquals("Socket write is not recipient confirmation", "relayed", states["A00000000001"])
    }

    @Test fun disconnectionBeforeAckSwitchesTextToNearby() {
        online.connected = true; nearby.running = true
        autoSwitch.attempt(message("A00000000001"))
        assertTrue("Online first; Nearby only in parallel for a direct peer", nearby.sends.isEmpty())
        online.connected = false; autoSwitch.onOffline()
        assertEquals(listOf("A00000000001"), nearby.sends)
        assertEquals("queued", states["A00000000001"])
        autoSwitch.onReceipt("A00000000001", "PEER00000001", direct = true)
        assertEquals("direct_delivered", states["A00000000001"])
    }

    @Test fun directNearbyPeerIsUsedInParallelWithTheServer() {
        online.connected = true; nearby.running = true; nearby.directPeers = setOf("PEER00000001")
        autoSwitch.attempt(message("A00000000001"))
        assertEquals(listOf("A00000000001"), online.sent)
        assertEquals(listOf("A00000000001"), nearby.sends)
        autoSwitch.onEvidence("A00000000001", "nearby:queued")
        assertEquals("Nearby queueing does not hide the in-flight server send", "sending", states["A00000000001"])
    }

    @Test fun missingAckRequeuesAndRetriesWithBackoff() {
        online.connected = true; autoSwitch.attempt(message("A00000000001"))
        now += DeliveryCoordinator.ACK_TIMEOUT; autoSwitch.tick()
        assertEquals("queued", states["A00000000001"])
        assertEquals(1, online.sent.size)
        now += DeliveryCoordinator.retryDelay(1) - 1; autoSwitch.tick()
        assertEquals("Not before the backoff", 1, online.sent.size)
        now += 1; autoSwitch.tick()
        assertEquals(listOf("A00000000001", "A00000000001"), online.sent)
    }

    @Test fun backoffGrowsAndIsCapped() {
        assertEquals(5_000L, DeliveryCoordinator.retryDelay(1))
        assertEquals(10_000L, DeliveryCoordinator.retryDelay(2))
        assertEquals(40_000L, DeliveryCoordinator.retryDelay(4))
        assertEquals(300_000L, DeliveryCoordinator.retryDelay(30))
    }

    @Test fun lateAndOutOfOrderEvidenceNeverDowngrades() {
        online.connected = true; autoSwitch.attempt(message("A00000000001"))
        autoSwitch.onReceipt("A00000000001", "PEER00000001", direct = false)
        autoSwitch.onEvidence("A00000000001", "server:stored")
        autoSwitch.onEvidence("A00000000001", "server:error")
        autoSwitch.onEvidence("A00000000001", "nearby:expired")
        now += DeliveryCoordinator.ACK_TIMEOUT * 10; autoSwitch.tick()
        assertEquals("delivered", states["A00000000001"])
        assertEquals("Delivered messages are never resent", 1, online.sent.size)
    }

    @Test fun receiptFromAnyoneButTheRecipientIsIgnored() {
        online.connected = true; autoSwitch.attempt(message("A00000000001"))
        autoSwitch.onReceipt("A00000000001", "OTHER0000001", direct = false)
        assertEquals("sending", states["A00000000001"])
        autoSwitch.attempt(message("G00000000001", group = true))
        autoSwitch.onReceipt("G00000000001", "GROUP0000001", direct = false)
        assertEquals("Group messages have no single recipient receipt", "sending", states["G00000000001"])
    }

    @Test fun duplicateAttemptsReuseTheInFlightSend() {
        online.connected = true; val m = message("A00000000001")
        autoSwitch.attempt(m); autoSwitch.attempt(m); autoSwitch.onLinkUp()
        assertEquals(1, online.sent.size)
    }

    @Test fun mediaAndGroupsWaitForTheServerInsteadOfNearby() {
        nearby.running = true; nearby.directPeers = setOf("PEER00000001")
        autoSwitch.attempt(message("I00000000001", type = "image"))
        autoSwitch.attempt(message("G00000000001", group = true))
        assertTrue("Nearby carries 1-to-1 text and stickers only", nearby.sends.isEmpty())
        assertEquals("queued", states["I00000000001"]); assertEquals("queued", states["G00000000001"])
    }

    @Test fun permanentServerErrorFailsUnlessNearbyStillCarriesIt() {
        online.connected = true; autoSwitch.attempt(message("A00000000001"))
        autoSwitch.onEvidence("A00000000001", "server:error")
        assertEquals("failed", states["A00000000001"])
        nearby.running = true; nearby.directPeers = setOf("PEER00000001")
        autoSwitch.attempt(message("A00000000002"))
        autoSwitch.onEvidence("A00000000002", "server:error")
        assertEquals("queued", states["A00000000002"])
    }

    @Test fun pausedUploadStopsAndUserRetryRequeues() {
        online.connected = true; online.result = OnlineResult.PAUSED
        autoSwitch.attempt(message("F00000000001", type = "file"))
        assertEquals("paused", states["F00000000001"])
        online.result = OnlineResult.WRITTEN; autoSwitch.retry("F00000000001")
        assertEquals("sending", states["F00000000001"])
    }

    @Test fun oldQueuedMessageFailsAfterMaximumAge() {
        autoSwitch.attempt(message("A00000000001"))
        now += DeliveryCoordinator.MAX_QUEUE_AGE + 1; autoSwitch.onLinkUp()
        assertEquals("failed", states["A00000000001"])
    }

    @Test fun legacyNearbyQueuedRowsAreRetriedOnline() {
        val m = message("A00000000001"); states[m.id] = "mesh"
        online.connected = true; autoSwitch.onLinkUp()
        assertEquals(listOf("A00000000001"), online.sent)
        assertEquals("sending", states["A00000000001"])
    }
}
