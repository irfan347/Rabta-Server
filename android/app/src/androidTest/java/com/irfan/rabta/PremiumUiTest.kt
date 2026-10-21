package com.irfan.rabta

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** All data and servers in this class are isolated emulator fixtures, never production users. */
@RunWith(AndroidJUnit4::class)
class PremiumUiTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private val screens=mutableListOf<Activity>()
    private fun ui(block:()->Unit)=inst.runOnMainSync(block)
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))}else emptyList()
    private fun labels(a:Activity)=all(a.window.decorView).filterIsInstance<TextView>().filter {it.isShown}.map {it.text.toString()}
    private fun waitUi(message:String,test:()->Boolean) {
        val end=System.currentTimeMillis()+12000
        do {inst.waitForIdleSync();var ok=false;ui {ok=test()};if(ok)return;Thread.sleep(80)}while(System.currentTimeMillis()<end)
        fail(message)
    }
    private fun launch(type:Class<out Activity>,id:String?=null):Activity {
        val intent=Intent(ctx,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(id!=null)intent.putExtra("statusId",id)
        return inst.startActivitySync(intent).also {screens.add(it);inst.waitForIdleSync()}
    }
    private fun click(value:String) {
        val until=System.currentTimeMillis()+8000
        do {
            val node=inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(value)?.firstOrNull {it.text?.toString()?.equals(value,true)==true}
            var target=node
            repeat(4) {
                if(target?.isClickable==true&&target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))return
                target=target?.parent
            }
            Thread.sleep(80)
        }while(System.currentTimeMillis()<until)
        fail("Real UI action unavailable: $value")
    }
    /** Clicks the control described as [description] (an icon button has no text) in the active window. */
    private fun press(description:String) {
        val until=System.currentTimeMillis()+8000
        do {
            val node=inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(description)?.firstOrNull {it.contentDescription?.toString()==description}
            if(node?.isClickable==true&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return
            Thread.sleep(80)
        }while(System.currentTimeMillis()<until)
        fail("Real UI action unavailable: $description")
    }
    private fun setDialogText(value:String) {
        fun editor(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(n.className?.toString()==EditText::class.java.name)return n
            for(i in 0 until n.childCount)n.getChild(i)?.let {editor(it)}?.let {return it}
            return null
        }
        // The dialog opens after the previous screen returns its result; wait for it to become the active window, like click().
        val until=System.currentTimeMillis()+8000
        var node:AccessibilityNodeInfo?=null
        while(node==null&&System.currentTimeMillis()<until) {node=inst.uiAutomation.rootInActiveWindow?.let {editor(it)};if(node==null)Thread.sleep(80)}
        if(node==null)error("No dialog editor")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}))
    }
    private fun capture(name:String) {
        inst.waitForIdleSync();Thread.sleep(300);inst.waitForIdleSync()
        val dir=File(ctx.getExternalFilesDir(null),"premium-captures").apply {mkdirs()}
        val shot=inst.captureScreen()?:return
        val scaled=Bitmap.createScaledBitmap(shot,360,(shot.height*360f/shot.width).toInt(),true)
        File(dir,"premium-$name.jpg").outputStream().use {scaled.compress(Bitmap.CompressFormat.JPEG,78,it)}
        if(scaled!==shot)scaled.recycle();shot.recycle()
        val names=listOf("nearby","profile","status","network")
        val available=names.map {File(dir,"premium-$it.jpg")}
        if(available.all {it.exists()}) {
            val sheet=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(sheet);canvas.drawColor(0xFF0B1020.toInt())
            available.forEachIndexed {index,file ->
                val bmp=android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                canvas.drawBitmap(bmp,null,android.graphics.Rect((index%2)*360,(index/2)*640,(index%2+1)*360,(index/2+1)*640),Paint(3));bmp.recycle()
            }
            val bytes=java.io.ByteArrayOutputStream().apply {sheet.compress(Bitmap.CompressFormat.JPEG,72,this)}.toByteArray();sheet.recycle()
            File(dir,"premium-review.jpg").writeBytes(bytes)
            File(dir,"premium-review.b64").writeText(android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
        }
        // Use the instrumentation shell for export; production requests no broad storage permission.
        val commands=listOf("mkdir -p /sdcard/Download/rabta-interface")+dir.listFiles().orEmpty().map {"cp ${it.absolutePath} /sdcard/Download/rabta-interface/${it.name}"}
        commands.forEach {command->android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use {it.readBytes()}}
    }
    private fun fixture(run:(Db,String)->Unit) {
        val account=java.util.UUID.randomUUID().toString().replace("-","").take(12).uppercase()
        Session.init(ctx)
        val old=listOf(Session.id,Session.name,Session.username,Session.token,Session.serverUrl,Session.profilePic)
        val prefs=ctx.getSharedPreferences("appearance",0)
        val oldMode=prefs.getString("mode",null);val oldLanguage=prefs.getString("language",null);val oldScale=prefs.getFloat("scale",1f)
        val database="premium-ui-$account.db"
        val isolated=object:ContextWrapper(ctx) {
            override fun getApplicationContext():Context=this
            override fun getDatabasePath(ignored:String)=ctx.getDatabasePath(database)
            override fun openOrCreateDatabase(ignored:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=ctx.openOrCreateDatabase(database,mode,factory)
            override fun openOrCreateDatabase(ignored:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?)=ctx.openOrCreateDatabase(database,mode,factory,handler)
        }
        val field=Db::class.java.getDeclaredField("INST").apply {isAccessible=true};val previous=field.get(null);val db=Db(isolated)
        ui {
            MessageService.stop(ctx);WsClient.disconnect();Session.id=account;Session.name="Irfan";Session.username="premium_fixture";Session.token="local-only-fixture";Session.serverUrl="https://127.0.0.1:8443";Session.profilePic=""
            field.set(null,db)
        }
        prefs.edit().putString("mode","Dark").putString("language","en").putFloat("scale",1f).commit()
        try {run(db,account)}finally {
            ui {screens.asReversed().forEach {it.finish()};MessageService.stop(ctx);WsClient.disconnect();MeshBridge.detach()}
            inst.waitForIdleSync()
            ui {field.set(null,previous);Session.id=old[0];Session.name=old[1];Session.username=old[2];Session.token=old[3];Session.serverUrl=old[4];Session.profilePic=old[5]}
            db.close();ctx.deleteDatabase(database)
            ctx.getSharedPreferences("radar_$account",0).edit().clear().commit()
            ctx.getSharedPreferences("mesh_keys_$account",0).edit().clear().commit()
            ctx.getSharedPreferences("privacy_$account",0).edit().clear().commit()
            ctx.deleteDatabase("mesh_v2_$account.db")
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry("rabta_mesh_v2_$account")}
            prefs.edit().putString("mode",oldMode).putString("language",oldLanguage).putFloat("scale",oldScale).commit()
        }
    }
    private class Radio:MeshTransport {
        override val connected=ConcurrentHashMap<String,String>()
        override val userIdOf=ConcurrentHashMap<String,String>()
        val peers=CopyOnWriteArrayList<RadarPeer>()
        @Volatile override var active=false
        override val discoveredCount get()=peers.size
        override val connectingCount=0
        override fun start(name:String,userId:String){active=true;MeshBridge.status="Nearby searching — keep both phones open"}
        override fun stop(){active=false}
        override fun discoveredPeerIds()=peers.map {it.id}.toSet()
        override fun radarPeers()=peers.toList()
        override fun retryConnections() {}
        override fun connect(endpointId:String){error("UI must not connect without approval")}
        override fun sendEnvelopeToAll(json:String,exceptEndpoint:String?) {}
        override fun sendEnvelopeTo(endpointId:String,json:String) {}
    }
    /**
     * Starting a router first creates the fixture account's RSA identity key in the Android Keystore.
     * On a busy emulator that alone can outlast a UI wait, so the radio gets its own wait.
     */
    private fun awaitRadio(router:Router,radio:Radio) {
        val end=System.currentTimeMillis()+60000
        while(!radio.active&&router.running&&System.currentTimeMillis()<end)Thread.sleep(50)
        assertTrue("Isolated radio fixture started",radio.active)
    }
    private fun closeRouter(router:Router) {
        ui {router.stop();MeshBridge.detach()}
        val worker=Router::class.java.getDeclaredField("worker").apply {isAccessible=true}.get(router) as java.util.concurrent.ExecutorService
        assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS))
    }
    /** Rabta's real MessageService has run its onDestroy, which detaches whatever Nearby router is attached. */
    private fun serviceGone()=MessageService::class.java.getDeclaredField("running").apply {isAccessible=true}.get(null)==null
    @Test fun nearbySearchFiltersLiveObservedPeersAndBackPreservesOptIn()=fixture {_,account ->
        val a=launch(NearbyActivity::class.java) as NearbyActivity
        waitUi("Nearby lifecycle resumed") {androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).contains(a)}
        ui {
            val manager=ctx.getSystemService(android.app.NotificationManager::class.java)
            assertEquals(android.app.NotificationManager.IMPORTANCE_LOW,manager.getNotificationChannel(MessageService.SERVICE_CHANNEL).importance)
            assertTrue(manager.getNotificationChannel(MessageService.MSG_CHANNEL).importance>=android.app.NotificationManager.IMPORTANCE_DEFAULT)
        }
        ui {MessageService.stop(ctx)};inst.waitForIdleSync()
        waitUi("Real foreground service stopped before attaching an isolated radio fixture") {
            @Suppress("DEPRECATION")
            val services=(ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningServices(Int.MAX_VALUE)
            // Android stops listing the service before the app has run its onDestroy: a late one would detach the fixture.
            services.none {it.service.className==MessageService::class.java.name}&&serviceGone()
        }
        val radio=Radio();radio.peers.addAll(listOf(RadarPeer("a","AAAABBBBCCCC","Ali"),RadarPeer("b","DDDDEEEEFFFF","Samira")))
        val router=Router(ctx,object:Router.UiListener {}) {radio}
        ui {RadarPrefs.setEnabled(ctx,true);router.start(account,"Irfan");MeshBridge.attach(router)}
        try {
            awaitRadio(router,radio)
            try { waitUi("Real peers appear") {radio.active&&labels(a).contains("Ali")&&labels(a).contains("Samira")} }
            catch(e:AssertionError) { var seen="";ui {seen="real service running: ${!serviceGone()}, Nearby shows: ${labels(a)}"};throw AssertionError("${e.message} ($seen)") }
            val search=all(a.window.decorView).filterIsInstance<EditText>().first {it.contentDescription=="Search nearby users"}
            ui {search.setText("aLI")}
            waitUi("Case-insensitive nearby search") {labels(a).contains("Ali")&&!labels(a).contains("Samira")}
            ui {search.setText("nothing")}
            waitUi("No-results state") {labels(a).contains("No nearby matches")}
            ui {search.requestFocus();a.onBackPressedDispatcher.onBackPressed();assertFalse(search.hasFocus());assertEquals("nothing",search.text.toString());a.onBackPressedDispatcher.onBackPressed();assertEquals("",search.text.toString());assertTrue(RadarPrefs.enabled(ctx))}
            waitUi("Clear restores actual users") {labels(a).contains("Samira")}
            capture("nearby")
        }finally {ui {a.finish()};inst.waitForIdleSync();closeRouter(router)}
    }
    @Test fun profileKeepsCircularAvatarCopiesRealIdAndOffersRealNameEditing()=fixture {_,account ->
        val a=launch(ProfileActivity::class.java)
        waitUi("Profile loaded") {labels(a).contains(account)}
        ui {
            val avatar=all(a.window.decorView).filterIsInstance<ImageView>().first {it.contentDescription.toString().contains("profile",true)}
            assertTrue(avatar.clipToOutline);assertTrue(avatar.background is android.graphics.drawable.GradientDrawable)
            assertTrue(labels(a).contains("Change any time · up to 30 characters"));assertTrue(labels(a).contains("Edit"))
            val label=all(a.window.decorView).filterIsInstance<TextView>().first {it.text=="Rabta ID"}
            (label.parent.parent as View).performClick()
            val clipboard=ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(account,clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(account,Session.id);assertEquals("Irfan",Session.name)
        }
        capture("profile")
    }
    @Test fun statusPreviewsViewerAndCreationUseAuthenticatedApiAndSelectedAudience()=fixture {db,account ->
        val server=MockWebServer();val posted=AtomicReference<JSONObject>();val views=AtomicInteger();val files=AtomicInteger();val allow=AtomicBoolean(true)
        val peer="AAAABBBBCCCC";db.upsertConversation(peer,"Ali","",1,false);db.upsertConversation("DDDDEEEEFFFF","Sara","",2,false)
        val bitmap=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply {eraseColor(0xFF35C9BC.toInt())}
        val output=java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,output);bitmap.recycle();val bytes=output.toByteArray()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val photoId="1".repeat(32);val remote="2".repeat(32)
        val media=JSONObject().put("remote",remote).put("name","fixture.png").put("mime","image/png").put("size",bytes.size).put("sha256",hash).toString()
        val now=System.currentTimeMillis()
        fun record(id:String,kind:String,body:String,expires:Long)=JSONObject().put("id",id).put("owner",peer).put("name","Ali").put("created",now).put("expires",expires).put("kind",kind).put("body",body)
        server.dispatcher=object:Dispatcher() {override fun dispatch(r:RecordedRequest):MockResponse {
            assertEquals("Bearer local-only-fixture",r.getHeader("Authorization"))
            return when {
                r.path=="/api/status"&&r.method=="GET"->MockResponse().setBody(JSONObject().put("statuses",if(allow.get())JSONArray().put(record(photoId,"image",media,now+600000)).put(record("3".repeat(32),"text","Expired secret",now-1))else JSONArray()).toString())
                r.path=="/api/files/$remote"->{files.incrementAndGet();MockResponse().setBody(okio.Buffer().write(bytes))}
                r.path=="/api/status/$photoId/view"->{views.incrementAndGet();MockResponse().setBody("{\"ok\":true}")}
                r.path=="/api/status"&&r.method=="POST"->{posted.set(JSONObject(r.body.readUtf8()));MockResponse().setBody("{\"id\":\"${"4".repeat(32)}\",\"expires\":${now+86400000}}")}
                r.path=="/ws"->MockResponse().withWebSocketUpgrade(object:okhttp3.WebSocketListener() {
                    override fun onOpen(ws:okhttp3.WebSocket,response:okhttp3.Response) { ws.send("{\"type\":\"ready\",\"id\":\"$account\",\"protocol\":2}") }
                })
                else->MockResponse().setResponseCode(404)
            }
        }}
        server.start();Session.serverUrl=server.url("/").toString().trimEnd('/')
        try {
            val a=launch(StatusActivity::class.java)
            waitUi("Authenticated real image preview") {all(a.window.decorView).filterIsInstance<StatusMediaPreview>().any {preview->all(preview).filterIsInstance<ImageView>().any {it.drawable is BitmapDrawable}}}
            ui {assertFalse(labels(a).contains("Expired secret"))};assertTrue(files.get()>0);capture("status")
            // Text updates open WhatsApp's full-screen composer: words on a colour, with colour and font to change.
            val composer=inst.addMonitor(TextStatusActivity::class.java.name,null,false)
            click("Add status");inst.waitForIdleSync();click("Text")
            val text=inst.waitForMonitorWithTimeout(composer,5000);inst.removeMonitor(composer);assertNotNull("Full-screen text composer",text)
            setDialogText("A real private moment")
            var before=0
            ui {
                before=(text!!.window.decorView.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0).let { (it.background as android.graphics.drawable.ColorDrawable).color }
                all(text.window.decorView).first {it.contentDescription=="Change colour"}.performClick()
                all(text.window.decorView).first {it.contentDescription=="Change font"}.performClick()
                assertNotEquals("Palette changes the colour",before,((text.window.decorView.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0).background as android.graphics.drawable.ColorDrawable).color)
                all(text.window.decorView).first {it.contentDescription=="Emoji"}.performClick()
                assertTrue("Emoji open in the keyboard's place",all(text.window.decorView).any {it is EmojiPanel&&it.isShown})
                all(text.window.decorView).first {it.contentDescription=="Send status"}.performClick()
            }
            // Who can see it: only Ali, chosen in the people picker; Sara is left out.
            click("Only share with…");click("Ali");press("Done");click("Share")
            waitUi("Creation reaches real API") {posted.get()!=null}
            assertEquals("text",posted.get().getString("kind"));assertEquals("A real private moment",posted.get().getString("body"));assertEquals(listOf(peer),(0 until posted.get().getJSONArray("audience").length()).map {posted.get().getJSONArray("audience").getString(it)})
            assertTrue("Chosen colour is sent",Regex("#[0-9A-F]{6}").matches(posted.get().getString("bg")));assertEquals("serif",posted.get().getString("font"))
            ui {WsClient.connect(Session)}
            val viewer=launch(StatusViewerActivity::class.java,photoId)
            waitUi("Viewer shows actual photo") {all(viewer.window.decorView).filterIsInstance<ImageView>().any {it.contentDescription=="Status photo"&&it.drawable is BitmapDrawable}}
            assertTrue(views.get()>0)
            // A quick emoji reply quotes the photo update with its owner, kind and a small picture.
            waitUi("Signed in for replies") {WsClient.connected}
            ui {all(viewer.window.decorView).first {it.contentDescription=="Reply with 😍"}.performClick()}
            waitUi("Emoji reply saved as a chat message to the owner") {db.messages(peer).any {it.mine&&it.msgType=="rich"}}
            val reply=JSONObject(db.messages(peer).first {it.mine&&it.msgType=="rich"}.body)
            assertEquals("😍",reply.getString("text"));assertEquals("Ali",reply.getJSONObject("status").getString("name"))
            assertEquals("image",reply.getJSONObject("status").getString("kind"));assertTrue("Photo thumbnail",reply.getJSONObject("status").getString("thumb").isNotEmpty())
            ui {viewer.finish()};inst.waitForIdleSync();allow.set(false)
            val denied=launch(StatusViewerActivity::class.java,photoId)
            waitUi("Revoked audience never reopens cached photo") {labels(denied).contains("Status unavailable")}
            ui {assertFalse(all(denied.window.decorView).filterIsInstance<ImageView>().any {it.drawable is BitmapDrawable})}
        }finally {ui {screens.forEach {it.finish()}};inst.waitForIdleSync();server.shutdown()}
    }
    @Test fun viewerRetryAndExpiryRemovePreviouslyVisibleContent()=fixture {_,_ ->
        val server=MockWebServer();val fail=AtomicBoolean(true);val id="5".repeat(32)
        server.dispatcher=object:Dispatcher() {override fun dispatch(r:RecordedRequest):MockResponse {
            if(r.path=="/api/status/$id/view")return MockResponse().setBody("{\"ok\":true}")
            return if(fail.get())MockResponse().setResponseCode(503)else MockResponse().setBody(JSONObject().put("statuses",JSONArray().put(JSONObject().put("id",id).put("owner","AAAABBBBCCCC").put("name","Ali").put("kind","text").put("body","Expires while viewing").put("created",System.currentTimeMillis()).put("expires",System.currentTimeMillis()+4000))).toString())
        }}
        server.start();Session.serverUrl=server.url("/").toString().trimEnd('/')
        try {
            val a=launch(StatusViewerActivity::class.java,id)
            waitUi("Real server error") {labels(a).contains("Status unavailable")&&labels(a).any {it.contains("Server temporarily unavailable")}}
            fail.set(false);click("Retry")
            waitUi("Retry opens actual text") {labels(a).contains("Expires while viewing")}
            waitUi("24-hour contract expiry closes content") {labels(a).contains("This update has expired.")&&!labels(a).contains("Expires while viewing")}
        }finally {ui {screens.forEach {it.finish()}};inst.waitForIdleSync();server.shutdown()}
    }
    @Test fun unsupportedMediaFallbackRevalidatesAudienceBeforeOpeningAnotherApp()=fixture {_,_ ->
        val server=MockWebServer();val allow=AtomicBoolean(true);val id="6".repeat(32);val remote="7".repeat(32)
        val bytes="Not a decodable photo".toByteArray()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val body=JSONObject().put("remote",remote).put("name","fixture.jpg").put("mime","image/jpeg").put("size",bytes.size).put("sha256",hash).toString()
        server.dispatcher=object:Dispatcher() {override fun dispatch(r:RecordedRequest):MockResponse = when(r.path) {
            "/api/status"->MockResponse().setBody(JSONObject().put("statuses",if(allow.get())JSONArray().put(JSONObject().put("id",id).put("owner","AAAABBBBCCCC").put("name","Ali").put("kind","image").put("body",body).put("created",System.currentTimeMillis()).put("expires",System.currentTimeMillis()+600000))else JSONArray()).toString())
            "/api/status/$id/view"->MockResponse().setBody("{\"ok\":true}")
            "/api/files/$remote"->MockResponse().setBody(okio.Buffer().write(bytes))
            else->MockResponse().setResponseCode(404)
        }}
        server.start();Session.serverUrl=server.url("/").toString().trimEnd('/')
        val filter=android.content.IntentFilter(Intent.ACTION_VIEW).apply {addDataType("image/jpeg")}
        val monitor=inst.addMonitor(filter,android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true)
        try {
            val a=launch(StatusViewerActivity::class.java,id)
            waitUi("Unsupported format offers the existing real external viewer") {labels(a).contains("Open with another app")}
            click("Open with another app")
            waitUi("Real ACTION_VIEW intent reached Android") {monitor.hits==1}
            allow.set(false);click("Open with another app")
            waitUi("Revocation removes external opening even for a cached file") {labels(a).any {it.contains("no longer shared with you")}}
            assertEquals(1,monitor.hits)
            ui {assertFalse(labels(a).contains("Open with another app"))}
        }finally {inst.removeMonitor(monitor);ui {screens.forEach {it.finish()}};inst.waitForIdleSync();server.shutdown()}
    }
    @Test fun networkGraphPagesAndKeyboardSelectionUseObservedNodesOnly()=fixture {_,account ->
        val radio=Radio()
        repeat(27) {n->radio.peers.add(RadarPeer("e$n","%012X".format(n+1),"Private $n"))}
        radio.peers.take(3).forEach {radio.connected[it.endpoint]=it.name;radio.userIdOf[it.endpoint]=it.id}
        val router=Router(ctx,object:Router.UiListener {}) {radio}
        ui {RadarPrefs.setEnabled(ctx,true);router.start(account,"Irfan");MeshBridge.attach(router)}
        try {
            val a=launch(NetworkVisionActivity::class.java)
            awaitRadio(router,radio)
            waitUi("Observed network count") {radio.active&&labels(a).any {it.startsWith("27 nearby peers")}}
            ui {
                all(a.window.decorView).first {it.contentDescription=="Next peers"}.performClick()
                assertTrue(labels(a).contains("Page 2 of 3"))
                all(a.window.decorView).first {it.contentDescription=="Zoom in"}.performClick()
                val graph=all(a.window.decorView).filterIsInstance<NetworkVisionView>().single()
                graph.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
                assertTrue(labels(a).any {it.startsWith("Peer 13\n")})
                all(a.window.decorView).first {it.contentDescription=="Reset network view"}.performClick()
                assertFalse(labels(a).any {it.contains("Private 0")||it.contains(account)})
            }
            capture("network")
        }finally {ui {screens.forEach {it.finish()}};inst.waitForIdleSync();closeRouter(router)}
    }
}
