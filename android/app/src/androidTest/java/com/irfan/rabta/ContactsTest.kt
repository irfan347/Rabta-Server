package com.irfan.rabta

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Contacts and chat clearing/deleting on a disposable database; no production accounts, servers or history. */
@RunWith(AndroidJUnit4::class)
class ContactsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private fun ui(block: () -> Unit) = inst.runOnMainSync(block)
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun windows() = WindowInspector.getGlobalWindowViews().flatMap { all(it) }
    private fun waitUi(message: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        do { inst.waitForIdleSync(); var ok = false; ui { ok = check() }; if (ok) return; Thread.sleep(75) } while (System.currentTimeMillis() < end)
        fail(message)
    }
    private fun shown(words: String) = windows().filterIsInstance<TextView>().any { it.isShown && it.text.toString() == words }
    /** Taps the visible text that is [label] (a sheet row starts with it; a dialog button is exactly it). */
    private fun tap(label: String, exact: Boolean = false) {
        var target: View? = null
        waitUi("'$label' is shown") { target = windows().filterIsInstance<TextView>().lastOrNull { it.isShown && if (exact) it.text.toString() == label else it.text.toString().startsWith(label) }; target != null }
        ui { target!!.performClick() }
    }
    private fun hold(name: String) {
        var row: View? = null
        waitUi("$name's chat is listed") {
            row = windows().filterIsInstance<TextView>().firstOrNull { it.isShown && it.text.toString() == name }?.let { v ->
                generateSequence(v as View) { it.parent as? View }.firstOrNull { it.isLongClickable }
            }
            row != null
        }
        ui { row!!.performLongClick() }
    }

    private fun fixture(run: (Db) -> Unit) {
        val permissions = mutableListOf("RECORD_AUDIO", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION")
        if (android.os.Build.VERSION.SDK_INT >= 31) permissions += listOf("BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT")
        if (android.os.Build.VERSION.SDK_INT >= 33) permissions += listOf("NEARBY_WIFI_DEVICES", "POST_NOTIFICATIONS")
        permissions.forEach { android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.$it")).use { s -> s.readBytes() } }
        val name = "contacts-${java.util.UUID.randomUUID()}.db"
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
            Session.id = "CT1100000001"; Session.name = "Contacts fixture"; Session.token = "local-contacts-test"; Session.serverUrl = "https://127.0.0.1:8443"
            field.set(null, db)
        }
        ctx.getSharedPreferences("appearance", 0).edit().putString("language", "en").putFloat("scale", 1f).commit()
        try { run(db) } finally {
            ui { WsClient.disconnect(); MessageService.stop(ctx); field.set(null, previous) }
            inst.waitForIdleSync(); db.close(); ctx.deleteDatabase(name)
        }
    }

    /**
     * The reported problem: with Rabta closed no message or call arrived, because nobody had found the setting that lets
     * it run. The chats screen now asks Android's question by itself, once. A stand-in takes the question here, so
     * Android's real screen never opens.
     */
    @Test fun chatsScreenAsksOnceToKeepRabtaRunning() = fixture { _ ->
        org.junit.Assume.assumeFalse("Rabta already runs in the background on this phone", BackgroundRun.allowed(ctx))
        val prefs = ctx.getSharedPreferences("background_run", 0)
        val asked = java.util.concurrent.atomic.AtomicInteger(); val android = BackgroundRun.ask
        fun openChats() = inst.startActivitySync(Intent(ctx, ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            BackgroundRun.ask = { asked.incrementAndGet() }
            prefs.edit().remove("asked").commit()
            val first = openChats()
            try { waitUi("Android is asked to keep Rabta running") { asked.get() == 1 } } finally { ui { first.finish() } }
            val again = openChats()
            try { Thread.sleep(2_500) } finally { ui { again.finish() } }
            assertEquals("Asked once, not every time the chats open", 1, asked.get())
        } finally {
            BackgroundRun.ask = android
            prefs.edit().putBoolean("asked", true).remove("auto_start_pending").commit()
        }
    }

    /** The request: people you connect with are kept in Contacts; a chat can be cleared or deleted and they stay there. */
    @Test fun contactsKeepPeopleWhoseChatIsClearedOrDeleted() = fixture { db ->
        val friend = "CT1100000002"; val other = "CT1100000003"; val now = System.currentTimeMillis()
        db.saveMessage("CTMSG0000001", friend, friend, "Hello there", now - 60_000, false, "received")
        db.upsertConversation(friend, "Saved friend", "Hello there", now - 60_000, false)
        db.saveContact(other, "Only contact", "only_contact")   // found by username, no message yet
        assertTrue("A one-to-one chat saves its person as a contact", db.contacts().any { it.peerId == friend && it.name == "Saved friend" })
        val list = inst.startActivitySync(Intent(ctx, ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ConversationListActivity
        try {
            waitUi("The chat is listed") { shown("Saved friend") }
            assertFalse("Chats hold chats only", shown("Only contact"))

            ui { windows().first { it.contentDescription == "Contacts filter" }.performClick() }
            waitUi("Contacts lists everyone connected") { shown("Saved friend") && shown("Only contact") && shown("@only_contact") && shown("Add contact") }
            ui { windows().first { it.contentDescription == "All filter" }.performClick() }
            // The list reloads in the background; until then the contact row is on screen, and holding it opens the contact's sheet.
            waitUi("Chats are listed again") { shown("Saved friend") && !shown("Add contact") && !shown("Only contact") }

            // Clear chat: the messages go, the chat stays.
            hold("Saved friend"); tap("Clear chat"); tap("Clear chat", exact = true)
            waitUi("Clearing empties the chat but keeps it listed") { db.messages(friend).isEmpty() && shown("Saved friend") && shown("No messages yet") }

            // Delete chat: the chat goes, the person stays in Contacts.
            hold("Saved friend"); tap("Delete chat"); tap("Delete chat", exact = true)
            waitUi("Deleting removes the chat") { db.conversations().none { it.peerId == friend } && !shown("Saved friend") }
            assertTrue("The person stays a contact", db.contacts().any { it.peerId == friend })
            ui { windows().first { it.contentDescription == "Contacts filter" }.performClick() }
            waitUi("Still in Contacts") { shown("Saved friend") && shown("Only contact") }

            // Delete contact: gone from Contacts.
            hold("Only contact"); tap("Delete contact"); tap("Delete", exact = true)
            waitUi("The deleted contact is gone") { db.contacts().none { it.peerId == other } && !shown("Only contact") && shown("Saved friend") }
        } finally { ui { list.finish() } }
    }

    /** A contact you delete stays deleted while their chat goes on; a new chat with them saves them again. */
    @Test fun deletedContactStaysDeletedUntilANewChat() = fixture { db ->
        val peer = "CT1100000005"; val now = System.currentTimeMillis()
        db.upsertConversation(peer, "Chatty", "First", now, true)
        assertTrue("A new chat saves its person", db.contacts().any { it.peerId == peer })
        db.removeContact(peer)
        db.upsertConversation(peer, "Chatty", "Another message", now + 1, true)
        assertTrue("More messages in the same chat do not bring a deleted contact back", db.contacts().none { it.peerId == peer })
        db.deleteChat(peer)
        db.upsertConversation(peer, "Chatty again", "Hello after a while", now + 2, true)
        assertEquals("A new chat saves them again, with their current name", "Chatty again", db.contacts().single { it.peerId == peer }.name)
        db.upsertConversation(peer, "Renamed", "Name changed", now + 3, true)
        assertEquals("An existing contact keeps the current name", "Renamed", db.contacts().single { it.peerId == peer }.name)
    }

    /** Updating keeps every chat: people already chatted with one-to-one become contacts, groups do not. */
    @Test fun updateMakesExistingChatPartnersContacts() {
        val name = "contacts-upgrade-${java.util.UUID.randomUUID()}.db"
        val path = ctx.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE conversations(peer_id TEXT PRIMARY KEY, peer_name TEXT, last_msg TEXT, last_time INTEGER DEFAULT 0, unread INTEGER DEFAULT 0, is_group INTEGER DEFAULT 0)")
            old.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, conv_id TEXT, from_id TEXT, from_name TEXT DEFAULT '', body TEXT, sent_at INTEGER, mine INTEGER DEFAULT 0, status TEXT DEFAULT 'sent', msg_type TEXT DEFAULT 'text')")
            old.execSQL("INSERT INTO conversations VALUES('CT1100000004','Old friend','Hi',100,0,0)")
            old.execSQL("INSERT INTO conversations VALUES('GROUP0000001','Old group','Hey',200,0,1)")
            old.execSQL("INSERT INTO messages VALUES('CTMSG0000002','CT1100000004','CT1100000004','','Hi',100,0,'received','text')")
            old.version = 5
        }
        val isolated = object : ContextWrapper(ctx) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(ignored: String) = path
            override fun openOrCreateDatabase(ignored: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(path, factory)
            override fun openOrCreateDatabase(ignored: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(path, factory)
        }
        val db = Db(isolated)
        try {
            assertEquals(listOf("CT1100000004"), db.contacts().map { it.peerId })
            assertEquals("Old friend", db.contacts().single().name)
            assertEquals("Chats are untouched", setOf("CT1100000004", "GROUP0000001"), db.conversations().map { it.peerId }.toSet())
            assertEquals("Messages are untouched", 1, db.messages("CT1100000004").size)
        } finally { db.close(); ctx.deleteDatabase(name) }
    }
}
