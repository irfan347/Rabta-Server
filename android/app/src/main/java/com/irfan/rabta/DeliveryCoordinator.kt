package com.irfan.rabta

import com.irfan.rabta.MessageDelivery.DELIVERED
import com.irfan.rabta.MessageDelivery.DIRECT
import com.irfan.rabta.MessageDelivery.FAILED
import com.irfan.rabta.MessageDelivery.QUEUED
import com.irfan.rabta.MessageDelivery.SENDING

/**
 * Rabta AutoSwitch: one conversation, whichever transport can carry the message right now.
 *
 * Every attempt reuses the message's original ID, so the server, the Nearby store and the recipient's inbox
 * all drop repeats. The server is tried whenever the phone is online; Nearby carries 1-to-1 text and stickers
 * when the server is unreachable, and also in parallel when the recipient is directly connected over Nearby.
 * A message that no transport accepted stays Queued and is retried with backoff, on reconnection and when
 * Nearby becomes available. Only the user's retry or a permanent server error makes it Failed.
 *
 * Pure logic with injected links, so disconnection, reconnection and switching are tested on the JVM.
 */
class DeliveryCoordinator(
    private val store: Store,
    private val online: OnlineLink,
    private val nearby: NearbyLink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Outgoing(val id: String, val peerId: String, val group: Boolean, val body: String, val type: String, val createdAt: Long)

    interface Store {
        fun state(id: String): String?
        /** Applies [MessageDelivery.canTransition]; returns false when the state was not changed. */
        fun setState(id: String, state: String): Boolean
        fun outgoing(id: String): Outgoing?
        /** Outgoing messages in a [MessageDelivery.pending] state. */
        fun pending(): List<Outgoing>
    }
    enum class OnlineResult { WRITTEN, UNAVAILABLE, PAUSED, CANCELED }
    interface OnlineLink {
        val connected: Boolean
        fun send(m: Outgoing): OnlineResult
    }
    interface NearbyLink {
        val running: Boolean
        /** The recipient is connected right now over a key-verified direct Nearby link. */
        fun direct(peerId: String): Boolean
        fun carrying(id: String): Boolean
        fun send(m: Outgoing): Boolean
    }

    companion object {
        const val ACK_TIMEOUT = 30_000L
        const val MAX_QUEUE_AGE = 24 * 60 * 60 * 1000L
        private const val FIRST_RETRY = 5_000L
        private const val MAX_RETRY = 5 * 60_000L
        fun retryDelay(attempt: Int) = minOf(MAX_RETRY, FIRST_RETRY shl (attempt - 1).coerceIn(0, 16))
        fun nearbyCapable(m: Outgoing) = !m.group && m.type in setOf("text", "sticker")
    }

    private val awaitingAck = HashMap<String, Long>()      // id -> deadline for the server's ack
    private val attempts = HashMap<String, Int>()
    private val nextTry = HashMap<String, Long>()

    /** Attempt every transport that can take [m] now. */
    @Synchronized fun attempt(m: Outgoing) {
        if (store.state(m.id)?.let(MessageDelivery::pending) != true) return
        val now = clock()
        if (now - m.createdAt > MAX_QUEUE_AGE && !nearby.carrying(m.id)) { store.setState(m.id, FAILED); forget(m.id); return }
        if (online.connected && m.id !in awaitingAck) {
            store.setState(m.id, SENDING)
            when (online.send(m)) {
                OnlineResult.WRITTEN -> awaitingAck[m.id] = now + ACK_TIMEOUT
                OnlineResult.PAUSED -> { store.setState(m.id, MessageDelivery.PAUSED); forget(m.id); return }
                OnlineResult.CANCELED -> { store.setState(m.id, MessageDelivery.CANCELED); forget(m.id); return }
                OnlineResult.UNAVAILABLE -> {}
            }
        }
        val serverHasIt = m.id in awaitingAck
        if (nearbyCapable(m) && nearby.running && (!serverHasIt || nearby.direct(m.peerId))) nearby.send(m)
        if (!serverHasIt) { store.setState(m.id, QUEUED); backOff(m.id, now) }
    }

    /** Server ack or Nearby store event for [id]; see [MessageDelivery.evidence]. */
    @Synchronized fun onEvidence(id: String, code: String) {
        val state = MessageDelivery.evidence(code) ?: return
        if (code.startsWith(MessageDelivery.SERVER_PREFIX)) awaitingAck.remove(id)
        if (state == FAILED && nearby.carrying(id)) { store.setState(id, QUEUED); return } // Nearby may still deliver
        if (state == QUEUED && id in awaitingAck) return // the server attempt is still in flight
        if (store.setState(id, state) && !MessageDelivery.pending(state)) forget(id)
    }

    /** The recipient confirmed it stored [id]. Ignored unless [by] is the conversation's own peer. */
    @Synchronized fun onReceipt(id: String, by: String, direct: Boolean) {
        val m = store.outgoing(id) ?: return
        if (m.group || m.peerId != by) return
        awaitingAck.remove(id)
        if (store.setState(id, if (direct) DIRECT else DELIVERED)) forget(id)
    }

    /** The server connection dropped: anything it had not acknowledged is queued again and offered to Nearby. */
    @Synchronized fun onOffline() {
        val unacked = awaitingAck.keys.toList(); awaitingAck.clear()
        unacked.forEach { id -> store.outgoing(id)?.let { nextTry.remove(id); attempt(it) } }
    }

    /** The server or Nearby became available: retry every pending message now, ignoring backoff. */
    @Synchronized fun onLinkUp() { nextTry.clear(); tick() }

    /** Expire missing acks and retry messages whose backoff has elapsed. */
    @Synchronized fun tick() {
        val now = clock()
        awaitingAck.filterValues { it <= now }.keys.forEach { id ->
            awaitingAck.remove(id)
            if (store.state(id) == SENDING) { store.setState(id, QUEUED); backOff(id, now) }
        }
        for (m in store.pending()) if (m.id !in awaitingAck && (nextTry[m.id] ?: 0L) <= now) attempt(m)
    }

    /** When [tick] next has work to do, or null when nothing is waiting. */
    @Synchronized fun nextWake(): Long? = (awaitingAck.values + nextTry.values).minOrNull()

    /** The user asked to retry a failed, expired, paused or cancelled message. */
    @Synchronized fun retry(id: String) {
        val m = store.outgoing(id) ?: return
        if (!store.setState(id, QUEUED)) return
        forget(id); attempt(m)
    }

    private fun backOff(id: String, now: Long) {
        val n = (attempts[id] ?: 0) + 1
        attempts[id] = n; nextTry[id] = now + retryDelay(n)
    }
    private fun forget(id: String) { attempts.remove(id); nextTry.remove(id); awaitingAck.remove(id) }
}
