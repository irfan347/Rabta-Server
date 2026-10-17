package com.irfan.rabta

import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Rabta Drop wire format. Pure JVM code: no Android or JSON dependency, so it is unit-tested directly.
 *
 * Control messages travel as Nearby BYTES payloads prefixed 'D' between two directly connected, key-verified
 * phones. File bytes travel as one Nearby STREAM payload of framed chunks: [u32 length][AES-256-GCM ciphertext].
 * Every chunk has its own nonce (chunk index) and authenticated data (transfer, index, last-chunk flag), so
 * reordering, truncation and tampering fail; the whole plaintext is also checked against the signed SHA-256.
 */
object DropProtocol {
    const val VERSION = 1
    const val CHUNK = 64 * 1024
    const val MAX_SIZE = 4L * 1024 * 1024 * 1024
    const val OFFER_LIFE = 2 * 60_000L
    private const val TAG_BYTES = 16
    private val HEX = Regex("^[0-9a-f]+$")

    private val documents = setOf(
        "application/pdf", "text/plain", "text/csv", "application/rtf",
        "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
    )
    /** MIME types offered in the picker; anything else is refused on both sides. */
    val pickerTypes = arrayOf("image/*", "video/*") + documents.toTypedArray()
    fun allowed(mime: String) = mime.length <= 100 && (mime.matches(Regex("^(image|video)/[a-z0-9.+-]+$")) || mime in documents)
    fun kind(mime: String) = when {
        mime.startsWith("image/") -> "Photo"
        mime.startsWith("video/") -> "Video"
        mime == "application/pdf" -> "PDF"
        else -> "Document"
    }

    /** Keeps a readable file name without path separators, control characters or a hidden-file dot. */
    fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.').take(120)
        return clean.ifBlank { "file" }
    }
    fun chunks(size: Long) = (size + CHUNK - 1) / CHUNK
    fun newId(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    fun validId(id: String) = id.length == 32 && id.matches(HEX)

    // ---------- control messages: URL-encoded key=value pairs ----------
    fun encode(fields: Map<String, String>) = fields.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
    fun decode(raw: String): Map<String, String>? = runCatching {
        require(raw.length <= 16_000)
        raw.split("&").associate { pair ->
            val i = pair.indexOf('='); require(i > 0)
            URLDecoder.decode(pair.substring(0, i), "UTF-8") to URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }
    }.getOrNull()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- signed offer ----------
    data class Offer(
        val id: String, val from: String, val fromName: String, val to: String, val name: String, val mime: String,
        val size: Long, val sha256: String, val sent: Long, val box: MeshCrypto.Box, val sig: String = "",
    ) {
        fun signed() = listOf(VERSION, id, from, fromName, to, name, mime, size, sha256, sent, box.key, box.iv, box.ciphertext).joinToString("\n") { enc(it.toString()) }
        fun fields() = mapOf("t" to "offer", "v" to "$VERSION", "id" to id, "from" to from, "fromName" to fromName, "to" to to,
            "name" to name, "mime" to mime, "size" to "$size", "sha" to sha256, "sent" to "$sent",
            "ek" to box.key, "iv" to box.iv, "ct" to box.ciphertext, "sig" to sig)
    }
    fun aad(id: String) = "rabta-drop:$id"

    /** Seals a fresh 256-bit file key for [recipient] and signs the whole offer with the sender's key. */
    fun offer(id: String, from: String, fromName: String, to: String, name: String, mime: String, size: Long, sha256: String,
              sent: Long, fileKey: ByteArray, recipient: PublicKey, signer: PrivateKey): Offer {
        val unsigned = Offer(id, from, fromName.take(40), to, safeName(name), mime, size, sha256, sent, MeshCrypto.seal(recipient, MeshCrypto.b64(fileKey), aad(id)))
        return unsigned.copy(sig = MeshCrypto.sign(signer, unsigned.signed()))
    }

    /** Parses and validates an offer addressed to [me] from [sender]; null if anything is wrong. */
    fun parseOffer(f: Map<String, String>, me: String, sender: String, senderKey: PublicKey): Offer? = runCatching {
        require(f["t"] == "offer" && f["v"] == "$VERSION")
        val o = Offer(f.getValue("id"), f.getValue("from"), f.getValue("fromName").take(40), f.getValue("to"), f.getValue("name"),
            f.getValue("mime"), f.getValue("size").toLong(), f.getValue("sha"), f.getValue("sent").toLong(),
            MeshCrypto.Box(f.getValue("ek"), f.getValue("iv"), f.getValue("ct")), f.getValue("sig"))
        require(validId(o.id) && o.from == sender && o.to == me && o.name == safeName(o.name) && allowed(o.mime))
        require(o.size in 1..MAX_SIZE && o.sha256.length == 64 && o.sha256.matches(HEX))
        require(MeshCrypto.verify(senderKey, o.signed(), o.sig))
        o
    }.getOrNull()

    /** The file key, which only the intended recipient can unwrap. */
    fun fileKey(o: Offer, me: PrivateKey): ByteArray? = runCatching {
        MeshCrypto.bytes(MeshCrypto.open(me, o.box, aad(o.id))).also { require(it.size == 32) }
    }.getOrNull()

    // ---------- integrity ----------
    fun sha256(input: InputStream, cancelled: () -> Boolean = { false }): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(CHUNK)
        input.use { while (true) { if (cancelled()) throw IOException("Cancelled"); val n = it.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------- chunk frames ----------
    private fun cipher(mode: Int, key: ByteArray, id: String, index: Long, last: Boolean) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(0).putLong(index).array()))
        updateAAD("$id:$index:${if (last) 1 else 0}".toByteArray())
    }
    fun plainLength(size: Long, index: Long) = minOf(CHUNK.toLong(), size - index * CHUNK).toInt()

    /**
     * Reads the source lazily and yields encrypted frames from [startChunk]; one chunk in memory at a time.
     * [onPlain] reports plaintext bytes consumed by the transport, which is the sender's measured progress.
     */
    class EncryptingStream(
        private val source: InputStream, private val key: ByteArray, private val id: String, private val size: Long,
        startChunk: Long, private val cancelled: () -> Boolean, private val onPlain: (Int) -> Unit,
    ) : InputStream() {
        private val total = chunks(size)
        private var index = startChunk
        private var frame = ByteArray(0)
        private var position = 0
        private val plain = ByteArray(CHUNK)
        private fun next(): Boolean {
            if (cancelled()) throw IOException("Cancelled")
            if (index >= total) return false
            val length = plainLength(size, index)
            readFully(source, plain, length)
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, id, index, index == total - 1).doFinal(plain, 0, length)
            frame = ByteBuffer.allocate(4 + sealed.size).putInt(sealed.size).put(sealed).array(); position = 0
            index++; onPlain(length); return true
        }
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (position >= frame.size && !next()) return -1
            val n = minOf(len, frame.size - position)
            System.arraycopy(frame, position, b, off, n); position += n; return n
        }
        override fun close() = source.close()
    }

    class IntegrityException(message: String) : IOException(message)

    /** Reads, authenticates and decrypts chunk [index]; throws [IntegrityException] on any mismatch. */
    fun readChunk(input: InputStream, key: ByteArray, id: String, size: Long, index: Long): ByteArray {
        val header = ByteArray(4); readFully(input, header, 4)
        val length = ByteBuffer.wrap(header).int
        val expected = plainLength(size, index)
        if (length != expected + TAG_BYTES) throw IntegrityException("Unexpected chunk length")
        val sealed = ByteArray(length); readFully(input, sealed, length)
        return try { cipher(Cipher.DECRYPT_MODE, key, id, index, index == chunks(size) - 1).doFinal(sealed) }
        catch (_: AEADBadTagException) { throw IntegrityException("Chunk failed authentication") }
    }

    fun readFully(input: InputStream, into: ByteArray, length: Int) {
        var read = 0
        while (read < length) { val n = input.read(into, read, length - read); if (n < 0) throw IOException("Connection interrupted"); read += n }
    }

    /** Measured throughput over the last few seconds. */
    class SpeedMeter(private val window: Long = 3_000) {
        private val samples = ArrayDeque<Pair<Long, Long>>()
        @Synchronized fun add(now: Long, totalBytes: Long) {
            samples.addLast(now to totalBytes)
            while (samples.size > 2 && now - samples.first().first > window) samples.removeFirst()
        }
        /** Bytes per second, or 0 until at least two samples span some time. */
        @Synchronized fun speed(): Long {
            if (samples.size < 2) return 0
            val (t0, b0) = samples.first(); val (t1, b1) = samples.last()
            return if (t1 > t0) (b1 - b0) * 1000 / (t1 - t0) else 0
        }
    }
}
