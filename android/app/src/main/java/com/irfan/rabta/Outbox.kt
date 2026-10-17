package com.irfan.rabta

import android.content.Context
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Persists before transmission and handles receipts even when no chat is open. */
object Outbox : RabtaEvents.Listener {
    private lateinit var context: Context
    private var prepared: Db? = null
    /** Follows the signed-in account; each account's interrupted sends are marked failed once, when first used. */
    @Synchronized private fun currentDb(): Db? {
        if (!::context.isInitialized) return null
        val d = Db.get(context)
        if (d !== prepared) { prepared = d; if (d.account != null) worker.execute { d.failInterruptedSends() } }
        return d
    }
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val attempts = mutableMapOf<String, Long>()
    private var attemptSequence=0L
    @Synchronized fun init(context: Context) {
        if (::context.isInitialized) return
        this.context=context.applicationContext
        currentDb()
        RabtaEvents.add(this)
    }
    fun send(peerId: String, peerName: String, group: Boolean, body: String, type: String) {
        val d = currentDb() ?: return
        val id = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }
        val time = System.currentTimeMillis()
        worker.execute {
            d.saveMessage(id, peerId, Session.id, body, time, true, "sending", type)
            val preview = when (type) { "image" -> "Photo"; "voice" -> "Voice note"; "video" -> "Video"; "file" -> Attachments.name(body); "sticker" -> "Sticker"; "rich" -> org.json.JSONObject(body).optString("text"); else -> body }
            d.upsertConversation(peerId, peerName, preview, time, false, group)
            RabtaEvents.postHistoryChanged(peerId)
            transmit(d, id, peerId, group, body, type)
        }
    }
    fun retry(message: Db.Message, group: Boolean) {
        val d = currentDb() ?: return
        worker.execute {
            if (d.status(message.id) == "expired") { send(message.convId,message.convId,group,message.body,message.msgType); return@execute }
            if (d.status(message.id) !in setOf("failed","paused","canceled")) return@execute
            d.updateStatus(message.id, "sending")
            RabtaEvents.postHistoryChanged(message.convId)
            transmit(d, message.id, message.convId, group, message.body, message.msgType)
        }
    }
    private fun transmit(d: Db, id: String, peerId: String, group: Boolean, body: String, type: String) {
        val attempt=++attemptSequence
        attempts[id]=attempt
        val net = try { val wire=if(type in setOf("file","video","sticker")&&!body.startsWith("rbt:")&&WsClient.connected) Transfers.upload(context,body,id) { RabtaEvents.postHistoryChanged(peerId) } else Attachments.wire(context,body,type); if (group) WsClient.sendGroupMsg(peerId,id,wire,type) else WsClient.send(peerId,id,wire,type) } catch (_: Transfers.Cancelled) { attempts.remove(id);d.updateStatus(id,"canceled");RabtaEvents.postHistoryChanged(peerId);return } catch (_: java.io.IOException) { attempts.remove(id);d.updateStatus(id,"paused");RabtaEvents.postHistoryChanged(peerId);return } catch (_: Exception) { false }
        // The existing mesh protocol carries text only. Never send raw image/audio as text.
        val mesh = !net && !group && type in setOf("text","sticker") && try { MeshBridge.sendDirect(peerId,id,body,type) } catch (_: Exception) { false }
        if (!net) {
            attempts.remove(id)
            d.updateStatus(id, if (mesh) (if(MeshBridge.hasKey(peerId)) "mesh" else "mesh_wait") else "failed")
            RabtaEvents.postHistoryChanged(peerId)
        } else worker.schedule(Runnable {
            if (attempts[id] == attempt && d.status(id) == "sending") {
                d.updateStatus(id, "failed")
                RabtaEvents.postHistoryChanged(peerId)
            }
            if(attempts[id]==attempt) attempts.remove(id)
        }, 30, TimeUnit.SECONDS)
    }
    override fun onConnected() { val d=currentDb()?:return; worker.execute { Privacy.sync(context);d.pausedMessages().forEach { retry(it,d.group(it.convId)!=null) } } }
    override fun onAck(msgId: String, status: String) {
        val d = currentDb() ?: return
        val normalized=MessageDelivery.normalizeReceipt(status) ?: return
        worker.execute {
            d.updateStatus(msgId, normalized)
            d.conversationForMessage(msgId)?.let { RabtaEvents.postHistoryChanged(it) }
        }
    }
    override fun onRead(msgId: String, byId: String) {
        val d = currentDb() ?: return
        worker.execute {
            d.applyReadReceipt(msgId, byId)
            RabtaEvents.postHistoryChanged(byId)
        }
    }
}
