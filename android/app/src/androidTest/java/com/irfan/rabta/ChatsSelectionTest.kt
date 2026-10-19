package com.irfan.rabta

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Chats list selection, your names for people, notes to yourself and New group, on a disposable database. */
@RunWith(AndroidJUnit4::class)
class ChatsSelectionTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val me = "CS1100000001"
    private fun ui(block: () -> Unit) = inst.runOnMainSync(block)
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun windows() = WindowInspector.getGlobalWindowViews().flatMap { all(it) }
    private fun waitUi(message: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        do { inst.waitForIdleSync(); var ok = false; ui { ok = check() }; if (ok) return; Thread.sleep(75) } while (System.currentTimeMillis() < end)
        fail(message)
    }
    private fun shown(words: String) = windows().filterIsInstance<TextView>().any { it.isShown && it.text.toString() == words }
    private fun tap(label: String, exact: Boolean = false) {
        var target: View? = null
        waitUi("'$label' is shown") { target = windows().filterIsInstance<TextView>().lastOrNull { it.isShown && if (exact) it.text.toString() == label else it.text.toString().startsWith(label) }; target != null }
        ui { target!!.performClick() }
    }
    private fun press(description: String) {
        var target: View? = null
        waitUi("'$description' is shown") { target = windows().lastOrNull { it.isShown && it.contentDescription?.toString() == description }; target != null }
        ui { target!!.performClick() }
    }
    private fun hold(name: String) {
        var row: View? = null
        waitUi("$name is listed") {
            row = windows().filterIsInstance<TextView>().firstOrNull { it.isShown && it.text.toString() == name }?.let { v -> generateSequence(v as View) { it.parent as? View }.firstOrNull { it.isLongClickable } }
            row != null
        }
        ui { row!!.performLongClick() }
    }
    /** Clicks the row (or tile) that shows [name]. */
    private fun click(name: String) {
        var row: View? = null
        waitUi("$name is shown") {
            row = windows().filterIsInstance<TextView>().lastOrNull { it.isShown && it.text.toString() == name }?.let { v -> generateSequence(v as View) { it.parent as? View }.firstOrNull { it.isClickable } }
            row != null
        }
        ui { row!!.performClick() }
    }
    private fun selecting() = windows().any { it.isShown && it.contentDescription == "Cancel selection" }
    private fun finishAll() = ui {
        listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED, Stage.CREATED).forEach { stage ->
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).forEach { it.finish() }
        }
    }

    private fun fixture(run: (Db) -> Unit) {
        // Permissions first, so Android's permission screen never covers the chats under test.
        val permissions = mutableListOf("RECORD_AUDIO", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION")
        if (android.os.Build.VERSION.SDK_INT >= 31) permissions += listOf("BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT")
        if (android.os.Build.VERSION.SDK_INT >= 33) permissions += listOf("NEARBY_WIFI_DEVICES", "POST_NOTIFICATIONS")
        permissions.forEach { android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.$it")).use { s -> s.readBytes() } }
        val name = "chats-select-${java.util.UUID.randomUUID()}.db"
        val isolated = object : ContextWrapper(ctx) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(ignored: String) = ctx.getDatabasePath(name)
            override fun openOrCreateDatabase(ignored: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = ctx.openOrCreateDatabase(name, mode, factory)
            override fun openOrCreateDatabase(ignored: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) = ctx.openOrCreateDatabase(name, mode, factory, handler)
        }
        val field = Db::class.java.getDeclaredField("INST").apply { isAccessible = true }
        val previous = field.get(null)
        val db = Db(isolated)
        ui {
            MessageService.stop(ctx); Session.init(ctx); WsClient.disconnect()
            Session.id = me; Session.name = "Selection fixture"; Session.token = "local-selection-test"; Session.serverUrl = "https://127.0.0.1:8443"
            field.set(null, db)
        }
        ctx.getSharedPreferences("appearance", 0).edit().putString("language", "en").putFloat("scale", 1f).commit()
        Names.forget()   // a fresh database: forget nicknames remembered from another one
        try { run(db) } finally {
            finishAll()
            ui { WsClient.disconnect(); MessageService.stop(ctx); field.set(null, previous) }
            Names.forget()
            inst.waitForIdleSync(); db.close(); ctx.deleteDatabase(name)
        }
    }

    /** Hold a chat, then Pin keeps it on top, Archive moves it under "Archived" (and back), Mute silences it. */
    @Test fun holdingAChatPinsArchivesAndMutesIt() = fixture { db ->
        val now = System.currentTimeMillis()
        val (alpha, bravo, charlie) = listOf("CS1100000002", "CS1100000003", "CS1100000004")
        db.upsertConversation(alpha, "Alpha", "oldest", now - 3000, false)
        db.upsertConversation(bravo, "Bravo", "middle", now - 2000, false)
        db.upsertConversation(charlie, "Charlie", "newest", now - 1000, false)
        val list = inst.startActivitySync(Intent(ctx, ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitUi("Chats are listed") { shown("Alpha") && shown("Bravo") && shown("Charlie") }
            hold("Alpha"); waitUi("Holding selects the chat") { selecting() }
            press("Pin")
            waitUi("Pinned chats go on top") { db.conversations().first().peerId == alpha && !selecting() }
            assertTrue(db.conversations().first().pinned > 0)

            hold("Bravo"); press("Archive")
            waitUi("Archived chats leave the list") { db.conversations().single { it.peerId == bravo }.archived && !shown("Bravo") && shown("Archived") }
            click("Archived")
            waitUi("Archived lists them") { shown("Bravo") && !shown("Charlie") }
            hold("Bravo"); press("Unarchive")
            waitUi("Unarchived chats come back") { !db.conversations().single { it.peerId == bravo }.archived }
            press("Back to chats")
            waitUi("Back in the chats") { shown("Bravo") && shown("Charlie") }

            hold("Charlie"); press("Mute"); tap("8 hours")
            waitUi("Muted for 8 hours") { ChatNotifications.muted(ctx, charlie) }
            hold("Charlie"); press("Unmute")
            waitUi("Unmuted") { !ChatNotifications.muted(ctx, charlie) }

            // More: several chats at once, then mark them unread.
            hold("Alpha"); waitUi("Selecting") { selecting() }
            click("Charlie")
            press("More"); tap("Mark as unread")
            waitUi("Marked unread") { db.conversations().filter { it.peerId in setOf(alpha, charlie) }.all { it.unread == 1 } }
        } finally { ChatNotifications.unmute(ctx, charlie); ui { list.finish() } }
    }

    /** Your own name for someone shows in the chats and their contact info; their Rabta name stays as it is. */
    @Test fun yourNameForSomeoneShowsOnThisPhoneOnly() = fixture { db ->
        val peer = "CS1100000005"
        db.upsertConversation(peer, "Real Name", "hi", System.currentTimeMillis(), false)
        Names.set(ctx, peer, "Bhai Jaan", "Real Name")
        assertEquals("Their own name is kept", "Real Name", db.contact(peer)!!.name)
        val list = inst.startActivitySync(Intent(ctx, ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { waitUi("The chat shows your name for them") { shown("Bhai Jaan") && !shown("Real Name") } } finally { ui { list.finish() } }
        val info = inst.startActivitySync(Intent(ctx, ContactProfileActivity::class.java).putExtra("peerId", peer).putExtra("peerName", "Real Name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitUi("Contact info shows both names") { shown("Bhai Jaan") && shown("~ Real Name on Rabta") }
            // A new message under their own name does not overwrite yours.
            db.upsertConversation(peer, "Real Name", "again", System.currentTimeMillis(), true)
            assertEquals("Bhai Jaan", Names.of(ctx, peer, "Real Name"))
        } finally { ui { info.finish() } }
        Names.set(ctx, peer, "")
        assertEquals("Removing your name shows theirs again", "Real Name", Names.of(ctx, peer, "Real Name"))
    }

    /** Message yourself: the note is kept on this phone (nothing is sent) and the chat says so. */
    @Test fun messageYourselfKeepsNotesOnThisPhone() = fixture { db ->
        Outbox.init(ctx)
        Outbox.send(me, "Selection fixture", false, "Buy milk", "text")
        waitUi("The note is saved as done") { db.messages(me).singleOrNull()?.status == MessageDelivery.READ }
        val chat = inst.startActivitySync(Intent(ctx, ChatActivity::class.java).putExtra("peerId", me).putExtra("peerName", "Selection fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitUi("Your own chat") { shown("Selection fixture (You)") && shown("Message yourself") && shown("Buy milk") }
            assertTrue("No calls with yourself", windows().none { it.isShown && (it.contentDescription == "Voice call" || it.contentDescription == "Video call") })
        } finally { ui { chat.finish() } }
        val pick = inst.startActivitySync(Intent(ctx, SelectContactActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { waitUi("Select contact offers it") { shown("Selection fixture (You)") && shown("Message yourself") } } finally { ui { pick.finish() } }
    }

    /** Tapping a photo in the chats opens a card with Message, Voice call, Video call and Info. */
    @Test fun tappingAPhotoOpensTheProfileCard() = fixture { db ->
        val peer = "CS1100000006"
        db.upsertConversation(peer, "Card Person", "hello", System.currentTimeMillis(), false)
        val list = inst.startActivitySync(Intent(ctx, ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            press("Profile photo of Card Person")
            waitUi("The card shows the person and four actions") {
                shown("Card Person") && listOf("Message", "Voice call", "Video call", "Contact info").all { d -> windows().any { it.isShown && it.contentDescription == d } }
            }
        } finally { ui { list.finish() } }
    }

    /** New group: choose people with round ticks, then name it; the server creates it and its chat is saved. */
    @Test fun newGroupChoosesPeopleThenNamesIt() = fixture { db ->
        val (gul, hina) = "CS1100000007" to "CS1100000008"
        db.saveContact(gul, "Gul", "gul"); db.saveContact(hina, "Hina", "hina")
        val created = CopyOnWriteArrayList<JSONObject>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse = when (r.path) {
                    "/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("{\"type\":\"ready\",\"id\":\"$me\",\"protocol\":2}") }
                    })
                    "/api/groups" -> {
                        val body = JSONObject(r.body.readUtf8()); created += body
                        MockResponse().setBody(JSONObject().put("id", "GRPTEST00001").put("name", body.getString("name")).put("members", body.getJSONArray("members")).toString())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        val url = server.url("/").toString().trimEnd('/') // resolves the host name: never on the main thread
        try {
            ui { Session.serverUrl = url; WsClient.connect(Session) }
            waitUi("Connected") { WsClient.connected }
            inst.startActivitySync(Intent(ctx, NewGroupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitUi("Contacts to choose from") { shown("Gul") && shown("Hina") }
            click("Gul"); click("Hina")
            waitUi("Both chosen") { shown("2 of 49 selected") }
            press("Next")
            var field: EditText? = null
            waitUi("Name step") { field = windows().filterIsInstance<EditText>().firstOrNull { it.isShown && it.hint?.toString() == "Group name" }; field != null }
            ui { field!!.setText("Trip") }
            press("Create group")
            waitUi("The group is created and saved") { db.group("GRPTEST00001") != null && db.conversations().any { it.peerId == "GRPTEST00001" && it.isGroup } }
            val body = created.single()
            assertEquals("Trip", body.getString("name"))
            assertEquals(setOf(me, gul, hina), (0 until body.getJSONArray("members").length()).map { body.getJSONArray("members").getString(it) }.toSet())
        } finally { ui { WsClient.disconnect() }; server.shutdown() }
    }
}
