package com.irfan.rabta

/**
 * AutoSwitch delivery states. A state only moves forward on verified evidence, so acknowledgements that
 * arrive late or out of order never undo a better state.
 *
 * Stored values written by earlier versions stay readable: "sent" (server accepted) is shown as Relayed,
 * "mesh"/"mesh_wait" (waiting in the Nearby queue) as Queued. History rows are not rewritten.
 */
object MessageDelivery {
    const val QUEUED = "queued"                 // saved on this phone; no transport has accepted it yet
    const val SENDING = "sending"               // written to the server connection, waiting for its ack
    const val RELAYED = "relayed"               // the server has it (stored or handed to the recipient's socket)
    const val DIRECT = "direct_delivered"       // recipient-signed Nearby receipt received over a direct link
    const val DELIVERED = "delivered"           // recipient's app confirmed it stored the message
    const val READ = "read"
    const val FAILED = "failed"
    const val PAUSED = "paused"
    const val CANCELED = "canceled"
    const val EXPIRED = "expired"

    /** Evidence codes posted through [RabtaEvents.postAck]. */
    const val SERVER_PREFIX = "server:"         // + the server's ack status: stored, sent, delivered, error
    const val NEARBY_QUEUED = "nearby:queued"   // sealed into the Nearby carry queue
    const val NEARBY_EXPIRED = "nearby:expired" // Nearby lifetime ended without a receipt

    private val waiting = setOf(QUEUED, SENDING, FAILED, PAUSED, CANCELED, EXPIRED, "mesh", "mesh_wait")
    private fun rank(status: String) = when (status) {
        READ -> 3
        DELIVERED, DIRECT -> 2
        RELAYED, "sent" -> 1
        else -> 0
    }

    /** The state that a piece of evidence proves, or null if it proves nothing. */
    fun evidence(code: String): String? = when (code) {
        SERVER_PREFIX + "stored", SERVER_PREFIX + "sent", SERVER_PREFIX + "delivered" -> RELAYED
        SERVER_PREFIX + "error" -> FAILED
        NEARBY_QUEUED -> QUEUED
        NEARBY_EXPIRED -> EXPIRED
        else -> null
    }

    fun canTransition(old: String, next: String): Boolean = when (next) {
        READ -> old != READ
        DELIVERED, DIRECT -> rank(old) < 2
        RELAYED -> rank(old) < 1
        SENDING -> old in waiting && old != SENDING
        QUEUED -> old in waiting && old != QUEUED
        FAILED -> old in setOf(QUEUED, SENDING, "mesh", "mesh_wait")
        EXPIRED -> old in setOf(QUEUED, SENDING, "mesh", "mesh_wait")
        PAUSED, CANCELED -> old == SENDING
        else -> false
    }

    /** Still waiting for a transport; AutoSwitch retries these without the user tapping. */
    fun pending(status: String) = status in setOf(QUEUED, SENDING, "mesh", "mesh_wait")
    fun retryable(status: String) = status in setOf(FAILED, EXPIRED, PAUSED, CANCELED)
    fun delivered(status: String) = rank(status) >= 2

    /** Icon for the chat tick: clock while waiting, ✓ relayed, ✓✓ delivered or read (read is blue), error when it needs a retry. */
    fun tickIcon(status: String): String = when {
        rank(status) >= 2 -> "done_all"
        rank(status) == 1 -> "check"
        retryable(status) -> "error"
        else -> "clock"
    }

    fun label(status: String): String = when (status) {
        QUEUED, "mesh", "mesh_wait" -> "Queued"
        SENDING -> "Sending…"
        RELAYED, "sent" -> "✓ Relayed"
        DIRECT -> "✓✓ Direct delivered"
        DELIVERED -> "✓✓ Delivered"
        READ -> "✓✓ Read"
        EXPIRED -> "Expired · Tap to resend"
        PAUSED -> "Paused · Tap to resume"
        CANCELED -> "Cancelled · Tap to retry"
        FAILED -> "Failed · Tap to retry"
        else -> status
    }
}
