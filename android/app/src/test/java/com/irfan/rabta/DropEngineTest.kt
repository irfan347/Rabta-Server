package com.irfan.rabta

import com.irfan.rabta.DropEngine.State
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Two Drop engines joined by an in-memory link: real RSA keys, AES-GCM frames, files and threads; no radios. */
class DropEngineTest {
    private val pool = Executors.newCachedThreadPool()
    private val dir = Files.createTempDirectory("drop-test").toFile()
    private val payloadIds = AtomicLong(100)
    private val controls = Collections.synchronizedList(mutableListOf<String>())
    private fun rsa() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** What the in-memory link does to a stream: pass, flip a byte, or cut it after some bytes. */
    @Volatile private var mangle: (InputStream) -> InputStream = { it }
    @Volatile private var connected = true

    inner class Phone(val id: String, val name: String) {
        val pair = rsa()
        val records = ConcurrentHashMap<String, DropEngine.Transfer>()
        val incoming = Collections.synchronizedList(mutableListOf<DropEngine.Transfer>())
        val sources = ConcurrentHashMap<String, ByteArray>()
        val published = ConcurrentHashMap<String, ByteArray>()
        val timers = Collections.synchronizedList(mutableListOf<Runnable>())
        val streams = ConcurrentHashMap<Long, InputStream>()
        lateinit var other: Phone
        lateinit var engine: DropEngine
        fun start(otherKey: () -> PublicKey) {
            engine = DropEngine(id, name,
                object : DropEngine.Link {
                    override fun verified(peer: String) = connected && peer == other.id
                    override fun name(peer: String) = other.name
                    override fun control(peer: String, message: String): Boolean {
                        if (!connected) return false
                        controls += "$id>${message.substringBefore('&')}"
                        pool.execute { other.engine.onControl(id, message) }; return true
                    }
                    override fun stream(peer: String, data: InputStream): Long? {
                        if (!connected) return null
                        val payload = payloadIds.incrementAndGet(); val wire = mangle(data); streams[payload] = wire
                        pool.execute { other.engine.onStream(id, payload, wire) }; return payload
                    }
                    override fun cancel(payloadId: Long) { streams.remove(payloadId)?.close() }
                },
                object : DropEngine.Files {
                    override fun source(uri: String) = sources[uri]?.let { bytes -> DropEngine.Source("$uri.pdf", "application/pdf", bytes.size.toLong()) { ByteArrayInputStream(bytes) } }
                    override fun partial(id: String) = File(dir, "${this@Phone.id}-$id.part")
                    override fun publish(partial: File, name: String, mime: String): String { published[name] = partial.readBytes(); return "saved://$name" }
                },
                object : DropEngine.Store {
                    override fun save(t: DropEngine.Transfer) { records[t.id] = t }
                    override fun get(id: String) = records[id]
                },
                object : DropEngine.Keys {
                    override val private = pair.private
                    override fun pinned(peer: String) = if (peer == other.id) otherKey() else null
                },
                object : DropEngine.Listener {
                    override fun changed(t: DropEngine.Transfer) {}
                    override fun incoming(t: DropEngine.Transfer) { incoming += t }
                },
                io = { pool.execute(it) }, later = { _, r -> timers += r })
        }
        fun only() = records.values.single()
    }

    private val alice = Phone("A11CE0000001", "Alice")
    private val bilal = Phone("B11A10000002", "Bilal")
    init {
        alice.other = bilal; bilal.other = alice
        alice.start { bilal.pair.public }; bilal.start { alice.pair.public }
    }
    @After fun tearDown() { pool.shutdownNow(); dir.deleteRecursively() }

    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(what, check())
    }
    private fun file(size: Int) = Random(size).nextBytes(size).also { alice.sources["doc"] = it }
    private fun offered(): DropEngine.Transfer {
        alice.engine.offer(bilal.id, "doc")
        until("Receiver is asked") { bilal.incoming.isNotEmpty() }
        return bilal.incoming.last()
    }

    @Test fun nothingIsReceivedUntilTheUserAccepts() {
        val bytes = file(5 * DropProtocol.CHUNK + 1234)
        val offer = offered()
        assertEquals(State.INCOMING, bilal.only().state)
        assertEquals(State.WAITING, alice.only().state)
        Thread.sleep(200)
        assertTrue("No stream before approval", alice.streams.isEmpty())
        assertFalse("No partial file before approval", File(dir, "${bilal.id}-${offer.id}.part").exists())
        assertEquals("doc.pdf", offer.name); assertEquals(bytes.size.toLong(), offer.size)

        bilal.engine.accept(offer.id)
        until("Receiver completes") { bilal.only().state == State.COMPLETED }
        until("Sender learns it was verified") { alice.only().state == State.COMPLETED }
        assertArrayEquals(bytes, bilal.published.getValue("doc.pdf"))
        assertEquals("saved://doc.pdf", bilal.only().saved)
        assertFalse("Partial file removed after publishing", File(dir, "${bilal.id}-${offer.id}.part").exists())
    }

    @Test fun declinedOfferSendsNothing() {
        file(1000); val offer = offered()
        bilal.engine.decline(offer.id)
        until("Sender sees the decline") { alice.only().state == State.DECLINED }
        assertTrue(alice.streams.isEmpty()); assertTrue(bilal.published.isEmpty())
        assertTrue("Declined transfers can be offered again", alice.only().retryable)
    }

    @Test fun tamperedDataIsRejectedAndNothingIsSaved() {
        file(3 * DropProtocol.CHUNK)
        mangle = { input -> object : FilterInputStream(input) {
            var position = 0L
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                for (i in 0 until maxOf(n, 0)) if (position + i == DropProtocol.CHUNK + 100L) b[off + i] = (b[off + i].toInt() xor 1).toByte()
                if (n > 0) position += n; return n
            }
        } }
        bilal.engine.accept(offered().id)
        until("Receiver rejects") { bilal.only().state == State.FAILED }
        assertTrue(bilal.only().error.contains("Integrity"))
        assertTrue(bilal.published.isEmpty())
        until("Sender is told") { alice.only().state == State.FAILED }
    }

    @Test fun interruptedTransferResumesFromVerifiedChunksWithoutAskingAgain() {
        val bytes = file(6 * DropProtocol.CHUNK + 777)
        val cut = 3L * (DropProtocol.CHUNK + 20) + 500
        mangle = { input -> object : FilterInputStream(input) {
            var position = 0L
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= cut) throw IOException("radio lost")
                val n = super.read(b, off, minOf(len.toLong(), cut - position).toInt()); if (n > 0) position += n; return n
            }
        } }
        val offer = offered(); bilal.engine.accept(offer.id)
        until("Receiver keeps the verified prefix") { bilal.only().state == State.FAILED }
        assertEquals(3L * DropProtocol.CHUNK, File(dir, "${bilal.id}-${offer.id}.part").length())
        alice.engine.onPayloadFailed(alice.streams.keys.single())
        until("Sender can retry") { alice.only().retryable }

        mangle = { it }
        alice.engine.retry(offer.id)
        until("Resumed transfer completes") { bilal.only().state == State.COMPLETED && alice.only().state == State.COMPLETED }
        assertEquals("Approval is not requested again for the same accepted file", 1, bilal.incoming.size)
        assertTrue("Resumed after the verified chunks", controls.any { it.startsWith("B11A10000002>t=accept") } && controls.count { it.startsWith("A11CE0000001>t=offer") } == 2)
        assertArrayEquals(bytes, bilal.published.getValue("doc.pdf"))
    }

    @Test fun senderCancelStopsBothSides() {
        file(40 * DropProtocol.CHUNK)
        mangle = { input -> object : FilterInputStream(input) { override fun read(b: ByteArray, off: Int, len: Int): Int { Thread.sleep(20); return super.read(b, off, len) } } }
        val offer = offered(); bilal.engine.accept(offer.id)
        until("Bytes are moving") { bilal.only().bytes > 0 }
        alice.engine.cancel(offer.id)
        until("Both sides cancelled") { alice.only().state == State.CANCELED && bilal.only().state == State.CANCELED }
        assertTrue(bilal.published.isEmpty())
        assertFalse(File(dir, "${bilal.id}-${offer.id}.part").exists())
    }

    @Test fun receiverCancelStopsTheSender() {
        file(40 * DropProtocol.CHUNK)
        mangle = { input -> object : FilterInputStream(input) { override fun read(b: ByteArray, off: Int, len: Int): Int { Thread.sleep(20); return super.read(b, off, len) } } }
        val offer = offered(); bilal.engine.accept(offer.id)
        until("Bytes are moving") { bilal.only().bytes > 0 }
        bilal.engine.cancel(offer.id)
        until("Both sides cancelled") { alice.only().state == State.CANCELED && bilal.only().state == State.CANCELED }
        assertTrue(bilal.published.isEmpty())
    }

    @Test fun unverifiedOrForgedOffersAreIgnored() {
        file(1000)
        connected = false
        alice.engine.offer(bilal.id, "doc")
        until("Sender fails without a verified link") { alice.records.values.any { it.state == State.FAILED } }
        connected = true
        val stranger = rsa()
        alice.start { bilal.pair.public }
        bilal.start { stranger.public }   // Bilal has a different key pinned for Alice: signature cannot verify
        alice.engine.offer(bilal.id, "doc")
        until("Sender is waiting") { alice.records.values.any { it.state == State.WAITING } }
        Thread.sleep(300)
        assertTrue("A forged or unpinned sender is never shown", bilal.incoming.isEmpty())
    }

    @Test fun unknownTypesAndChangedFilesAreRefused() {
        alice.sources["doc"] = ByteArray(10)
        val refused = object : DropEngine.Files {
            override fun source(uri: String) = DropEngine.Source("run.sh", "application/x-sh", 10) { ByteArrayInputStream(ByteArray(10)) }
            override fun partial(id: String) = File(dir, id)
            override fun publish(partial: File, name: String, mime: String) = null
        }
        val records = ConcurrentHashMap<String, DropEngine.Transfer>()
        val engine = DropEngine(alice.id, "Alice", object : DropEngine.Link {
            override fun verified(peer: String) = true; override fun name(peer: String) = "Bilal"
            override fun control(peer: String, message: String) = true; override fun stream(peer: String, data: InputStream) = 1L
            override fun cancel(payloadId: Long) {}
        }, refused, object : DropEngine.Store { override fun save(t: DropEngine.Transfer) { records[t.id] = t }; override fun get(id: String) = records[id] },
            object : DropEngine.Keys { override val private = alice.pair.private; override fun pinned(peer: String) = bilal.pair.public },
            object : DropEngine.Listener { override fun changed(t: DropEngine.Transfer) {} }, io = { it.run() }, later = { _, _ -> })
        engine.offer(bilal.id, "x")
        assertEquals(State.FAILED, records.values.single().state)
    }
}
