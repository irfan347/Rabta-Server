package com.irfan.rabta

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fixture-only account; never runs against production storage or the live server. */
@RunWith(AndroidJUnit4::class)
class WebCompanionTest {
    private val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(run:(Db)->Unit) {
        Session.init(ctx)
        val before=listOf(Session.id,Session.name,Session.username,Session.token,Session.serverUrl)
        val me="FEB000000001"
        WsClient.disconnect();MessageService.stop(ctx)
        Session.id=me;Session.token="web-fixture";Session.serverUrl="http://127.0.0.1:1"
        val db=Db.get(ctx)
        try {run(db)} finally {
            db.writableDatabase.delete("messages","conv_id=?",arrayOf("FEB000000002"))
            db.writableDatabase.delete("conversations","peer_id=?",arrayOf("FEB000000002"))
            Privacy.prefs(ctx).edit().clear().commit();WebCompanion.revoke(ctx,"fixture-link")
            Session.id=before[0];Session.name=before[1];Session.username=before[2];Session.token=before[3];Session.serverUrl=before[4]
        }
    }
    @Test fun browserAccessExpiresAndPrivateChatsAreExcluded()=fixture { _ ->
        val peer="FEB000000002";val chat=Db.Conversation(peer,"Friend","",0,0)
        assertTrue(WebCompanion.visible(ctx,chat))
        assertFalse(WebCompanion.visible(ctx,chat.copy(isGroup=true)))
        assertFalse(WebCompanion.visible(ctx,chat.copy(archived=true)))
        Privacy.prefs(ctx).edit().putBoolean("chat_lock_$peer",true).commit()
        assertFalse(WebCompanion.visible(ctx,chat))
        Privacy.prefs(ctx).edit().putBoolean("chat_lock_$peer",false).putBoolean("request_$peer",true).commit()
        assertFalse(WebCompanion.visible(ctx,chat))
        assertTrue(WebCompanion.allow(ctx,"fixture-link",System.currentTimeMillis()+60000))
        assertTrue(WebCompanion.allowed(ctx,"fixture-link"))
        WebCompanion.revoke(ctx,"fixture-link");assertFalse(WebCompanion.allowed(ctx,"fixture-link"))
        WebCompanion.allow(ctx,"fixture-link",System.currentTimeMillis()-1);assertFalse(WebCompanion.allowed(ctx,"fixture-link"))
    }
    @Test fun webSendIsDurableIdempotentAndChecksPermissionBeforeWriting()=fixture { db ->
        Outbox.init(ctx)
        val peer="FEB000000002";val id="FEB000000003"
        fun send(text:String,allowed:Boolean):Boolean {
            val latch=CountDownLatch(1);var result=false
            Outbox.sendFromWeb(peer,"Fixture",text,id,{allowed}) { result=it;latch.countDown() }
            assertTrue("Phone queue responds",latch.await(5,TimeUnit.SECONDS));return result
        }
        assertFalse(send("hello",false));assertFalse(db.hasMessage(id))
        assertTrue(send("hello",true));assertEquals("hello",db.outgoing(id)?.body)
        assertTrue(send("hello",true));assertEquals(1,db.messages(peer).count { it.id==id })
        assertFalse(send("different content with same ID",true));assertEquals("hello",db.outgoing(id)?.body)
    }
}
