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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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
            ui { VoiceCall.end(false);VideoCall.end();WsClient.disconnect();MessageService.stop(ctx);field.set(null,previous) }
            // A video call that ends on screen opens its chat: close whatever a test left open.
            inst.waitForIdleSync()
            ui { listOf(Stage.RESUMED,Stage.PAUSED,Stage.STARTED,Stage.STOPPED,Stage.CREATED).forEach { stage -> ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).forEach { it.finish() } } }
            inst.waitForIdleSync();db.close();ctx.deleteDatabase(name)
        }
    }
    private fun launch()=inst.startActivitySync(Intent(ctx,ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ConversationListActivity
    private fun nav(a:ConversationListActivity,label:String)=all(a.window.decorView).first { it is ViewGroup && it.contentDescription==label }
    private fun text(a:ConversationListActivity,value:String)=all(a.window.decorView).filterIsInstance<TextView>().any { it.isShown&&it.text.toString()==value }
    private fun search(a:ConversationListActivity)=all(a.window.decorView).filterIsInstance<EditText>().first { it.contentDescription=="Search calls" }
    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    /** Wake locks held right now; newer Android also logs released ones further down the same dump. */
    private fun heldWakeLocks()=shell("dumpsys power").let { dump -> val start=dump.indexOf("Wake Locks: size=");if(start<0)"" else dump.substring(start,dump.indexOf("\n\n",start).takeIf { it>0 } ?: dump.length) }
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
        server.start();val url=server.url("/").toString().trimEnd('/')
        ui { Session.serverUrl=url;WsClient.connect(Session) }
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
                assertTrue("The phone is kept awake for the call",heldWakeLocks().contains("Rabta:call"))
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
            assertFalse("The call no longer keeps the phone awake",heldWakeLocks().contains("Rabta:call"))
        } finally {
            ui { chat?.finish();WsClient.disconnect();MessageService.stop(ctx) }
            server.shutdown()
        }
    }

    /** A mock server whose other phone answers a voice call at once and talks; [ended] counts the hang-ups it is sent. */
    private fun answeringServer(ended:CountDownLatch,offered:CountDownLatch?=null)=MockWebServer().apply {
        dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path!="/ws")return MockResponse().setResponseCode(404)
                return MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                    override fun onOpen(ws:WebSocket,response:Response) { ws.send("{\"type\":\"ready\",\"id\":\"CA1100000001\",\"protocol\":2}") }
                    override fun onMessage(ws:WebSocket,raw:String) {
                        val o=JSONObject(raw);val id=o.optString("callId")
                        when(o.optString("type")) {
                            "call_init" -> {
                                ws.send(JSONObject().put("type","call_state").put("from","CA1100000002").put("callId",id).put("state","accepted").toString())
                                ws.send(JSONObject().put("type","call_audio").put("from","CA1100000002").put("callId",id).put("data",android.util.Base64.encodeToString(ByteArray(640),android.util.Base64.NO_WRAP)).toString())
                            }
                            "video_offer" -> offered?.countDown()
                            "call_end","video_end" -> ended.countDown()
                        }
                    }
                })
            }
        }
    }
    private fun shows(a:android.app.Activity,words:String)=all(a.window.decorView).filterIsInstance<TextView>().any { it.isShown&&it.text.toString().contains(words) }

    /** The reported bug: going back to the chats or opening another chat ended the call. It goes on, and the green bar returns to it. */
    @Test fun voiceCallGoesOnWhileYouUseOtherChats()=fixture { _ ->
        val ended=CountDownLatch(1);val server=answeringServer(ended)
        server.start();val url=server.url("/").toString().trimEnd('/')
        ui { Session.serverUrl=url;WsClient.connect(Session) }
        var chat:ChatActivity?=null;var other:ChatActivity?=null;var back:android.app.Activity?=null
        try {
            waitUi { WsClient.connected&&WsClient.protocol>=2 }
            chat=inst.startActivitySync(Intent(ctx,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("peerId","CA1100000002").putExtra("peerName","Busy contact").putExtra("requestedCallKind","voice")) as ChatActivity
            val c=chat!!
            waitUi { VoiceCall.connectedAt>0&&shows(c,"Connected") }
            // Back on the call screen shows the chat underneath, with the green bar; back again closes the chat.
            ui { c.onBackPressed() }
            waitUi { shows(c,"Tap to return to call")&&!shows(c,"Connected") }
            ui { c.onBackPressed() }
            waitUi { c.isFinishing||c.isDestroyed }
            Thread.sleep(500)
            assertTrue("The call goes on without its chat",VoiceCall.active)
            assertEquals("Nobody hung up",1L,ended.count)
            // Another chat shows the call going on; its bar leads back to the call screen.
            other=inst.startActivitySync(Intent(ctx,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("peerId","CA1100000003").putExtra("peerName","Other contact")) as ChatActivity
            val o=other!!
            waitUi { shows(o,"Busy contact")&&shows(o,"Tap to return to call") }
            ui { all(o.window.decorView).first { it.isShown&&it.contentDescription?.toString()?.startsWith("Ongoing call with")==true }.performClick() }
            // Whichever screen shows the call now: the call screen with its time and End.
            waitUi {
                back=WindowInspector.getGlobalWindowViews().flatMap { all(it) }.firstOrNull { it.isShown&&it.contentDescription=="End" }?.context as? android.app.Activity
                back?.let { shows(it,"Connected") }==true
            }
            val b=back!!
            assertTrue("The call screen belongs to the call's chat",b is ChatActivity&&b.intent.getStringExtra("peerId")=="CA1100000002")
            assertEquals("Still one call, never hung up",1L,ended.count)
            // The ongoing call's notice can hang up from anywhere.
            val notice=ctx.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id==1 }.notification
            assertEquals("Ongoing voice call",notice.extras.getString(Notification.EXTRA_TITLE))
            notice.actions.first { it.title.toString()=="Hang up" }.actionIntent.send()
            assertTrue("Hang up tells the other phone",ended.await(5,TimeUnit.SECONDS))
            waitUi { !VoiceCall.active&&!shows(b,"Tap to return to call")&&!shows(b,"Connected") }
        } finally {
            ui { back?.finish();other?.finish();chat?.finish();VoiceCall.end(false);WsClient.disconnect();MessageService.stop(ctx) }
            server.shutdown()
        }
    }

    /**
     * The reported problem: calls dropped whenever the connection blinked. An answered voice call waits through a short
     * drop; once Rabta is connected again by itself it tells the server the call goes on, and nobody hung up.
     */
    @Test fun voiceCallGoesOnThroughAShortDrop()=fixture { _ ->
        val ended=CountDownLatch(1);val alive=CountDownLatch(1);val sockets=java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
        val server=MockWebServer().apply { dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path!="/ws")return MockResponse().setResponseCode(404)
                return MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                    override fun onOpen(ws:WebSocket,response:Response) { sockets.add(ws);ws.send("{\"type\":\"ready\",\"id\":\"CA1100000001\",\"protocol\":2}") }
                    override fun onMessage(ws:WebSocket,raw:String) {
                        val o=JSONObject(raw);val id=o.optString("callId")
                        when(o.optString("type")) {
                            "call_init" -> {
                                ws.send(JSONObject().put("type","call_state").put("from","CA1100000002").put("callId",id).put("state","accepted").toString())
                                ws.send(JSONObject().put("type","call_audio").put("from","CA1100000002").put("callId",id).put("data",android.util.Base64.encodeToString(ByteArray(640),android.util.Base64.NO_WRAP)).toString())
                            }
                            "call_alive" -> if(id==VoiceCall.id&&o.optString("to")=="CA1100000002") alive.countDown()
                            "call_end" -> ended.countDown()
                        }
                    }
                })
            }
        } }
        server.start();val url=server.url("/").toString().trimEnd('/')
        ui { Session.serverUrl=url;WsClient.connect(Session) }
        var chat:ChatActivity?=null
        try {
            waitUi { WsClient.connected&&WsClient.protocol>=2 }
            chat=inst.startActivitySync(Intent(ctx,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("peerId","CA1100000002").putExtra("peerName","Busy contact").putExtra("requestedCallKind","voice")) as ChatActivity
            val c=chat!!
            waitUi { VoiceCall.connectedAt>0&&shows(c,"Connected") }
            // The connection drops, as when the network blinks or the server restarts: the call waits instead of ending.
            sockets.last().close(1001,"Going away")
            waitUi { !WsClient.connected&&VoiceCall.active&&shows(c,"Reconnecting") }
            assertTrue("Rabta connects again and says the call goes on",alive.await(20,TimeUnit.SECONDS))
            waitUi { WsClient.connected&&VoiceCall.active&&shows(c,"Connected") }
            assertEquals("Nobody hung up",1L,ended.count)
            assertEquals("Connected again once",2,sockets.size)
        } finally {
            ui { chat?.finish();VoiceCall.end(false);WsClient.disconnect();MessageService.stop(ctx) }
            server.shutdown()
        }
    }

    /** Back turns a video call into a small window over its chat; with that window closed, the chat leads back to it. */
    @Test fun videoCallGoesOnInASmallWindowAndBehindTheChats()=fixture { _ ->
        listOf("CAMERA","RECORD_AUDIO").forEach { shell("pm grant ${ctx.packageName} android.permission.$it") }
        val ended=CountDownLatch(1);val offered=CountDownLatch(1);val server=answeringServer(ended,offered)
        server.start();val url=server.url("/").toString().trimEnd('/')
        ui { Session.serverUrl=url;WsClient.connect(Session) }
        var video:VideoCallActivity?=null;var again:android.app.Activity?=null;var behind:android.app.Activity?=null
        try {
            waitUi { WsClient.connected&&WsClient.protocol>=2 }
            video=inst.startActivitySync(Intent(ctx,VideoCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(VideoCallActivity.PEER_ID,"CA1100000002").putExtra(VideoCallActivity.PEER_NAME,"Video contact")
                .putExtra(VideoCallActivity.IS_CALLER,true).putExtra(VideoCallActivity.CALL_ID,"BBBBBB000001")) as VideoCallActivity
            val v=video!!
            waitUi { VideoCall.active }
            assertTrue("The video offer goes out",offered.await(20,TimeUnit.SECONDS))
            waitUi { serviceNotice()=="Ongoing video call" }
            // Asking for the screen-sharing type without Android's consent (refused on Android 14 and up) breaks nothing.
            ui { MessageService.sharingStarted();MessageService.sharingEnded() }
            assertEquals("Ongoing video call",serviceNotice())
            assertTrue(VideoCall.active)
            org.junit.Assume.assumeTrue("This emulator offers picture-in-picture",ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))
            // The reported bug: back from the call (once it had been small) left Rabta. Like WhatsApp, back shows the
            // chat with that person under the small window, also when Rabta was not open.
            val chats=inst.addMonitor(ChatActivity::class.java.name,null,false)
            ui { v.onBackPressed() }
            waitUi { v.isInPictureInPictureMode }
            behind=try { inst.waitForMonitorWithTimeout(chats,10000) } finally { inst.removeMonitor(chats) }
            val l=behind!!
            assertTrue("Back leads to the chat with that person",l is ChatActivity&&l.intent.getStringExtra("peerId")=="CA1100000002")
            waitUi { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).contains(l) }
            assertTrue("The call goes on in the small window",VideoCall.active)
            assertEquals("Nobody hung up",1L,ended.count)
            // The window closed: the call still goes on, and the chat shows the green bar back to it.
            ui { v.finish() }
            waitUi { v.isDestroyed }
            waitUi { shows(l,"Tap to return to call")&&shows(l,"Video contact") }
            assertTrue(VideoCall.active)
            val monitor=inst.addMonitor(VideoCallActivity::class.java.name,null,false)
            try {
                ui { all(l.window.decorView).first { it.isShown&&it.contentDescription?.toString()?.startsWith("Ongoing call with")==true }.performClick() }
                again=inst.waitForMonitorWithTimeout(monitor,5000)
            } finally { inst.removeMonitor(monitor) }
            val a=again!!
            waitUi { all(a.window.decorView).any { it.isShown&&it.contentDescription=="End call" } }
            ui { all(a.window.decorView).first { it.isShown&&it.contentDescription=="End call" }.performClick() }
            assertTrue("End tells the other phone",ended.await(5,TimeUnit.SECONDS))
            waitUi { !VideoCall.active&&(a.isFinishing||a.isDestroyed) }
        } finally {
            ui { again?.finish();behind?.finish();video?.finish();VideoCall.end();WsClient.disconnect();MessageService.stop(ctx) }
            server.shutdown()
        }
    }

    /** Filters and background blur change your camera during a call; the camera keeps going and the call ends cleanly. */
    @Test fun videoEffectsChangeTheCameraWithoutStoppingIt()=fixture { _ ->
        listOf("CAMERA","RECORD_AUDIO").forEach { shell("pm grant ${ctx.packageName} android.permission.$it") }
        val ended=CountDownLatch(1);val offered=CountDownLatch(1);val server=answeringServer(ended,offered)
        server.start();val url=server.url("/").toString().trimEnd('/')
        ui { Session.serverUrl=url;WsClient.connect(Session) }
        var video:VideoCallActivity?=null
        try {
            waitUi { WsClient.connected&&WsClient.protocol>=2 }
            video=inst.startActivitySync(Intent(ctx,VideoCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(VideoCallActivity.PEER_ID,"CA1100000002").putExtra(VideoCallActivity.PEER_NAME,"Video contact")
                .putExtra(VideoCallActivity.IS_CALLER,true).putExtra(VideoCallActivity.CALL_ID,"BBBBBB000002")) as VideoCallActivity
            val v=video!!
            waitUi { VideoCall.active }
            assertTrue("The video offer goes out",offered.await(20,TimeUnit.SECONDS))
            fun press(description:String) { var t:View?=null;waitUi { t=all(v.window.decorView).firstOrNull { it.isShown&&it.contentDescription?.toString()==description };t!=null };ui { t!!.performClick() } }
            press("Filters and background blur")
            press("Warm filter")
            waitUi { VideoEffects.filter==VideoFilter.WARM }
            press("Background blur, off")
            waitUi { VideoEffects.blur }
            val before=VideoEffects.processed
            Thread.sleep(3000)
            org.junit.Assume.assumeTrue("This emulator's camera sends frames",VideoEffects.processed>before)
            assertEquals("Every frame took the effects",0L,VideoEffects.failures)
            ui { all(v.window.decorView).first { it.isShown&&it.contentDescription=="End call" }.performClick() }
            assertTrue("End tells the other phone",ended.await(5,TimeUnit.SECONDS))
            waitUi { !VideoCall.active }
            assertEquals("Effects end with the call",VideoFilter.NORMAL,VideoEffects.filter);assertFalse(VideoEffects.blur)
        } finally {
            ui { video?.finish();VideoCall.end();WsClient.disconnect();MessageService.stop(ctx) }
            server.shutdown()
        }
    }

    /** The fixture account's server: counts connections in [opened] and answers Rabta's ping only while [answering]. */
    private fun pingServer(opened:java.util.concurrent.atomic.AtomicInteger,answering:java.util.concurrent.atomic.AtomicBoolean)=MockWebServer().apply {
        dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path!="/ws")return MockResponse().setResponseCode(404)
                return MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                    override fun onOpen(ws:WebSocket,response:Response) { opened.incrementAndGet();ws.send("{\"type\":\"ready\",\"id\":\"CA1100000001\",\"protocol\":2,\"receipts\":true}") }
                    override fun onMessage(ws:WebSocket,raw:String) { if(JSONObject(raw).optString("type")=="ping"&&answering.get()) ws.send("{\"type\":\"pong\"}") }
                })
            }
        }
        start()
    }

    /**
     * The reported bug: with Rabta closed its connection died unnoticed (no messages, no calls), and opening Rabta took
     * a minute to reconnect. The keep-alive alarm (and opening Rabta) now checks the connection and replaces a dead one.
     */
    @Test fun keepAliveReplacesAConnectionThatStoppedAnswering()=fixture { _ ->
        val opened=java.util.concurrent.atomic.AtomicInteger();val answering=java.util.concurrent.atomic.AtomicBoolean(true)
        val server=pingServer(opened,answering);val url=server.url("/").toString().trimEnd('/')
        ui { Session.serverUrl=url;WsClient.connect(Session) }
        fun keepAlive()=ctx.sendBroadcast(Intent(ctx,StartReceiver::class.java).setAction(MessageService.KEEP_ALIVE))
        fun waitFor(ms:Long,check:()->Boolean) { val until=System.currentTimeMillis()+ms;while(!check()) { if(System.currentTimeMillis()>until) fail("Connection did not reach the expected state");Thread.sleep(100) } }
        try {
            waitUi { WsClient.connected }
            assertEquals(1,opened.get())
            // Quiet for a while, but alive: the check gets its answer and the connection is kept.
            Thread.sleep(21_000);keepAlive();Thread.sleep(8_000)
            assertEquals("A connection that answers is kept",1,opened.get());assertTrue(WsClient.connected)
            // The connection stops answering (its network died while the phone slept): the next check replaces it.
            answering.set(false);Thread.sleep(21_000);keepAlive()
            waitFor(20_000) { opened.get()==2&&WsClient.connected }
        } finally {
            ui { WsClient.disconnect();MessageService.stop(ctx) }
            server.shutdown()
        }
    }

    /**
     * The reported problem: opening Rabta showed "Connecting to server" for seconds. A connection that went quiet while
     * Rabta was closed gets a short check when Rabta opens and is replaced at once, without the offline strip showing.
     */
    @Test fun openingRabtaReplacesAQuietConnectionQuicklyAndUnseen()=fixture { _ ->
        val opened=java.util.concurrent.atomic.AtomicInteger();val answering=java.util.concurrent.atomic.AtomicBoolean(true)
        val server=pingServer(opened,answering);val url=server.url("/").toString().trimEnd('/')
        ui { Session.serverUrl=url;WsClient.connect(Session) }
        try {
            waitUi { WsClient.connected }
            // Rabta was closed for a while and its connection died unnoticed.
            answering.set(false);Thread.sleep(21_000)
            val opening=System.currentTimeMillis();var shownOffline=false
            ui { WsClient.wake(onScreen=true) }
            while(!(opened.get()==2&&WsClient.connected)) {
                ui { shownOffline=shownOffline||WsClient.showsOffline() }
                // Before: a 6 s check, then a 2 s wait before connecting again.
                assertTrue("New connection within seconds of opening Rabta",System.currentTimeMillis()-opening<7_000)
                Thread.sleep(50)
            }
            assertFalse("A quick reconnect does not show the offline strip",shownOffline)
        } finally {
            ui { WsClient.disconnect();MessageService.stop(ctx) }
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
