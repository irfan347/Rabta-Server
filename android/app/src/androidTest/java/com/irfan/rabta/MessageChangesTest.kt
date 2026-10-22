package com.irfan.rabta

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageChangesTest {
    private val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    private val account="CHANGE_FIXTURE"
    private lateinit var db:Db
    private val id="AAA000000001"
    private val author="BBB000000002"
    private val conv="CCC000000003"
    @Before fun setup() {ctx.deleteDatabase("rabta_$account.db");db=Db(ctx,account)}
    @After fun cleanup() {db.close();ctx.deleteDatabase("rabta_$account.db")}
    private fun change(rev:Int,action:String="edit",text:String="updated",from:String=author)=JSONObject().put("id",id).put("from",from).put("convId",conv).put("revision",rev).put("action",action).put("text",text).put("changedAt",rev*1000L)
    private fun original()=db.saveIfNew(id,conv,author,"Friend","original",100,false,"received")
    @Test fun editArrivingFirstIsAppliedWhenOriginalIsStored() {
        db.receiveChange(change(1));original()
        assertEquals("updated",db.message(id)?.body);assertEquals(1000L,db.message(id)?.editedAt)
    }
    @Test fun deleteWinsOverRetriesAndLateEdits() {
        db.receiveChange(change(2,"delete"));original();db.receiveChange(change(1));db.receiveChange(change(3))
        assertEquals("deleted",db.message(id)?.msgType);assertEquals("Message deleted",db.message(id)?.body)
        assertFalse(original());assertTrue(db.hasPendingDeletion(id,author,conv))
    }
    @Test fun authorAndConversationMustMatch() {
        original();db.receiveChange(change(1,from="DDD000000004"));assertEquals("original",db.message(id)?.body)
        db.receiveChange(change(1).put("convId","EEE000000005"));assertEquals("original",db.message(id)?.body)
    }
    @Test fun staleEditDoesNotOverwriteNewerEditOrLocalDeletion() {
        original();db.receiveChange(change(2,text="latest"));db.receiveChange(change(1,text="stale"));assertEquals("latest",db.message(id)?.body)
        db.deleteMessage(id);db.receiveChange(change(3));assertEquals("Message deleted",db.message(id)?.body)
    }
}
