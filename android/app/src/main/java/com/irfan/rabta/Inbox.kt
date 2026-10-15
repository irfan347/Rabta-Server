package com.irfan.rabta

import android.content.Context
import java.util.concurrent.Executors

object Inbox {
    private var db: Db? = null
    private val worker = Executors.newSingleThreadExecutor()

    fun init(ctx: Context) { if (db == null) db = Db.get(ctx.applicationContext) }

    /** convId = peerId for DMs, groupId for groups. Returns true if new. */
    fun receive(msgId: String, fromId: String, fromName: String, body: String, sentAt: Long,
                convId: String, convName: String, msgType: String = "text", isGroup: Boolean = false) {
        val d = db ?: return
        if (msgId.isEmpty() || fromId.isEmpty()) return
        // Skip audio frame data accidentally routed as messages
        if (msgType == "text" && body.length > 2000 && body.matches(Regex("[A-Za-z0-9+/=]+"))) return
        worker.execute {
            try {
                val isNew = d.saveIfNew(msgId, convId, fromId, fromName, body, sentAt, false, "received", msgType)
                if (isNew) {
                    val preview = when (msgType) { "image" -> "📷 Photo"; "voice" -> "🎤 Voice note"; "file" -> "📎 File"; else -> body }
                    val showBadge = ChatActivity.openPeer != convId
                    d.upsertConversation(convId, convName, preview, sentAt, showBadge, isGroup)
                    RabtaEvents.postMessage(fromId, fromName, msgId, body, sentAt, msgType, if (isGroup) convId else "")
                }
            } catch (_: Exception) {}
        }
    }
}
