package com.irfan.rabta

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Isolated emulator fixture accounts; never production accounts or messages. */
@RunWith(AndroidJUnit4::class)
class AccountDeletionTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private fun prefsFile(name: String) = File(ctx.applicationInfo.dataDir, "shared_prefs/$name.xml")

    /** Once the server deleted an account, the phone drops that account's chats, files and settings, and nothing of another's. */
    @Test fun deletingAnAccountRemovesOnlyItsOwnDataFromThePhone() {
        inst.runOnMainSync { Session.init(ctx) }
        val field = Db::class.java.getDeclaredField("INST").apply { isAccessible = true }
        val previous = field.get(null)
        val old = listOf(Session.id, Session.name, Session.username, Session.token, Session.serverUrl)
        val gone = "DE1E7E000001"; val stays = "DE1E7E000002"
        val dir = File(ctx.filesDir, "attachments").apply { mkdirs() }
        val mine = UUID.randomUUID().toString(); val theirs = UUID.randomUUID().toString()
        try {
            File(dir, mine).writeText("photo of the deleted account"); File(dir, theirs).writeText("photo of the other account")
            Session.id = stays
            Db.get(ctx).saveMessage("DE1E7E00000A", "PEERPEERPEER", stays, JSONObject().put("local", theirs).toString(), 1L, true, "sent", "image")
            ctx.getSharedPreferences("privacy_$stays", 0).edit().putBoolean("hide_previews", true).commit()
            Session.id = gone; Session.token = "local-deletion-fixture"; Session.serverUrl = "https://127.0.0.1:8443"
            Db.get(ctx).saveMessage("DE1E7E00000B", "PEERPEERPEER", gone, JSONObject().put("local", mine).toString(), 1L, true, "sent", "image")
            ctx.getSharedPreferences("privacy_$gone", 0).edit().putBoolean("hide_previews", true).commit()
            ctx.getSharedPreferences("downloads_$gone", 0).edit().putString("remote-file", mine).commit()

            val stale = Db.get(ctx)
            // A worker can retain a helper while the singleton follows a different account.
            Session.id = stays
            Db.get(ctx)
            Session.id = gone
            val latest = Db.get(ctx)
            AccountDeletion.wipe(ctx, gone)
            for (helper in listOf(stale, latest)) {
                assertTrue("Retained writer cannot recreate deleted chats", runCatching { helper.writableDatabase }.isFailure)
                assertTrue("Retained reader cannot recreate deleted chats", runCatching { helper.readableDatabase }.isFailure)
            }

            assertFalse("Signed out", Session.loggedIn)
            assertFalse("Its chats are deleted", ctx.getDatabasePath("rabta_$gone.db").exists())
            assertFalse("Its files are deleted", File(dir, mine).exists())
            assertFalse("Its settings are deleted", prefsFile("privacy_$gone").exists())
            assertFalse(prefsFile("downloads_$gone").exists())
            assertTrue("Another account's chats stay", ctx.getDatabasePath("rabta_$stays.db").exists())
            assertTrue("Another account's files stay", File(dir, theirs).exists())
            assertTrue("Another account's settings stay", prefsFile("privacy_$stays").exists())
        } finally {
            Session.id = old[0]; Session.name = old[1]; Session.username = old[2]; Session.token = old[3]; Session.serverUrl = old[4]
            field.set(null, previous)
            for (a in listOf(gone, stays)) { ctx.deleteDatabase("rabta_$a.db"); ctx.deleteSharedPreferences("privacy_$a"); ctx.deleteSharedPreferences("downloads_$a") }
            File(dir, mine).delete(); File(dir, theirs).delete()
        }
    }
}
