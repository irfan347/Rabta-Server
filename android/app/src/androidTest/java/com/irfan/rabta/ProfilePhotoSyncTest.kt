package com.irfan.rabta

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Profile photos between fixture accounts on a local mock server; never production users or servers. */
@RunWith(AndroidJUnit4::class)
class ProfilePhotoSyncTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private fun ui(block: () -> Unit) = inst.runOnMainSync(block)
    private fun waitUi(message: String, test: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) { var ok = false; ui { ok = test() }; if (ok) return; Thread.sleep(50) }
        fail(message)
    }

    /** One photo per account like features.js: 404 without one, and a bodiless 304 while it is unchanged. */
    private class PhotoServer : Dispatcher() {
        val photos = ConcurrentHashMap<String, String>()
        val uploads = CopyOnWriteArrayList<String>()
        val downloads = AtomicInteger()
        val unchanged = AtomicInteger()
        override fun dispatch(r: RecordedRequest): MockResponse {
            val me = r.getHeader("Authorization").orEmpty().removePrefix("Bearer fixture-")
            val path = r.path.orEmpty()
            if (r.method == "POST" && path == "/api/me/photo") {
                val photo = JSONObject(r.body.readUtf8()).optString("photo")
                uploads.add(photo); if (photo.isEmpty()) photos.remove(me) else photos[me] = photo
                return MockResponse().setBody("{\"ok\":true}")
            }
            val photo = photos[path.removePrefix("/api/user/photo/")]
            if (r.method != "GET" || !path.startsWith("/api/user/photo/") || photo == null) return MockResponse().setResponseCode(404).setBody("{\"error\":\"No photo\"}")
            val tag = "W/\"${photo.hashCode()}\""
            if (r.getHeader("If-None-Match") == tag) { unchanged.incrementAndGet(); return MockResponse().setResponseCode(304).setHeader("ETag", tag) }
            downloads.incrementAndGet()
            return MockResponse().setHeader("ETag", tag).setBody(JSONObject().put("photo", photo).toString())
        }
    }

    private var now = 0L
    private var url = ""
    private fun account() = java.util.UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
    /** Switches this phone to the fixture account [id]; the photo the phone keeps is left as it is. */
    private fun signIn(id: String) = ui { Session.id = id; Session.name = "Fixture"; Session.token = "fixture-$id"; Session.serverUrl = url }
    private fun resetSyncPacing() = ProfilePhoto::class.java.getDeclaredField("lastSync").apply { isAccessible = true }.setLong(null, 0L)

    private fun fixture(run: (PhotoServer) -> Unit) {
        Session.init(ctx)
        val old = listOf(Session.id, Session.name, Session.username, Session.token, Session.serverUrl, Session.profilePic)
        val oldShared = Session.profilePicShared; val oldClock = ProfileHelper.clock
        val photos = PhotoServer(); val server = MockWebServer().apply { dispatcher = photos; start() }
        url = server.url("/").toString().trimEnd('/'); now = SystemClock.elapsedRealtime()
        ui { MessageService.stop(ctx); WsClient.disconnect(); ProfileHelper.clear(); ProfileHelper.clock = { now } }
        try { run(photos) } finally {
            ui {
                ProfileHelper.clear(); ProfileHelper.clock = oldClock
                Session.id = old[0]; Session.name = old[1]; Session.username = old[2]; Session.token = old[3]; Session.serverUrl = old[4]
                Session.profilePic = old[5]; Session.profilePicShared = oldShared
            }
            resetSyncPacing(); server.shutdown()
        }
    }

    /** A photo-like JPEG: a gradient with grain, so it compresses like a real picture rather than a flat colour. */
    private fun jpeg(side: Int, quality: Int): ByteArray {
        val random = java.util.Random(7)
        val pixels = IntArray(side * side) { val x = it % side; val y = it / side; val grain = random.nextInt(48) - 24
            Color.rgb((x * 255 / side + grain).coerceIn(0, 255), (y * 255 / side + grain).coerceIn(0, 255), (128 + grain).coerceIn(0, 255)) }
        val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    /** The reported bug: a photo kept only on its owner's phone, and a contact whose running app remembered "no photo". */
    @Test fun photoKeptOnlyOnThePhoneReachesAContactWithoutRestartingTheirApp() = fixture { photos ->
        val owner = account(); val contact = account(); val bytes = jpeg(96, 85)
        // The contact looks first, while the server has no photo for the owner.
        signIn(contact)
        var seen: Bitmap? = null; var answers = 0
        val look = { ui { ProfileHelper.loadIfNeeded(owner, Session.serverUrl, Session.token) { seen = it; answers++ } } }
        look(); waitUi("Contact hears there is no photo") { answers == 1 }
        assertNull(seen)

        // The owner's phone shares the photo it kept to itself, stored the old way with line breaks.
        val legacy = Base64.encodeToString(bytes, Base64.DEFAULT)
        signIn(owner); resetSyncPacing()
        ui { Session.profilePic = legacy; Session.profilePicShared = false; ProfilePhoto.sync {} }
        waitUi("Phone shares its photo") { Session.profilePicShared }
        assertEquals("Same picture, on the single base64 line the server requires", Base64.encodeToString(bytes, Base64.NO_WRAP), photos.photos[owner])
        resetSyncPacing(); ui { ProfilePhoto.sync {} }
        Thread.sleep(500)
        assertEquals("Shared once, not on every resume", 1, photos.uploads.size)
        ui { assertEquals("The phone keeps its own copy", legacy, Session.profilePic) }

        // Back on the contact's phone, which kept running: the recent answer is reused, then a minute later the photo shows.
        signIn(contact)
        look(); ui { assertEquals(2, answers); assertNull(seen) }
        assertEquals(0, photos.downloads.get())
        now += 61_000
        look(); waitUi("Photo shows for the contact") { answers == 3 }
        assertEquals(96, seen?.width); assertEquals(1, photos.downloads.get())

        // Minutes later the photo shows at once while a tiny "unchanged" check runs; it is not downloaded again.
        val first = seen
        now += 5 * 60_000 + 1
        look(); ui { assertEquals(4, answers); assertSame(first, seen) }
        waitUi("Unchanged photo answered without the picture") { photos.unchanged.get() == 1 }
        assertEquals(1, photos.downloads.get())

        // Once the owner removes it, the contact's next look shows initials.
        photos.photos.remove(owner)
        now += 5 * 60_000 + 1
        look()
        waitUi("Removed photo is forgotten") { ProfileHelper.get(owner) == null }
        ui { assertEquals("The old photo was handed out once, not followed by a second answer", 5, answers) }
        look(); ui { assertEquals(6, answers); assertNull(seen) }
    }

    /** Older app versions kept photos up to 1600 px, above the server's 256 KB limit; they are resized for sharing. */
    @Test fun largePhotoFromAnOlderVersionIsResizedForTheServerAndKeptOnThePhone() = fixture { photos ->
        val owner = account(); val bytes = jpeg(1400, 95)
        assertTrue("Fixture is over the server limit", bytes.size > 256 * 1024)
        val local = Base64.encodeToString(bytes, Base64.NO_WRAP)
        signIn(owner); resetSyncPacing()
        ui { Session.profilePic = local; Session.profilePicShared = false; ProfilePhoto.sync {} }
        waitUi("Resized photo shared") { Session.profilePicShared }
        val sent = Base64.decode(photos.photos[owner], Base64.NO_WRAP)
        assertTrue("JPEG within the server limit", sent[0] == 0xFF.toByte() && sent[1] == 0xD8.toByte() && sent.size <= 256 * 1024)
        assertEquals(480, BitmapFactory.decodeByteArray(sent, 0, sent.size).width)
        ui { assertEquals("The phone keeps its original", local, Session.profilePic) }
    }
}
