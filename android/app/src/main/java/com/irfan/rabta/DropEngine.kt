package com.irfan.rabta

import com.irfan.rabta.DropProtocol.CHUNK
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Rabta Drop transfers between two directly connected, key-verified phones.
 *
 * Sender: hash → signed offer → receiver's explicit approval → encrypted chunk stream → receiver's verified "done".
 * Receiver: an offer is only shown; nothing is written until the user accepts. Chunks are authenticated as they
 * arrive, written to a private partial file, and the whole file is checked against the signed SHA-256 before it
 * is published where the user can open it. An interrupted transfer keeps its verified prefix; the sender's retry
 * resumes from it (only for a transfer this user already accepted). Pure logic with injected platform pieces.
 */
class DropEngine(
    private val me: String,
    private val myName: String,
    private val link: Link,
    private val files: Files,
    private val store: Store,
    private val keys: Keys,
    private val events: Listener,
    private val io: (Runnable) -> Unit,
    private val later: (Long, Runnable) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class State { PREPARING, WAITING, INCOMING, SENDING, RECEIVING, VERIFYING, COMPLETED, FAILED, DECLINED, CANCELED }
    data class Transfer(
        val id: String, val outgoing: Boolean, val peer: String, val peerName: String, val name: String, val mime: String,
        val size: Long, val sha256: String, val state: State, val bytes: Long = 0, val speed: Long = 0,
        val started: Long, val updated: Long, val source: String = "", val saved: String = "", val error: String = "",
        val accepted: Boolean = false,
    ) {
        val active get() = state in setOf(State.PREPARING, State.WAITING, State.SENDING, State.RECEIVING, State.VERIFYING)
        val retryable get() = outgoing && source.isNotEmpty() && state in setOf(State.FAILED, State.CANCELED, State.DECLINED)
    }
    data class Source(val name: String, val mime: String, val size: Long, val open: () -> InputStream)

    interface Link {
        /** [peer] is connected now over a direct link whose identity key matches its pinned key. */
        fun verified(peer: String): Boolean
        fun name(peer: String): String
        fun control(peer: String, message: String): Boolean
        /** Sends [data] as one stream payload; returns its payload id, or null if the link is unavailable. */
        fun stream(peer: String, data: InputStream): Long?
        fun cancel(payloadId: Long)
    }
    interface Files {
        fun source(uri: String): Source?
        fun partial(id: String): File
        /** Moves a verified file where the user can open it; returns its location, or null on failure. */
        fun publish(partial: File, name: String, mime: String): String?
    }
    interface Store {
        fun save(t: Transfer)
        fun get(id: String): Transfer?
    }
    interface Keys {
        val private: PrivateKey
        fun pinned(peer: String): PublicKey?
    }
    interface Listener {
        fun changed(t: Transfer)
        fun incoming(t: Transfer) {}
    }

    private val lock = Any()
    private val fileKeys = ConcurrentHashMap<String, ByteArray>()
    private val payloads = ConcurrentHashMap<String, Long>()          // transfer -> stream payload
    private val incomingStreams = ConcurrentHashMap<Long, Pair<String, InputStream>>() // payload -> (peer, stream)
    private val awaitingStream = ConcurrentHashMap<Long, Pair<String, Long>>()       // payload -> (transfer, offset)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val meters = ConcurrentHashMap<String, DropProtocol.SpeedMeter>()
    private val lastPublished = ConcurrentHashMap<String, Long>()

    private fun put(t: Transfer, notify: Boolean = true): Transfer { store.save(t); if (notify) events.changed(t); return t }
    private fun update(id: String, change: (Transfer) -> Transfer?): Transfer? = synchronized(lock) {
        val old = store.get(id) ?: return null
        change(old)?.let { put(it.copy(updated = clock())) }
    }
    private fun fail(id: String, reason: String, from: Set<State>? = null) = update(id) { t ->
        if (!t.active || (from != null && t.state !in from)) null else t.copy(state = State.FAILED, error = reason, speed = 0)
    }
    private fun send(peer: String, vararg fields: Pair<String, String>) = link.control(peer, DropProtocol.encode(mapOf(*fields)))

    // ---------- sender ----------

    /** Offers the file at [uri] to [peer]; reuses [retryId] (and its receiver-side progress) on retry. */
    fun offer(peer: String, uri: String, retryId: String? = null) = io(Runnable {
        val source = files.source(uri)
        val now = clock()
        val reuse = retryId?.let { store.get(it) }
        val id = reuse?.id ?: DropProtocol.newId()
        if (source == null || !DropProtocol.allowed(source.mime) || source.size !in 1..DropProtocol.MAX_SIZE) {
            put(Transfer(id, true, peer, link.name(peer), source?.name ?: "file", source?.mime ?: "", source?.size ?: 0, "", State.FAILED,
                started = now, updated = now, source = uri, error = if (source == null) "File can no longer be opened" else "This file type or size cannot be sent"))
            return@Runnable
        }
        cancelled.remove(id)
        put(Transfer(id, true, peer, link.name(peer), DropProtocol.safeName(source.name), source.mime, source.size, reuse?.sha256 ?: "", State.PREPARING,
            started = reuse?.started ?: now, updated = now, source = uri))
        try {
            val sha = DropProtocol.sha256(source.open()) { id in cancelled }
            // A changed file must not resume someone's partial copy of the old one.
            if (reuse != null && reuse.sha256.isNotEmpty() && reuse.sha256 != sha) { cancelled.add(id); fail(id, "The file changed — send it again as a new transfer"); return@Runnable }
            val recipient = keys.pinned(peer)
            if (!link.verified(peer) || recipient == null) { fail(id, "${link.name(peer)} is not connected with a verified key"); return@Runnable }
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val o = DropProtocol.offer(id, me, myName, peer, source.name, source.mime, source.size, sha, clock(), key, recipient, keys.private)
            fileKeys[id] = key
            update(id) { it.copy(sha256 = sha, state = State.WAITING, error = "") }
            if (!link.control(peer, DropProtocol.encode(o.fields()))) { fail(id, "Nearby link unavailable"); return@Runnable }
            later(DropProtocol.OFFER_LIFE, Runnable { fail(id, "No answer from ${link.name(peer)}", setOf(State.WAITING)) })
        } catch (_: IOException) {
            if (id in cancelled) update(id) { it.copy(state = State.CANCELED, speed = 0) } else fail(id, "Could not read the file")
        }
    })

    fun retry(id: String) {
        val t = store.get(id) ?: return
        if (t.retryable) offer(t.peer, t.source, t.id)
    }

    private fun startStream(t: Transfer, offset: Long) {
        val key = fileKeys[t.id] ?: return
        val source = files.source(t.source) ?: run { fail(t.id, "File can no longer be opened"); return }
        val meter = DropProtocol.SpeedMeter().also { meters[t.id] = it }
        var sent = offset
        val input = try { source.open().also { skipFully(it, offset) } } catch (_: IOException) { fail(t.id, "Could not read the file"); return }
        meter.add(clock(), sent)
        update(t.id) { it.copy(state = State.SENDING, bytes = offset, accepted = true, error = "") }
        val stream = DropProtocol.EncryptingStream(input, key, t.id, t.size, offset / CHUNK, { t.id in cancelled }) { n ->
            sent += n; meter.add(clock(), sent); progress(t.id, sent, meter, if (sent >= t.size) State.VERIFYING else null)
        }
        val payload = link.stream(t.peer, stream) ?: run { input.close(); fail(t.id, "Nearby link unavailable"); return }
        payloads[t.id] = payload
        send(t.peer, "t" to "data", "id" to t.id, "payload" to "$payload", "offset" to "$offset")
    }

    // ---------- receiver ----------

    fun accept(id: String) { synchronized(lock) {
        val t = store.get(id) ?: return
        if (t.outgoing || t.state != State.INCOMING || !link.verified(t.peer)) return
        val offset = resumeOffset(t)
        put(t.copy(state = State.RECEIVING, accepted = true, bytes = offset, updated = clock()))
        send(t.peer, "t" to "accept", "id" to id, "offset" to "$offset")
    } }

    fun decline(id: String) { synchronized(lock) {
        val t = store.get(id) ?: return
        if (t.outgoing || t.state != State.INCOMING) return
        fileKeys.remove(id); put(t.copy(state = State.DECLINED, updated = clock()))
        send(t.peer, "t" to "decline", "id" to id)
    } }

    /** Bytes already verified on this phone for [t]; always a whole number of chunks. */
    private fun resumeOffset(t: Transfer): Long {
        val partial = files.partial(t.id)
        if (!t.accepted || !partial.exists()) { partial.delete(); return 0 }
        return minOf(partial.length(), t.size) / CHUNK * CHUNK
    }

    private fun receive(id: String, peer: String, input: InputStream, offset: Long) = io(Runnable {
        val t = store.get(id) ?: return@Runnable
        val key = fileKeys[id] ?: return@Runnable
        if (t.outgoing || t.peer != peer || t.state != State.RECEIVING) { input.close(); return@Runnable }
        val partial = files.partial(id).also { it.parentFile?.mkdirs() }
        val meter = DropProtocol.SpeedMeter().also { meters[id] = it }
        try {
            RandomAccessFile(partial, "rw").use { out ->
                out.setLength(offset); out.seek(offset)   // drops any unverified tail from an interrupted attempt
                var written = offset; meter.add(clock(), written)
                for (index in offset / CHUNK until DropProtocol.chunks(t.size)) {
                    if (id in cancelled) throw IOException("Cancelled")
                    val chunk = DropProtocol.readChunk(input, key, id, t.size, index)
                    out.write(chunk); written += chunk.size
                    meter.add(clock(), written); progress(id, written, meter, null)
                }
            }
            input.close()
            update(id) { it.copy(state = State.VERIFYING, bytes = t.size, speed = 0) }
            if (DropProtocol.sha256(partial.inputStream()) != t.sha256) throw DropProtocol.IntegrityException("File checksum does not match")
            val saved = files.publish(partial, t.name, t.mime) ?: throw IOException("Could not save the file")
            partial.delete(); fileKeys.remove(id)
            update(id) { it.copy(state = State.COMPLETED, saved = saved, error = "") }
            send(peer, "t" to "done", "id" to id, "ok" to "1")
        } catch (e: DropProtocol.IntegrityException) {
            runCatching { input.close() }; partial.delete()
            fail(id, "Integrity check failed — nothing was saved"); send(peer, "t" to "done", "id" to id, "ok" to "0", "reason" to "integrity")
        } catch (_: IOException) {
            runCatching { input.close() }
            if (id in cancelled) { partial.delete(); update(id) { it.copy(state = State.CANCELED, speed = 0) } }
            else fail(id, "Connection interrupted — the sender can resume")
        }
    })

    // ---------- shared ----------

    fun cancel(id: String) {
        val t = store.get(id) ?: return
        if (t.state == State.INCOMING) { decline(id); return }
        if (!t.active) return
        cancelled.add(id)
        payloads.remove(id)?.let(link::cancel)
        send(t.peer, "t" to "cancel", "id" to id)
        if (t.state != State.PREPARING && t.state != State.RECEIVING) update(id) { it.copy(state = State.CANCELED, speed = 0) }
        if (!t.outgoing) { fileKeys.remove(id); files.partial(id).delete(); update(id) { it.copy(state = State.CANCELED, speed = 0) } }
    }

    fun onControl(peer: String, raw: String) {
        val f = DropProtocol.decode(raw) ?: return
        val id = f["id"]?.takeIf(DropProtocol::validId) ?: return
        if (!link.verified(peer)) return
        when (f["t"]) {
            "offer" -> onOffer(peer, f)
            "accept" -> {
                val offset = f["offset"]?.toLongOrNull() ?: return
                val t = synchronized(lock) { store.get(id)?.takeIf { it.outgoing && it.peer == peer && it.state == State.WAITING } } ?: return
                if (offset < 0 || offset % CHUNK != 0L || offset >= t.size) { fail(id, "Receiver asked for an invalid resume point"); return }
                io(Runnable { startStream(t, offset) })
            }
            "decline" -> update(id) { t -> if (t.outgoing && t.peer == peer && t.state == State.WAITING) t.copy(state = State.DECLINED, error = "${t.peerName} declined") else null }
            "data" -> {
                val payload = f["payload"]?.toLongOrNull() ?: return
                val offset = f["offset"]?.toLongOrNull() ?: return
                val t = store.get(id)?.takeIf { !it.outgoing && it.peer == peer && it.state == State.RECEIVING } ?: return
                if (offset != t.bytes) { fail(id, "Sender resumed from the wrong position"); return }
                val arrived = incomingStreams.remove(payload)
                if (arrived != null && arrived.first == peer) receive(id, peer, arrived.second, offset) else awaitingStream[payload] = id to offset
            }
            "cancel" -> {
                val t = store.get(id)?.takeIf { it.peer == peer && (it.active || it.state == State.INCOMING) } ?: return
                cancelled.add(id); payloads.remove(id)?.let(link::cancel)
                if (!t.outgoing) { fileKeys.remove(id); if (t.state != State.RECEIVING) files.partial(id).delete() }
                if (t.outgoing || t.state != State.RECEIVING) update(id) { it.copy(state = State.CANCELED, speed = 0, error = "Cancelled by ${t.peerName}") }
            }
            "done" -> update(id) { t ->
                if (!t.outgoing || t.peer != peer || t.state !in setOf(State.WAITING, State.SENDING, State.VERIFYING)) null
                else if (f["ok"] == "1") t.copy(state = State.COMPLETED, bytes = t.size, speed = 0, error = "").also { payloads.remove(id); fileKeys.remove(id) }
                else if (t.state == State.WAITING) null
                else t.copy(state = State.FAILED, speed = 0, error = if (f["reason"] == "integrity") "Receiver rejected corrupted data — retry" else "Receiver could not save the file")
            }
        }
    }

    private fun onOffer(peer: String, f: Map<String, String>) {
        val senderKey = keys.pinned(peer) ?: return
        val o = DropProtocol.parseOffer(f, me, peer, senderKey) ?: return
        val key = DropProtocol.fileKey(o, keys.private) ?: return
        val now = clock()
        synchronized(lock) {
            val known = store.get(o.id)
            if (known != null && (known.outgoing || known.peer != peer)) return
            if (known?.state == State.COMPLETED && known.sha256 == o.sha256) { send(peer, "t" to "done", "id" to o.id, "ok" to "1"); return }
            if (known != null && (known.active || known.state == State.INCOMING)) return
            fileKeys[o.id] = key
            cancelled.remove(o.id)
            val t = Transfer(o.id, false, peer, o.fromName.ifBlank { link.name(peer) }, o.name, o.mime, o.size, o.sha256, State.INCOMING,
                started = known?.started ?: now, updated = now,
                accepted = known?.accepted == true && known.sha256 == o.sha256 && known.state == State.FAILED)
            // A file this user already accepted resumes without asking again; anything else waits for approval.
            if (t.accepted) { put(t, notify = false); accept(o.id); return }
            files.partial(o.id).delete()
            put(t); events.incoming(t)
            later(DropProtocol.OFFER_LIFE, Runnable { update(o.id) { if (it.state == State.INCOMING) it.copy(state = State.FAILED, error = "Offer expired") else null } })
        }
    }

    /** A Nearby stream arrived; it is used only once the matching "data" message names it. */
    fun onStream(peer: String, payload: Long, input: InputStream) {
        val waiting = awaitingStream.remove(payload)
        if (waiting != null) receive(waiting.first, peer, input, waiting.second) else incomingStreams[payload] = peer to input
    }

    fun onPayloadFailed(payload: Long) {
        payloads.entries.firstOrNull { it.value == payload }?.let { (id, _) -> payloads.remove(id); fail(id, "Connection interrupted — tap Retry to resume", setOf(State.SENDING, State.VERIFYING)) }
    }

    fun onPeerLost(peer: String) {
        incomingStreams.entries.filter { it.value.first == peer }.forEach { (payload, stream) -> incomingStreams.remove(payload); runCatching { stream.second.close() } }
        store.let { s -> payloads.keys.mapNotNull(s::get).filter { it.peer == peer }.forEach { fail(it.id, "Connection lost — tap Retry to resume") } }
    }

    private fun progress(id: String, bytes: Long, meter: DropProtocol.SpeedMeter, next: State?) {
        val now = clock()
        if (next == null && now - (lastPublished[id] ?: 0) < 250) return
        lastPublished[id] = now
        update(id) { t -> if (!t.active) null else t.copy(bytes = bytes, speed = meter.speed(), state = next ?: t.state) }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val buffer = ByteArray(CHUNK)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) { left -= skipped; continue }
            val n = input.read(buffer, 0, minOf(left, CHUNK.toLong()).toInt()); if (n < 0) throw IOException("File is shorter than expected"); left -= n
        }
    }
}
