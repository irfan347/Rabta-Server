package com.irfan.rabta

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
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
class EmojiClippingTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val me = "E4031E000001"
    private val peer = "E4031E000002"
    /** One emoji, three emoji and the last message, each shown large; and emoji inside ordinary text. */
    private val messages = listOf("E4031E0000B1" to "😂", "E4031E0000B2" to "😂❤️👍", "E4031E0000B3" to "Hi 😂 there", "E4031E0000B4" to "😍")
    private lateinit var old: List<String>
    private lateinit var db: Db
    private fun ui(block: () -> Unit) = inst.runOnMainSync(block)
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()).trim() }
    private fun waitFor(message: String, check: () -> String?) {
        val end = SystemClock.elapsedRealtime() + 8000
        var last: String? = "not checked"
        while (SystemClock.elapsedRealtime() < end) { inst.waitForIdleSync(); ui { last = check() }; if (last == null) return; Thread.sleep(50) }
        fail("$message: $last")
    }

    @Before fun setUp() {
        Session.init(ctx); old = listOf(Session.id, Session.name, Session.username, Session.token, Session.serverUrl)
        ui { MessageService.stop(ctx); WsClient.disconnect(); Session.id = me; Session.name = "Emoji fixture"; Session.username = "emojifixture"; Session.token = "fixture-token"; Session.serverUrl = "https://127.0.0.1:8443" }
        Privacy.prefs(ctx).edit().clear().commit(); ctx.getSharedPreferences("drafts_$me", 0).edit().clear().commit()
        L.prefs(ctx).edit().putString("experience", "Standard").putString("language", "en").putFloat("scale", 1f).commit()
        db = Db.get(ctx); clean()
        db.saveContact(peer, "Emoji friend")
        val now = System.currentTimeMillis()
        db.upsertConversation(peer, "Emoji friend", "😍", now, false)
        for (i in 0 until 8) db.saveIfNew("E4031E0000A$i", peer, if (i % 2 == 0) peer else me, if (i % 2 == 0) "Emoji friend" else "Emoji fixture", "Message $i", now - 60_000 + i * 1000, i % 2 == 1, if (i % 2 == 0) "received" else "sent")
        messages.forEachIndexed { i, (id, body) ->
            val mine = i % 2 == 1
            db.saveIfNew(id, peer, if (mine) me else peer, if (mine) "Emoji fixture" else "Emoji friend", body, now - 5000 + i * 1000, mine, if (mine) "sent" else "received")
        }
    }
    private fun clean() {
        db.writableDatabase.delete("messages", "conv_id=?", arrayOf(peer)); db.writableDatabase.delete("conversations", "peer_id=?", arrayOf(peer)); db.removeContact(peer)
    }
    @After fun tearDown() {
        ui { for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED)) ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).toList().forEach { it.finish() } }
        inst.waitForIdleSync()
        clean(); Privacy.prefs(ctx).edit().clear().commit(); ctx.getSharedPreferences("drafts_$me", 0).edit().clear().commit()
        L.prefs(ctx).edit().remove("animate_emoji").commit()
        ui { Session.id = old[0]; Session.name = old[1]; Session.username = old[2]; Session.token = old[3]; Session.serverUrl = old[4] }
    }

    /** The reported bug: emoji showed half. Every emoji is drawn inside its own view, and the last one stays whole when the emoji panel opens. */
    @Test fun emojiShowWholeInChat() {
        val a = inst.startActivitySync(Intent(ctx, ChatActivity::class.java).putExtra("peerId", peer).putExtra("peerName", "Emoji friend").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ChatActivity
        fun text(id: String, body: String) = all(a.window.decorView).firstOrNull { it.tag == "message:$id" }?.let { row -> all(row).firstOrNull { it is TextView && it.text.toString() == body } as TextView? }
        /** Null when the emoji glyphs lie inside the view, so nothing of them is cut off; otherwise what sticks out. */
        fun inside(id: String, body: String): String? {
            val v = text(id, body) ?: return "no view for $body"
            val layout = v.layout ?: return "no layout for $body"
            val glyphs = Rect(); v.paint.getTextBounds(body, 0, body.length, glyphs)
            val baseline = v.totalPaddingTop + layout.getLineBaseline(0)
            val top = baseline + glyphs.top; val bottom = baseline + glyphs.bottom
            val left = v.totalPaddingLeft + layout.getLineLeft(0).toInt() + glyphs.left; val right = v.totalPaddingLeft + layout.getLineLeft(0).toInt() + glyphs.right
            return if (top >= 0 && bottom <= v.height && left >= 0 && right <= v.width) null
            else "$body at ${v.textSize}px in a ${v.width}x${v.height} view draws $left..$right x $top..$bottom (lines ${layout.lineCount}, padding ${v.totalPaddingTop}/${v.totalPaddingBottom})"
        }
        fun whole(id: String, body: String): String? {
            val v = text(id, body) ?: return "no view for $body"
            val r = Rect()
            return if (v.getGlobalVisibleRect(r) && r.width() == v.width && r.height() == v.height) null else "$body shows $r of ${v.width}x${v.height}"
        }
        val (lastId, lastBody) = messages.last()
        waitFor("The chat opens at its latest message") { whole(lastId, lastBody) }
        for ((id, body) in messages) waitFor("Emoji drawn inside their view") { inside(id, body) }
        // The emoji panel takes the keyboard's place; the last emoji is not left half under its edge.
        ui { all(a.window.decorView).first { it.isShown && it.contentDescription == "Emoji" }.performClick() }
        waitFor("The panel opens") { if (all(a.window.decorView).any { it.isShown && it.contentDescription == "Keyboard" }) null else "no panel" }
        waitFor("The last emoji stays whole above the panel") { whole(lastId, lastBody) }
    }

}
