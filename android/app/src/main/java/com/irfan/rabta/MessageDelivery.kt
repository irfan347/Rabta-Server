package com.irfan.rabta

/** Receipt states never go backwards when acknowledgements arrive out of order. */
object MessageDelivery {
    fun normalizeReceipt(status: String): String? = when(status) {
        "stored" -> "sent" // The existing server accepted an offline message.
        "error" -> "failed"
        "sent", "delivered", "read", "failed", "expired", "mesh" -> status
        else -> null
    }
    fun canTransition(old: String, next: String): Boolean = when (next) {
        "read" -> true
        "delivered" -> old != "read"
        "sent" -> old !in setOf("delivered", "read")
        "mesh" -> old in setOf("sending", "failed", "mesh", "mesh_wait")
        "mesh_wait" -> old in setOf("sending", "failed")
        "expired" -> old in setOf("mesh","mesh_wait")
        "failed" -> old == "sending"
        "paused", "canceled" -> old=="sending"
        "sending" -> old in setOf("failed","expired","paused","canceled")
        else -> false
    }
    fun label(status: String): String = when (status) {
        "sending" -> "Sending…"
        "sent" -> "✓ Sent"
        "delivered" -> "✓✓ Delivered"
        "read" -> "✓✓ Read"
        "mesh" -> "Nearby queued"
        "mesh_wait" -> "Waiting for contact key"
        "expired" -> "Expired · Tap to resend"
        "paused" -> "Paused · Tap to resume"
        "canceled" -> "Cancelled · Tap to retry"
        "failed" -> "Failed · Tap to retry"
        else -> status
    }
}
