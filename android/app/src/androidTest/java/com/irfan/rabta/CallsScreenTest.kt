package com.irfan.rabta

import android.app.Notification
import android.app.NotificationManager
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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
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
        // Complete permission setup before launch, so Android's permission Activity
        // cannot outlive a finished call and intercept subsequent home launches.
        val permissions=mutableListOf("ACCESS_COARSE_LOCATION","ACCESS_FINE_LOCATION","RECORD_AUDIO")
        if(android.os.Build.VERSION.SDK_INT>=31)permissions+=listOf("BLUETOOTH_SCAN","BLUETOOTH_ADVERTISE","BLUETOOTH_CONNECT")
        if(android.os.Build.VERSION.SDK_INT>=33)permissions+=listOf("NEARBY_WIFI_DEVICES","POST_NOTIFICATIONS")
        permissions.forEach { permission ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.$permission")).use { it.readBytes() }
        }
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
            inst.waitForIdleSync();db.close();ctx.deleteDatabase(name)
        }
    }
    private fun launch()=inst.startActivitySync(Intent(ctx,ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ConversationListActivity
    private fun nav(a:ConversationListActivity,label:String)=all(a.window.decorView).first { it is ViewGroup && it.contentDescription==label }
    private fun text(a:ConversationListActivity,value:String)=all(a.window.decorView).filterIsInstance<TextView>().any { it.isShown&&it.text.toString()==value }
    private fun search(a:ConversationListActivity)=all(a.window.decorView).filterIsInstance<EditText>().first { it.contentDescription=="Search calls" }
    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    /** Title of MessageService's own notification, which turns into the ongoing-call notice during calls. */
    private fun serviceNotice()=ctx.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id==1 }?.notification?.extras?.getString(Notification.EXTRA_TITLE)

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
        val old=db.logCallStart("+923001234567","Old ÁlIce","incoming")
        db.writableDatabase.execSQL("UPDATE calls SET started_at=1 WHERE id=?",arrayOf(old))
        repeat(105) { db.logCallStart("bulk-$it","Other contact $it","outgoing") }
        db.logCallStart("CA1100000003","Video contact","outgoing video")
        db.logCallStart("CA1100000004","Missed contact","missed")
        assertFalse(db.calls().any { it.id==old })
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
        // MessageService's initial network callback may retry a connecting socket.
        // Serve every upgrade so a cancelled attempt cannot consume the only reply.
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path!="/ws")return MockResponse().setResponseCode(404)
                return MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                    override fun onOpen(ws:WebSocket,response:Response) { ws.send("{\"type\":\"ready\",\"id\":\"CA1100000001\",\"protocol\":2}") }
                    override fun onMessage(ws:WebSocket,raw:String) {
                        val o=JSONObject(raw)
                        if(o.optString("type")=="call_init") { to=o.optString("to");init.countDown() }
                    }
                })
            }
        }
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

    /** The reported bug: a call went to reconnecting once the phone locked mid-call. The screen may go off; the call goes on. */
    @Test fun voiceCallGoesOnWhileTheScreenIsOff()=fixture { _ ->
        inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.RECORD_AUDIO").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val server=MockWebServer();val ended=CountDownLatch(1);val frames=java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path!="/ws")return MockResponse().setResponseCode(404)
                return MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                    override fun onOpen(ws:WebSocket,response:Response) { ws.send("{\"type\":\"ready\",\"id\":\"CA1100000001\",\"protocol\":2}") }
                    override fun onMessage(ws:WebSocket,raw:String) {
                        val o=JSONObject(raw);val id=o.optString("callId")
                        when(o.optString("type")) {
                            // The other phone answers and talks; its first audio frame connects the call.
                            "call_init" -> {
                                ws.send(JSONObject().put("type","call_state").put("from","CA1100000002").put("callId",id).put("state","accepted").toString())
                                ws.send(JSONObject().put("type","call_audio").put("from","CA1100000002").put("callId",id).put("data",android.util.Base64.encodeToString(ByteArray(640),android.util.Base64.NO_WRAP)).toString())
                            }
                            "call_audio" -> frames.incrementAndGet()
                            "call_end" -> ended.countDown()
                        }
                    }
                })
            }
        }
        server.start()
        ui { Session.serverUrl=server.url("/").toString().trimEnd('/');WsClient.connect(Session) }
        val power=ctx.getSystemService(android.os.PowerManager::class.java)
        fun connectedShown(c:ChatActivity)=all(c.window.decorView).filterIsInstance<TextView>().any { it.isShown&&it.text.startsWith("Connected") }
        var chat:ChatActivity?=null
        try {
            waitUi { WsClient.connected&&WsClient.protocol>=2 }
            chat=inst.startActivitySync(Intent(ctx,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("peerId","CA1100000002").putExtra("peerName","Lock contact").putExtra("requestedCallKind","voice")) as ChatActivity
            val c=chat!!
            waitUi { connectedShown(c) }
            waitUi { serviceNotice()=="Ongoing voice call" }
            // Emulators without audio input record nothing even on screen; then only the call's state is checked.
            val onScreen=frames.get();Thread.sleep(2000);val talking=frames.get()-onScreen>=25
            try {
                shell("input keyevent KEYCODE_SLEEP")
                waitUi { !power.isInteractive }
                val before=frames.get();Thread.sleep(3000)
                if(talking) assertTrue("Microphone audio keeps going out with the screen off (${frames.get()-before} frames in 3 s)",frames.get()-before>=25)
                assertEquals("The call was not ended",1L,ended.count)
                assertEquals("Ongoing voice call",serviceNotice())
                assertTrue("The phone is kept awake for the call",shell("dumpsys power").contains("Rabta:call"))
                if(android.os.Build.VERSION.SDK_INT>=30) Regex("(?:types|foregroundServiceType)=(?:0x)?([0-9a-fA-F]+)").find(shell("dumpsys activity services ${ctx.packageName}"))?.let {
                    assertTrue("Microphone kept while the screen is off (foreground type ${it.groupValues[1]})",it.groupValues[1].toLong(16) and 0x80L!=0L)
                }
            } finally {
                shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");shell("input keyevent 82")
            }
            waitUi { power.isInteractive&&connectedShown(c) }
            ui { all(c.window.decorView).first { it.contentDescription=="End" && it.isClickable }.performClick() }
            assertTrue("The other phone is told the call ended",ended.await(5,TimeUnit.SECONDS))
            waitUi { serviceNotice()=="Rabta" }
            assertFalse("The call no longer keeps the phone awake",shell("dumpsys power").contains("Rabta:call"))
        } finally {
            ui { chat?.finish();WsClient.disconnect();MessageService.stop(ctx) }
            server.shutdown()
        }
    }

    @Test fun messageServiceStartsAgainAfterRestartOrUpdate() {
        for(action in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) {
            val receivers=ctx.packageManager.queryBroadcastReceivers(Intent(action).setPackage(ctx.packageName),0)
            assertTrue("$action starts Rabta's message service",receivers.any { it.activityInfo.name==StartReceiver::class.java.name })
        }
    }

    @Test fun chatsOfferBackgroundRunUntilDismissed()=fixture { _ ->
        val prefs=ctx.getSharedPreferences("background_run",0);val saved=prefs.all
        prefs.edit().clear().commit()
        try {
            org.junit.Assume.assumeFalse("This emulator already lets Rabta run in the background",BackgroundRun.allowed(ctx))
            val a=launch()
            try {
                waitUi { text(a,"Messages may stop when Rabta is closed") }
                ui { assertTrue(all(a.window.decorView).any { it.isShown&&it.contentDescription=="Allow Rabta to run in the background" }) }
                ui { all(a.window.decorView).first { it.isShown&&it.contentDescription=="Dismiss" }.performClick() }
                waitUi { !text(a,"Messages may stop when Rabta is closed") }
                assertFalse("Dismissed for good; Settings keeps the controls",BackgroundRun.shouldOffer(ctx))
            } finally { ui { a.finish() } }
        } finally {
            prefs.edit().clear().apply { saved.forEach { (k,v) -> if(v is Boolean) putBoolean(k,v) } }.commit()
        }
    }
}
