package com.irfan.rabta

import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The server keeps a file only until the phones it was sent to have saved it. Here a local mock server plays
 * features.js for a fixture account; never production users or servers.
 */
@RunWith(AndroidJUnit4::class)
class ServerCopiesTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private fun ui(block: () -> Unit) = inst.runOnMainSync(block)
    private fun waitFor(message: String, test: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) { if (test()) return; Thread.sleep(50) }
        fail(message)
    }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun hex(n: Int) = (1..n).map { "0123456789abcdef".random() }.joinToString("")
    /** Bytes no other file on the test phone has. */
    private fun unique(what: String) = "$what ${java.util.UUID.randomUUID()}".toByteArray()
    private fun received(remote: String, bytes: ByteArray, name: String = "note.txt") =
        JSONObject().put("remote", remote).put("name", name).put("mime", "text/plain").put("size", bytes.size).put("sha256", sha(bytes)).toString()

    /** Files, the "saved it" notes and resumable uploads, as features.js answers them. */
    private class FileServer : Dispatcher() {
        val files = ConcurrentHashMap<String, ByteArray>()
        val downloads = AtomicInteger()
        val got = CopyOnWriteArrayList<String>()
        val uploads = ConcurrentHashMap<String, ByteArrayOutputStream>()
        val info = ConcurrentHashMap<String, JSONObject>()
        override fun dispatch(r: RecordedRequest): MockResponse {
            val path = r.path.orEmpty()
            Regex("/api/files/([a-f0-9]{32})/got").matchEntire(path)?.let { got.add(it.groupValues[1]); return ok(JSONObject().put("ok", true)) }
            Regex("/api/files/([a-f0-9]{32})").matchEntire(path)?.let { m ->
                val bytes = files[m.groupValues[1]] ?: return MockResponse().setResponseCode(400).setBody("{\"error\":\"File unavailable\"}")
                downloads.incrementAndGet(); return MockResponse().setBody(okio.Buffer().write(bytes))
            }
            if (r.method == "POST" && path == "/api/uploads") {
                val id = (1..32).map { "0123456789abcdef".random() }.joinToString(""); val o = JSONObject(r.body.readUtf8())
                uploads[id] = ByteArrayOutputStream(); info[id] = o
                return ok(JSONObject().put("id", id).put("received", 0).put("done", false).put("chunkSize", 128 * 1024))
            }
            Regex("/api/uploads/([a-f0-9]{32})/chunks").matchEntire(path)?.let { m ->
                val out = uploads[m.groupValues[1]]!!; out.write(Base64.decode(JSONObject(r.body.readUtf8()).getString("data"), Base64.DEFAULT))
                return ok(JSONObject().put("received", out.size()))
            }
            Regex("/api/uploads/([a-f0-9]{32})/complete").matchEntire(path)?.let { m ->
                val id = m.groupValues[1]; val o = info[id]!!; val bytes = uploads[id]!!.toByteArray(); files[id] = bytes
                return ok(JSONObject().put("remote", id).put("name", o.optString("name")).put("mime", o.optString("mime")).put("size", bytes.size)
                    .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }))
            }
            return MockResponse().setResponseCode(400).setBody("{\"error\":\"Upload not found\"}")
        }
        private fun ok(o: JSONObject) = MockResponse().setBody(o.toString())
    }

    private fun fixture(run: (FileServer) -> Unit) {
        Session.init(ctx)
        val old = listOf(Session.id, Session.name, Session.token, Session.serverUrl)
        val files = FileServer(); val server = MockWebServer().apply { dispatcher = files; start() }
        val account = (1..12).map { "0123456789ABCDEF".random() }.joinToString("")
        val url = server.url("/").toString().trimEnd('/')   // read off the main thread
        ui { MessageService.stop(ctx); WsClient.disconnect(); Session.id = account; Session.name = "Copies fixture"; Session.token = "fixture"; Session.serverUrl = url }
        val before = java.io.File(ctx.filesDir, "attachments").list()?.toSet().orEmpty()
        try { run(files) } finally {
            ui { Session.id = old[0]; Session.name = old[1]; Session.token = old[2]; Session.serverUrl = old[3] }
            server.shutdown()
            java.io.File(ctx.filesDir, "attachments").listFiles()?.filter { it.name !in before }?.forEach { it.delete() }
            for (prefs in listOf("downloads_$account", "uploads_$account")) ctx.deleteSharedPreferences(prefs)
        }
    }

    /** Opening a file saves it on the phone and tells the server, once; opening it again needs no server at all. */
    @Test fun anOpenedFileStaysOnThePhoneAndTheServerIsToldOnce() = fixture { server ->
        val bytes = unique("a file someone sent"); val remote = hex(32); server.files[remote] = bytes
        val local = Transfers.download(ctx, received(remote, bytes))
        assertArrayEquals(bytes, Attachments.file(ctx, local).readBytes())
        waitFor("The server hears this phone saved it") { server.got.contains(remote) }
        server.files.remove(remote)   // everyone has it now, so the server let go of its copy
        assertEquals("Opening it again uses the phone's copy", local, Transfers.download(ctx, received(remote, bytes)))
        Thread.sleep(500)
        assertEquals(1, server.downloads.get()); assertEquals("Told once", 1, server.got.count { it == remote })
    }

    /** A chat restored from a backup brings its files along; they open even though the server no longer has them. */
    @Test fun aCopyAlreadyOnThePhoneOpensWithoutTheServer() = fixture { server ->
        val bytes = unique("a restored photo"); Attachments.save(ctx, bytes, "restored.txt", "text/plain")
        val remote = hex(32)
        val local = Transfers.download(ctx, received(remote, bytes))
        assertArrayEquals(bytes, Attachments.file(ctx, local).readBytes())
        assertEquals("Nothing downloaded", 0, server.downloads.get())
    }

    /** Forwarding a received file sends this phone's own copy; one not opened here yet is shared from the server. */
    @Test fun forwardingSendsThePhonesOwnCopy() = fixture { server ->
        val bytes = unique("a file to pass on"); val remote = hex(32); server.files[remote] = bytes
        Transfers.download(ctx, received(remote, bytes)); server.files.remove(remote)
        val sent = JSONObject(Transfers.upload(ctx, received(remote, bytes), "FWD000000001"))
        assertNotEquals("Uploaded again as this phone's file", remote, sent.getString("remote"))
        assertEquals(sha(bytes), sent.getString("sha256")); assertArrayEquals(bytes, server.files[sent.getString("remote")])

        val unopened = unique("not opened here"); val other = received(hex(32), unopened)
        assertEquals("Shared from the server as it is", other, Transfers.upload(ctx, other, "FWD000000002"))
    }
}
