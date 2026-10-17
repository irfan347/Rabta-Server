package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import kotlin.random.Random

class DropProtocolTest {
    private fun rsa() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val key = ByteArray(32) { it.toByte() }
    private val id = "0123456789abcdef0123456789abcdef"

    private fun encrypt(bytes: ByteArray, start: Long = 0) = ByteArrayOutputStream().also { out ->
        DropProtocol.EncryptingStream(ByteArrayInputStream(bytes, (start * DropProtocol.CHUNK).toInt(), bytes.size), key, id, bytes.size.toLong(), start, { false }) {}.copyTo(out)
    }.toByteArray()

    @Test fun framesRoundTripAndResumeAtAChunk() {
        val bytes = Random(1).nextBytes(3 * DropProtocol.CHUNK + 10)
        val wire = ByteArrayInputStream(encrypt(bytes))
        val out = ByteArrayOutputStream()
        for (i in 0 until DropProtocol.chunks(bytes.size.toLong())) out.write(DropProtocol.readChunk(wire, key, id, bytes.size.toLong(), i))
        assertArrayEquals(bytes, out.toByteArray())
        val resumed = ByteArrayInputStream(encrypt(bytes, 2))
        assertArrayEquals(bytes.copyOfRange(2 * DropProtocol.CHUNK, 3 * DropProtocol.CHUNK), DropProtocol.readChunk(resumed, key, id, bytes.size.toLong(), 2))
    }

    @Test fun reorderedTruncatedOrForeignFramesFail() {
        val bytes = Random(2).nextBytes(2 * DropProtocol.CHUNK)
        val wire = encrypt(bytes)
        assertThrows(DropProtocol.IntegrityException::class.java) { DropProtocol.readChunk(ByteArrayInputStream(wire), key, id, bytes.size.toLong(), 1) }
        assertThrows(DropProtocol.IntegrityException::class.java) { DropProtocol.readChunk(ByteArrayInputStream(wire), key, "f".repeat(32), bytes.size.toLong(), 0) }
        // Claiming the file is longer turns chunk 1 into a non-final chunk, whose authenticated data differs.
        val first = DropProtocol.CHUNK + 20
        assertThrows(DropProtocol.IntegrityException::class.java) { DropProtocol.readChunk(ByteArrayInputStream(wire, first, wire.size), key, id, 3L * DropProtocol.CHUNK, 1) }
        assertThrows(java.io.IOException::class.java) { DropProtocol.readChunk(ByteArrayInputStream(wire, 0, 100), key, id, bytes.size.toLong(), 0) }
    }

    @Test fun offerIsSignedAndOnlyTheRecipientCanOpenItsKey() {
        val sender = rsa(); val recipient = rsa(); val other = rsa()
        val offer = DropProtocol.offer(id, "A11CE0000001", "Alice", "B11A10000002", "../report.pdf", "application/pdf", 10, "a".repeat(64), 1, key, recipient.public, sender.private)
        assertEquals("report.pdf", offer.name)
        val wire = DropProtocol.decode(DropProtocol.encode(offer.fields()))!!
        val parsed = DropProtocol.parseOffer(wire, "B11A10000002", "A11CE0000001", sender.public)!!
        assertArrayEquals(key, DropProtocol.fileKey(parsed, recipient.private))
        assertNull("Other phones cannot unwrap the file key", DropProtocol.fileKey(parsed, other.private))
        assertNull("Wrong sender key", DropProtocol.parseOffer(wire, "B11A10000002", "A11CE0000001", other.public))
        assertNull("Addressed to someone else", DropProtocol.parseOffer(wire, "C00000000003", "A11CE0000001", sender.public))
        assertNull("Changed size breaks the signature", DropProtocol.parseOffer(wire + ("size" to "11"), "B11A10000002", "A11CE0000001", sender.public))
        assertNull("Changed name breaks the signature", DropProtocol.parseOffer(wire + ("name" to "x.pdf"), "B11A10000002", "A11CE0000001", sender.public))
    }

    @Test fun typesNamesAndCodecAreSafe() {
        assertTrue(DropProtocol.allowed("image/jpeg")); assertTrue(DropProtocol.allowed("video/mp4")); assertTrue(DropProtocol.allowed("application/pdf"))
        assertTrue(DropProtocol.allowed("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertFalse(DropProtocol.allowed("application/vnd.android.package-archive")); assertFalse(DropProtocol.allowed("text/html"))
        assertEquals("passwd", DropProtocol.safeName("../../etc/passwd")); assertEquals("a.pdf", DropProtocol.safeName("C:\\x\\a.pdf"))
        assertFalse(DropProtocol.safeName("a/b\u0000c").contains('/')); assertEquals("file", DropProtocol.safeName("..."))
        assertEquals(mapOf("a" to "x&y=z", "b" to "ü"), DropProtocol.decode(DropProtocol.encode(mapOf("a" to "x&y=z", "b" to "ü"))))
        assertNull(DropProtocol.decode("broken"))
        assertEquals("PDF", DropProtocol.kind("application/pdf")); assertEquals("Video", DropProtocol.kind("video/mp4"))
    }

    @Test fun speedIsMeasuredFromRecentSamples() {
        val meter = DropProtocol.SpeedMeter()
        assertEquals(0, meter.speed())
        meter.add(1_000, 0); meter.add(2_000, 2_000_000)
        assertEquals(2_000_000, meter.speed())
        meter.add(10_000, 2_000_000); meter.add(11_000, 2_500_000)
        assertEquals("Old samples drop out of the window", 500_000, meter.speed())
    }

    @Test fun hashIsStreamed() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DropProtocol.sha256(ByteArrayInputStream("abc".toByteArray())))
    }
}
