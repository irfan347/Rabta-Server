package com.irfan.rabta

/** Receipt states never go backwards when acknowledgements arrive out of order. */
object MessageDelivery {
    fun normalizeReceipt(status: String): String? = when(status) {
        "stored" -> "sent" // The existing server accepted an offline message.
        "error" -> "failed"
        "sent", "delivered", "read", "failed", "expired" -> status
        else -> null
    }
    fun canTransition(old: String, next: String): Boolean = when (next) {
        "read" -> true
        "delivered" -> old != "read"
        "sent" -> old !in setOf("delivered", "read")
        "mesh" -> old in setOf("sending", "failed", "mesh")
        "expired" -> old == "mesh"
        "failed" -> old == "sending"
        "sending" -> old in setOf("failed","expired")
        else -> false
    }
    fun label(status: String): String = when (status) {
        "sending" -> "Sending…"
        "sent" -> "✓ Sent"
        "delivered" -> "✓✓ Delivered"
        "read" -> "✓✓ Read"
        "mesh" -> "Nearby queued"
        "expired" -> "Expired · Tap to resend"
        "failed" -> "Failed · Tap to retry"
        else -> status
    }
}
