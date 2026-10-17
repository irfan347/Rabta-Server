package com.irfan.rabta

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated emulator fixture accounts; never production accounts or messages. */
@RunWith(AndroidJUnit4::class)
class AccountStorageTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    @Test fun eachAccountOnThePhoneSeesOnlyItsOwnHistory() {
        inst.runOnMainSync { Session.init(ctx) }
        val field = Db::class.java.getDeclaredField("INST").apply { isAccessible = true }
        val previous = field.get(null); val oldId = Session.id
        val a = "AC5700000001"; val b = "AC5700000002"; val message = "AC57AAAA0001"
        try {
            Session.id = a
            val dbA = Db.get(ctx)
            dbA.upsertConversation("PEER57000001", "Private peer", "only for A", 1L, false)
            dbA.saveMessage(message, "PEER57000001", a, "only for A", 1L, true)
            Session.id = b
            val dbB = Db.get(ctx)
            assertNotSame(dbA, dbB)
            assertFalse("Another account must not read A's messages", dbB.hasMessage(message))
            assertTrue("Another account must not list A's chats", dbB.conversations().none { it.peerId == "PEER57000001" })
            Session.id = ""
            assertFalse("Signed out, no stored history is readable", Db.get(ctx).hasMessage(message))
            Session.id = a
            assertEquals("only for A", Db.get(ctx).messages("PEER57000001").first { it.id == message }.body)
        } finally {
            Session.id = oldId; field.set(null, previous)
            ctx.deleteDatabase("rabta_$a.db"); ctx.deleteDatabase("rabta_$b.db")
        }
    }
}
