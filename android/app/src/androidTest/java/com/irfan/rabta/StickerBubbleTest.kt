package com.irfan.rabta

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated fixture account; never a production account. */
@RunWith(AndroidJUnit4::class)
class StickerBubbleTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val me = "57C4E7000001"
    private val peer = "57C4E7000002"
    private val received = "57C4E70000B1"
    private val sent = "57C4E70000B2"
    private lateinit var old: List<String>
    private lateinit var db: Db
    private fun ui(block: () -> Unit) = inst.runOnMainSync(block)
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun waitFor(message: String, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < end) { inst.waitForIdleSync(); var pass = false; ui { pass = check() }; if (pass) return; Thread.sleep(50) }
        fail(message)
    }

    @Before fun setUp() {
        Session.init(ctx); old = listOf(Session.id, Session.name, Session.username, Session.token, Session.serverUrl)
        ui { MessageService.stop(ctx); WsClient.disconnect(); Session.id = me; Session.name = "Sticker fixture"; Session.username = "stickerfixture"; Session.token = "fixture-token"; Session.serverUrl = "https://127.0.0.1:8443" }
        Privacy.prefs(ctx).edit().clear().commit(); ctx.getSharedPreferences("drafts_$me", 0).edit().clear().commit()
        L.prefs(ctx).edit().putString("experience", "Standard").putString("language", "en").putFloat("scale", 1f).commit()
        db = Db.get(ctx); clean()
        db.saveContact(peer, "Sticker friend")
        val now = System.currentTimeMillis()
        db.upsertConversation(peer, "Sticker friend", "Sticker", now, false)
        // Enough chat to scroll, then two stickers at the end, like a real conversation.
        for (i in 0 until 10) db.saveIfNew("57C4E70000A$i", peer, if (i % 2 == 0) peer else me, if (i % 2 == 0) "Sticker friend" else "Sticker fixture", "Message $i", now - 60_000 + i * 1000, i % 2 == 1, if (i % 2 == 0) "received" else "sent")
        db.saveIfNew(received, peer, peer, "Sticker friend", "rbt:3", now - 2000, false, "received", "sticker")
        db.saveIfNew(sent, peer, me, "Sticker fixture", "rbt:11", now - 1000, true, "sent", "sticker")
    }
    private fun clean() {
        db.writableDatabase.delete("messages", "conv_id=?", arrayOf(peer)); db.writableDatabase.delete("conversations", "peer_id=?", arrayOf(peer)); db.removeContact(peer)
    }
    @After fun tearDown() {
        ui { for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED)) ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).toList().forEach { it.finish() } }
        inst.waitForIdleSync()
        clean(); Privacy.prefs(ctx).edit().clear().commit(); ctx.getSharedPreferences("drafts_$me", 0).edit().clear().commit()
        ui { Session.id = old[0]; Session.name = old[1]; Session.username = old[2]; Session.token = old[3]; Session.serverUrl = old[4] }
    }

    /** The reported bug: a sticker in a chat showed only half. The stickers are drawn whole, and stay whole on screen when the sticker panel opens. */
    @Test fun stickersStayWholeWhenTheStickerPanelOpens() {
        val a = inst.startActivitySync(Intent(ctx, ChatActivity::class.java).putExtra("peerId", peer).putExtra("peerName", "Sticker friend").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ChatActivity
        fun sticker(id: String) = all(a.window.decorView).firstOrNull { it.tag == "message:$id" }?.let { row -> all(row).firstOrNull { it is ImageView && it.contentDescription == "Sticker" } as ImageView? }
        fun whole(id: String): String? {
            val v = sticker(id) ?: return "no sticker $id"
            val r = Rect()
            return if (v.getGlobalVisibleRect(r) && r.width() == v.width && r.height() == v.height) null else "sticker $id shows $r of ${v.width}x${v.height}"
        }
        waitFor("Both stickers load") { (sticker(received)?.height ?: 0) > 0 && (sticker(sent)?.height ?: 0) > 0 }
        waitFor("The chat opens at its latest message") { whole(sent) == null }
        ui {
            val side = RabtaUi.dp(a, 150)
            for (id in listOf(received, sent)) {
                val v = sticker(id)!!
                assertEquals("Sticker $id is laid out at its full size", side to side, v.width to v.height)
                // Drawn whole: its top and its bottom are both painted.
                val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(bmp))
                val top = bmp.getPixel(v.width / 2, (v.height * 0.12f).toInt()); val bottom = bmp.getPixel(v.width / 2, (v.height * 0.86f).toInt()); bmp.recycle()
                assertTrue("Sticker $id has its top drawn", Color.alpha(top) > 0)
                assertTrue("Sticker $id has its bottom drawn", Color.alpha(bottom) > 0)
            }
        }
        // The sticker panel takes the keyboard's place; the latest sticker is not left half under its edge.
        ui { all(a.window.decorView).first { it.isShown && it.contentDescription == "Emoji" }.performClick() }
        waitFor("The panel opens") { all(a.window.decorView).any { it.isShown && it.contentDescription == "Keyboard" } }
        waitFor("The latest sticker stays whole above the panel") { whole(sent) == null }
        review()
    }

    /** A small picture of the chat for the CI log (Rabta keeps its screens out of screenshots; see captureScreen). */
    private fun review() {
        Thread.sleep(300); inst.waitForIdleSync()
        val shot = inst.captureScreen() ?: return
        val scaled = Bitmap.createScaledBitmap(shot, 360, (shot.height * 360f / shot.width).toInt(), true)
        val bytes = java.io.ByteArrayOutputStream().apply { scaled.compress(Bitmap.CompressFormat.JPEG, 70, this) }.toByteArray()
        if (scaled !== shot) scaled.recycle(); shot.recycle()
        val dir = File(ctx.getExternalFilesDir(null), "sticker-review").apply { mkdirs() }
        File(dir, "sticker-review.b64").writeText(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        for (command in listOf("mkdir -p /sdcard/Download/rabta-interface", "cp ${dir.absolutePath}/sticker-review.b64 /sdcard/Download/rabta-interface/sticker-review.b64"))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
