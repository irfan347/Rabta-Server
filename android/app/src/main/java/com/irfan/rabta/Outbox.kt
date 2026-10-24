package com.irfan.rabta

import android.content.Context
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Persists before transmission and handles receipts even when no chat is open. Transport choice, retries and
 * delivery states belong to [DeliveryCoordinator] (Rabta AutoSwitch); this object connects it to the phone's
 * database, the server connection and Nearby, and runs it on one worker thread.
 */
object Outbox : RabtaEvents.Listener {
    private lateinit var context: Context
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var prepared: Db? = null
    private var coordinator: DeliveryCoordinator? = null
    private var wake: ScheduledFuture<*>? = null

    /** Follows the signed-in account; each account's interrupted sends are queued again once, when first used. */
    @Synchronized private fun current(): Pair<Db, DeliveryCoordinator>? {
        if (!::context.isInitialized) return null
        val d = Db.get(context)
        if (d !== prepared) {
            val c = DeliveryCoordinator(Store(d), OnlineTransport, NearbyTransport)
            prepared = d; coordinator = c
            if (d.account != null) worker.execute { d.requeueInterruptedSends(); c.onLinkUp(); reschedule() }
        }
        return d to coordinator!!
    }
    @Synchronized fun init(context: Context) {
        if (::context.isInitialized) return
        this.context=context.applicationContext
        current()
        RabtaEvents.add(this)
    }
    fun send(peerId: String, peerName: String, group: Boolean, body: String, type: String) {
        val (d, c) = current() ?: return
        val id = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }
        val time = System.currentTimeMillis()
        // Message yourself: the note is kept on this phone and nothing is sent anywhere.
        val note = !group && peerId == Session.id
        worker.execute {
            d.saveMessage(id, peerId, Session.id, body, time, true, if (note) MessageDelivery.READ else MessageDelivery.QUEUED, type)
            val preview = when (type) { "image" -> "📷 Photo"; "voice" -> VoiceAudio.label(context, body).let { if (it.isEmpty()) "🎤 Voice message" else "🎤 Voice message · $it" }; "video" -> "🎥 Video"; "file" -> Attachments.name(body); "sticker" -> "Sticker"; "rich" -> org.json.JSONObject(body).optString("text"); else -> body }
            d.upsertConversation(peerId, peerName, preview, time, false, group)
            RabtaEvents.postHistoryChanged(peerId)
            if (note) return@execute
            c.attempt(DeliveryCoordinator.Outgoing(id, peerId, group, body, type, time)); reschedule()
        }
    }
    /** A browser retry uses the same ID; acknowledgment means persisted on this phone, not delivered. */
    fun sendFromWeb(peer:String,name:String,body:String,id:String,guard:()->Boolean,done:(Boolean)->Unit) {
        val pair=current() ?: run {done(false);return}
        val (d,c)=pair
        worker.execute {
            val stored=runCatching {
                require(guard() && Db.get(context)===d)
                if(d.hasMessage(id)) {
                    val old=d.outgoing(id)
                    require(old!=null && old.convId==peer && old.body==body && old.msgType=="text")
                } else {
                    val time=System.currentTimeMillis();val account=d.account ?: error("Missing account")
                    d.writableDatabase.beginTransaction()
                    try {
                        require(guard())
                        d.saveMessage(id,peer,account,body,time,true,if(peer==account)MessageDelivery.READ else MessageDelivery.QUEUED,"text")
                        d.upsertConversation(peer,name,body,time,false,false)
                        d.writableDatabase.setTransactionSuccessful()
                    } finally {d.writableDatabase.endTransaction()}
                }
            }.isSuccess
            if(stored) {
                RabtaEvents.postHistoryChanged(peer)
                if(guard() && peer!=d.account)runCatching {
                    if(d.status(id)==MessageDelivery.QUEUED)d.outgoing(id)?.let {
                        c.attempt(DeliveryCoordinator.Outgoing(it.id,it.convId,false,it.body,it.msgType,it.sentAt))
                    }
                    reschedule()
                }
            }
            done(stored)
        }
    }

    fun retry(message: Db.Message, group: Boolean) {
        val (d, c) = current() ?: return
        worker.execute {
            // The Nearby lifetime ended: send a fresh copy rather than reviving the expired envelope.
            if (d.status(message.id) == MessageDelivery.EXPIRED) { send(message.convId,message.convId,group,message.body,message.msgType); return@execute }
            c.retry(message.id); reschedule()
        }
    }
    private fun withCoordinator(action: (Db, DeliveryCoordinator) -> Unit) {
        val (d, c) = current() ?: return
        worker.execute { action(d, c); reschedule() }
    }
    /** Wakes the coordinator when its next ack deadline or backoff elapses. */
    @Synchronized private fun reschedule() {
        val c = coordinator ?: return
        val at = c.nextWake() ?: return
        wake?.cancel(false)
        wake = worker.schedule({ c.tick(); reschedule() }, maxOf(1000L, at - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
    }

    override fun onConnected() = withCoordinator { d, c -> Privacy.sync(context); c.onLinkUp(); d.pausedMessages().forEach { c.retry(it.id) } }
    override fun onDisconnected() { if (!WsClient.connected) withCoordinator { _, c -> c.onOffline() } }
    override fun onNearbyLinked() = withCoordinator { _, c -> c.onLinkUp() }
    override fun onAck(msgId: String, status: String) = withCoordinator { _, c -> c.onEvidence(msgId, status) }
    override fun onReceipt(msgId: String, byId: String, direct: Boolean) = withCoordinator { _, c -> c.onReceipt(msgId, byId, direct) }
    override fun onRead(msgId: String, byId: String) = withCoordinator { d, _ ->
        d.applyReadReceipt(msgId, byId)
        RabtaEvents.postHistoryChanged(byId)
    }

    private class Store(private val d: Db) : DeliveryCoordinator.Store {
        override fun state(id: String) = d.status(id)
        override fun setState(id: String, state: String) = d.updateStatus(id, state).also { changed ->
            if (changed) d.conversationForMessage(id)?.let { RabtaEvents.postHistoryChanged(it) }
        }
        override fun outgoing(id: String) = d.outgoing(id)?.let(::wrap)
        override fun pending() = d.pendingOutgoing().map(::wrap)
        private fun wrap(m: Db.Message) = DeliveryCoordinator.Outgoing(m.id, m.convId, d.isGroupConversation(m.convId), m.body, m.msgType, m.sentAt)
    }

    private object OnlineTransport : DeliveryCoordinator.OnlineLink {
        override val connected get() = WsClient.connected
        override fun send(m: DeliveryCoordinator.Outgoing) = try {
            val wire = if (m.type in setOf("file","video","sticker") && !m.body.startsWith("rbt:")) Transfers.upload(Outbox.context, m.body, m.id) { RabtaEvents.postHistoryChanged(m.peerId) } else Attachments.wire(Outbox.context, m.body, m.type)
            val written = if (m.group) WsClient.sendGroupMsg(m.peerId, m.id, wire, m.type) else WsClient.send(m.peerId, m.id, wire, m.type)
            if (written) DeliveryCoordinator.OnlineResult.WRITTEN else DeliveryCoordinator.OnlineResult.UNAVAILABLE
        } catch (_: Transfers.Cancelled) { DeliveryCoordinator.OnlineResult.CANCELED
        } catch (_: java.io.IOException) { DeliveryCoordinator.OnlineResult.PAUSED
        } catch (_: Exception) { DeliveryCoordinator.OnlineResult.UNAVAILABLE }
    }

    private object NearbyTransport : DeliveryCoordinator.NearbyLink {
        override val running get() = MeshBridge.running
        override fun direct(peerId: String) = MeshBridge.direct(peerId)
        override fun carrying(id: String) = MeshBridge.carrying(id)
        // The Nearby protocol carries text and validated stickers only; it never sends raw media as text.
        override fun send(m: DeliveryCoordinator.Outgoing) = runCatching { MeshBridge.sendDirect(m.peerId, m.id, m.body, m.type) }.getOrDefault(false)
    }
}
