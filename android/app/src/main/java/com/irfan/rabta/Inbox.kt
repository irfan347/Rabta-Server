package com.irfan.rabta

import android.content.Context
import java.util.concurrent.Executors

object Inbox {
    private val db: Db? get() = if (::context.isInitialized) Db.get(context) else null // follows the signed-in account
    private lateinit var context: Context
    private val worker = Executors.newSingleThreadExecutor()

    fun init(ctx: Context) { context=ctx.applicationContext }

    /** convId = peerId for DMs, groupId for groups. Returns true if new. */
    fun receive(msgId: String, fromId: String, fromName: String, body: String, sentAt: Long,
                convId: String, convName: String, msgType: String = "text", isGroup: Boolean = false, stored: () -> Unit = {}) {
        val d = db ?: return
        if (msgId.isEmpty() || fromId.isEmpty() || Privacy.blocked(context,fromId)) return
        // Skip audio frame data accidentally routed as messages
        if (msgType == "text" && body.length > 2000 && body.matches(Regex("[A-Za-z0-9+/=]+"))) return
        worker.execute {
            try {
                if(msgType=="reaction") { val o=org.json.JSONObject(body);val target=o.getString("target");if(d.conversationForMessage(target)==convId){d.react(target,fromId,o.optString("emoji").take(16));RabtaEvents.postHistoryChanged(convId)};stored();return@execute }
                if(d.hasMessage(msgId)) { stored(); return@execute }
                val localBody=Attachments.receive(context,body,msgType)
                val isNew = d.saveIfNew(msgId, convId, fromId, fromName, localBody, sentAt, false, "received", msgType)
                if (isNew) {
                    val preview = when (msgType) { "image" -> "📷 Photo"; "voice" -> "🎤 Voice note"; "file" -> "File"; "video" -> "Video"; "sticker" -> "Sticker"; "rich" -> runCatching { org.json.JSONObject(body).getString("text") }.getOrDefault("Message"); else -> body }
                    val showBadge = ChatActivity.openPeer != convId
                    d.upsertConversation(convId, convName, preview, sentAt, showBadge, isGroup)
                    RabtaEvents.postMessage(fromId, fromName, msgId, localBody, sentAt, msgType, if (isGroup) convId else "")
                }
                stored()
            } catch (_: Exception) {}
        }
    }
}
