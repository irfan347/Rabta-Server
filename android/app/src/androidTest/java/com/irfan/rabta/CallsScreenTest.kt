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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Disposable emulator-only database; no production or other test history is deleted. */
@RunWith(AndroidJUnit4::class)
class CallsScreenTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private fun ui(run:()->Unit)=inst.runOnMainSync(run)
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun waitUi(check:()->Boolean) {
        val until=System.currentTimeMillis()+10000
        do { inst.waitForIdleSync();var ok=false;ui { ok=check() };if(ok)return;Thread.sleep(75) } while(System.currentTimeMillis()<until)
        fail("Calls UI did not reach expected state")
    }
    private fun fixture(run:(Db)->Unit) {
        val name="calls-ui-${java.util.UUID.randomUUID()}.db"
        val isolated=object:ContextWrapper(ctx) {
            override fun getApplicationContext():Context=this
            override fun getDatabasePath(ignored:String)=ctx.getDatabasePath(name)
            override fun openOrCreateDatabase(ignored:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=ctx.openOrCreateDatabase(name,mode,factory)
            override fun openOrCreateDatabase(ignored:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?)=ctx.openOrCreateDatabase(name,mode,factory,handler)
        }
        val field=Db::class.java.getDeclaredField("INST").apply { isAccessible=true }
        val previous=field.get(null)
        val db=Db(isolated)
        ui {
            MessageService.stop(ctx);Session.init(ctx);WsClient.disconnect()
            Session.id="CA1100000001";Session.name="Calls fixture";Session.token="local-calls-test"
            Session.serverUrl="https://127.0.0.1:8443"
            field.set(null,db)
        }
        ctx.getSharedPreferences("appearance",0).edit().putString("mode","Dark").putString("language","en").putFloat("scale",1f).commit()
        try { run(db) } finally {
            ui { WsClient.disconnect();MessageService.stop(ctx);field.set(null,previous) }
            db.close();ctx.deleteDatabase(name)
        }
    }
    private fun launch()=inst.startActivitySync(Intent(ctx,ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ConversationListActivity
    private fun nav(a:ConversationListActivity,label:String)=all(a.window.decorView).first { it is ViewGroup && it.contentDescription==label }
    private fun text(a:ConversationListActivity,value:String)=all(a.window.decorView).filterIsInstance<TextView>().any { it.isShown&&it.text.toString()==value }
    private fun search(a:ConversationListActivity)=all(a.window.decorView).filterIsInstance<EditText>().first { it.contentDescription=="Search calls" }

    @Test fun emptyHistoryHidesSearchAndUsesRealCallPicker()=fixture { db ->
        db.upsertConversation("CA1100000002","Available contact","",1,false)
        val a=launch()
        try {
            ui { nav(a,"Calls").performClick() }
            waitUi { text(a,"No calls yet") }
            ui {
                assertFalse(search(a).isShown)
                assertTrue(text(a,"Your call history will appear here."))
                assertFalse(text(a,"New chat"))
                all(a.window.decorView).filterIsInstance<TextView>().first { it.text=="Start a call" }.performClick()
            }
            waitUi { WindowInspector.getGlobalWindowViews().flatMap { all(it) }.filterIsInstance<TextView>().any { it.text.toString().startsWith("Available contact") } }
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui { a.onBackPressedDispatcher.onBackPressed();assertTrue(nav(a,"Chats").isSelected) }
        } finally { ui { a.finish() } }
    }

    @Test fun searchFiltersRealHistoryIncludingOlderRecordsAndSurvivesNavigation()=fixture { db ->
        db.logCallStart("+923001234567","Old ÁlIce","incoming")
        repeat(105) { db.logCallStart("bulk-$it","Other contact $it","outgoing") }
        db.logCallStart("CA1100000003","Video contact","outgoing video")
        db.logCallStart("CA1100000004","Missed contact","missed")
        val a=launch()
        try {
            ui { nav(a,"Calls").performClick() }
            waitUi { search(a).isShown&&text(a,"Video contact") }
            waitUi { search(a).height>0 }
            ui {
                assertEquals(RabtaUi.dp(a,44),(search(a).parent as View).height)
                assertFalse(a.currentFocus is EditText)
                search(a).setText("áLiCe")
            }
            waitUi { text(a,"Old ÁlIce")&&!text(a,"Video contact")&&!text(a,"Missed contact") }
            ui { search(a).setText("1234567") }
            waitUi { text(a,"Old ÁlIce") }
            ui { search(a).setText("No matching person") }
            waitUi { text(a,"No calls found") }
            ui {
                assertTrue(search(a).isShown)
                all(a.window.decorView).first { it.contentDescription=="Clear call search" }.performClick()
            }
            waitUi { text(a,"Video contact")&&text(a,"Missed contact") }
            ui {
                assertFalse(all(a.window.decorView).first { it.contentDescription=="Clear call search" }.isShown)
                search(a).setText("VIDEO")
                nav(a,"Chats").performClick();nav(a,"Calls").performClick()
            }
            waitUi { search(a).text.toString()=="VIDEO"&&text(a,"Video contact")&&!text(a,"Missed contact") }
            ui { search(a).requestFocus();a.onBackPressedDispatcher.onBackPressed();assertFalse(search(a).hasFocus());assertTrue(nav(a,"Calls").isSelected) }
            ui { a.onBackPressedDispatcher.onBackPressed();assertEquals("",search(a).text.toString());assertTrue(nav(a,"Calls").isSelected) }
            assertEquals(108,db.calls(Int.MAX_VALUE).size)
        } finally { ui { a.finish() } }
    }

    @Test fun callbackUsesExistingVoiceSignalingAndRetainsHistory()=fixture { db ->
        inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.RECORD_AUDIO").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val server=MockWebServer();val init=CountDownLatch(1);var to=""
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
            override fun onOpen(ws:WebSocket,response:Response) { ws.send("{\"type\":\"ready\",\"id\":\"CA1100000001\",\"protocol\":2}") }
            override fun onMessage(ws:WebSocket,raw:String) {
                val o=JSONObject(raw)
                if(o.optString("type")=="call_init") { to=o.optString("to");init.countDown() }
            }
        }))
        server.start();val url=server.url("/").toString().trimEnd('/')
        ui { Session.serverUrl=url }
        val original=db.logCallStart("CA1100000002","Call target","outgoing")
        val a=launch();val monitor=inst.addMonitor(ChatActivity::class.java.name,null,false)
        var chat:android.app.Activity?=null
        try {
            waitUi { WsClient.connected&&WsClient.protocol>=2 }
            ui { nav(a,"Calls").performClick() }
            waitUi { text(a,"Call target") }
            ui { all(a.window.decorView).first { it.contentDescription=="Call back Call target" }.performClick() }
            chat=inst.waitForMonitorWithTimeout(monitor,5000);assertNotNull(chat)
            assertTrue("Real call_init sent through existing WebSocket",init.await(5,TimeUnit.SECONDS))
            assertEquals("CA1100000002",to)
            assertTrue(db.calls(Int.MAX_VALUE).any { it.id==original })
            assertEquals("local-calls-test",Session.token)
        } finally {
            ui { chat?.finish();a.finish();WsClient.disconnect();MessageService.stop(ctx) }
            inst.removeMonitor(monitor);server.shutdown()
        }
    }
}
