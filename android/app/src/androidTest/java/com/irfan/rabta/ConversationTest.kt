package com.irfan.rabta

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated emulator fixtures and localhost sockets; never production accounts or messages. */
@RunWith(AndroidJUnit4::class)
class ConversationTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private val peer="convo-test-peer"
    private fun ui(block:()->Unit)=inst.runOnMainSync(block)
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) }else emptyList()
    private fun waitUi(check:()->Boolean) {
        val end=System.currentTimeMillis()+10000
        do { inst.waitForIdleSync();var ok=false;ui { ok=check() };if(ok)return;Thread.sleep(100) }while(System.currentTimeMillis()<end)
        fail("Conversation UI did not reach the expected state")
    }
    private fun prepare() {
        ui { MessageService.stop(ctx);Session.init(ctx);WsClient.disconnect();Session.id="AAAABBBBCCCC";Session.token="conversation-test";Session.name="Test account";Session.serverUrl="https://127.0.0.1:8443" }
        ctx.getSharedPreferences("appearance",0).edit().putString("language","en").putFloat("scale",1f).commit()
        Db.get(ctx).upsertConversation(peer,"Conversation contact","",System.currentTimeMillis(),false)
    }
    private fun launch()=inst.startActivitySync(Intent(ctx,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("peerId",peer).putExtra("peerName","Conversation contact")) as ChatActivity
    private fun choose(label:String) {
        var list:ListView?=null;var index=-1
        waitUi {
            list=WindowInspector.getGlobalWindowViews().flatMap { all(it) }.filterIsInstance<ListView>().firstOrNull { v -> (0 until v.count).any { v.getItemAtPosition(it).toString()==label } }
            index=list?.let { v -> (0 until v.count).firstOrNull { v.getItemAtPosition(it).toString()==label } }?:-1
            list!=null&&index>=0
        }
        ui { val v=list!!;v.performItemClick(v.getChildAt(index-v.firstVisiblePosition),index,v.getItemIdAtPosition(index)) }
    }
    @Test fun replyCopyDeleteAndStatesUseSavedMessageIds() {
        prepare();val db=Db.get(ctx);val now=System.currentTimeMillis()
        db.saveMessage("convo-incoming",peer,peer,"Original message",now-86400000,false,"received")
        db.saveMessage("convo-queued",peer,Session.id,"Waiting nearby",now,true,"mesh_wait")
        db.saveMessage("convo-sent",peer,Session.id,"Accepted online",now+1,true,"sent")
        val a=launch()
        try {
            fun views()=all(a.window.decorView)
            fun incoming()=views().first { it.tag=="bubble:convo-incoming" }
            waitUi { views().any { it.tag=="bubble:convo-incoming" } }
            ui {
                // Ticks are icons; each bubble's delivery words are its accessibility description.
                fun described(id:String)=all(views().first { it.tag=="bubble:$id" }).mapNotNull { it.contentDescription?.toString() }
                assertTrue("Stored Nearby states read as Queued",described("convo-queued").any { it.contains("Queued") })
                assertTrue("Stored server acceptance reads as Sent, not Delivered",described("convo-sent").any { it.contains("Sent") }&&described("convo-sent").none { it.contains("Delivered") })
                assertTrue(views().filterIsInstance<TextView>().any { it.text=="Yesterday" })
                for(v in views().filter { it.tag?.toString()?.startsWith("bubble:")==true })assertTrue(v.width<=(v.parent as View).width)
                incoming().performLongClick()
            }
            choose("Reply")
            waitUi { views().filterIsInstance<TextView>().any { it.isShown&&it.text.contains("Reply to")&&it.text.contains("Original message") } }
            ui { views().first { it.contentDescription=="Cancel reply" }.performClick();incoming().performLongClick() }
            choose("Copy")
            ui { assertEquals("Original message",(ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString());incoming().performLongClick() }
            choose("Delete for me")
            waitUi { WindowInspector.getGlobalWindowViews().flatMap { all(it) }.filterIsInstance<android.widget.Button>().any { it.text=="Delete for me" } }
            ui { WindowInspector.getGlobalWindowViews().flatMap { all(it) }.filterIsInstance<android.widget.Button>().first { it.text=="Delete for me" }.performClick() }
            waitUi { views().filterIsInstance<TextView>().any { it.text=="Message deleted" } }
            val messages=db.messages(peer)
            assertEquals("Message deleted",messages.first { it.id=="convo-incoming" }.body)
            assertEquals("Waiting nearby",messages.first { it.id=="convo-queued" }.body)
            assertTrue(messages.any { it.id=="convo-sent" })
        } finally { ui { a.finish();WsClient.disconnect() };MessageService.stop(ctx) }
    }
    @Test fun retryRetainsIdAndDeliveryReceiptUpdatesTheBubble() {
        prepare();val server=MockWebServer();val sent=CountDownLatch(1);var wireId="";var socket:WebSocket?=null
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path!="/ws")return MockResponse().setResponseCode(404)
                return MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                    override fun onOpen(ws:WebSocket,response:Response) { ws.send("{\"type\":\"ready\",\"id\":\"AAAABBBBCCCC\",\"protocol\":2}") }
                    override fun onMessage(ws:WebSocket,text:String) {
                        val o=JSONObject(text)
                        if(o.optString("type")=="msg") { wireId=o.getString("id");socket=ws;ws.send(JSONObject().put("type","ack").put("id",wireId).put("status","delivered").toString());sent.countDown() }
                    }
                })
            }
        }
        server.start();val url=server.url("/").toString().trimEnd('/')
        val db=Db.get(ctx);db.saveMessage("convo-failed",peer,Session.id,"Retry this exact payload",System.currentTimeMillis(),true,"failed")
        ui { Session.serverUrl=url;WsClient.connect(Session) }
        val deadline=System.currentTimeMillis()+10000
        while(!WsClient.connected&&System.currentTimeMillis()<deadline)Thread.sleep(50)
        assertTrue("Local authenticated socket ready",WsClient.connected)
        val a=launch()
        try {
            waitUi { all(a.window.decorView).any { it.tag=="retry:convo-failed" } }
            ui { all(a.window.decorView).first { it.tag=="retry:convo-failed" }.performClick() }
            assertTrue("Retry went through the existing websocket",sent.await(8,TimeUnit.SECONDS))
            assertEquals("convo-failed",wireId)
            // The server's ack only proves it relayed the message; the recipient's receipt proves delivery.
            waitUi { all(a.window.decorView).firstOrNull { it.tag=="bubble:convo-failed" }?.let { b -> all(b).any { it.contentDescription?.contains("Sent")==true } }==true }
            assertEquals("relayed",db.status("convo-failed"))
            socket!!.send(JSONObject().put("type","receipt").put("id",wireId).put("by",peer).toString())
            waitUi { all(a.window.decorView).firstOrNull { it.tag=="bubble:convo-failed" }?.let { b -> all(b).any { it.contentDescription?.contains("Delivered")==true } }==true }
            assertEquals(1,db.messages(peer).count { it.id=="convo-failed" })
            assertEquals("Retry this exact payload",db.messages(peer).first { it.id=="convo-failed" }.body)
            assertEquals("delivered",db.status("convo-failed"))
        } finally { ui { a.finish();WsClient.disconnect() };server.shutdown();MessageService.stop(ctx) }
    }
    @Test fun voiceUsesDecodedDurationAndRealPlaybackControls() {
        prepare()
        val samples=16000;val buffer=java.nio.ByteBuffer.allocate(44+samples*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buffer.putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
        repeat(samples) { buffer.putShort(0) }
        Db.get(ctx).saveMessage("convo-voice",peer,peer,android.util.Base64.encodeToString(buffer.array(),android.util.Base64.NO_WRAP),System.currentTimeMillis(),false,"received","voice")
        val a=launch()
        try {
            waitUi { all(a.window.decorView).any { it is VoiceMessageView } }
            ui { all(a.window.decorView).first { it.contentDescription=="Play voice message" }.performClick() }
            waitUi { all(a.window.decorView).filterIsInstance<SeekBar>().any { it.isEnabled&&it.max in 900..1100 } }
            ui { val view=all(a.window.decorView).filterIsInstance<VoiceMessageView>().first();view.stop();assertFalse(all(view).filterIsInstance<SeekBar>().first().isEnabled) }
        } finally { ui { a.finish();WsClient.disconnect() };MessageService.stop(ctx) }
    }

    /** WhatsApp-style timeline: day chips, call bubbles and a status reply's quote card with its big emoji. */
    @Test fun callsStatusRepliesAndDaysShowInTheChat() {
        prepare();val db=Db.get(ctx);val other="convo-calls-peer";val now=System.currentTimeMillis();val day=86_400_000L
        db.upsertConversation(other,"Calls contact","",now,false)
        db.saveMessage("convo-old",other,other,"Long ago",now-10*day,false,"received")
        db.saveMessage("convo-week",other,other,"This week",now-3*day,false,"received")
        val answered=db.logCallStart(other,"Calls contact","outgoing");db.logCallEnd(answered,46)
        val missed=db.logCallStart(other,"Calls contact","missed video")
        val thumb=java.io.ByteArrayOutputStream().also { android.graphics.Bitmap.createBitmap(40,40,android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.JPEG,70,it) }.toByteArray()
        val card=JSONObject().put("owner",other).put("name","Calls contact").put("kind","image").put("text","Unbox").put("thumb",android.util.Base64.encodeToString(thumb,android.util.Base64.NO_WRAP))
        db.saveMessage("convo-status-reply",other,Session.id,JSONObject().put("text","😍").put("reply","Status · Photo").put("status",card).toString(),now+1000,true,"sent","rich")
        val a=inst.startActivitySync(Intent(ctx,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("peerId",other).putExtra("peerName","Calls contact")) as ChatActivity
        try {
            fun texts()=all(a.window.decorView).filterIsInstance<TextView>()
            fun shown(value:String)=texts().any { it.isShown&&it.text.toString()==value }
            waitUi { shown("Voice call")&&shown("46 sec")&&shown("Missed video call")&&shown("Calls contact • Status") }
            ui {
                assertTrue("Date older than a week",shown(java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(now-10*day))))
                assertTrue("Weekday within the last week",shown(java.text.SimpleDateFormat("EEEE",java.util.Locale.getDefault()).format(java.util.Date(now-3*day))))
                assertTrue(shown("Today"));assertTrue("Status words in the quote",shown("Unbox"))
                val biggest=texts().filter { it.isShown&&it.text.toString()=="😍" }.maxOf { it.textSize }
                assertTrue("Emoji-only status reply is big",biggest>=40*a.resources.displayMetrics.scaledDensity)
                assertTrue("Photo status shows its thumbnail",all(a.window.decorView).any { it is android.widget.ImageView&&it.isShown&&it.width==RabtaUi.dp(a,56) })
                assertTrue("Calls sit in time order after this week's message",texts().indexOfFirst { it.text.toString()=="This week" }<texts().indexOfFirst { it.text.toString()=="Voice call" })
            }
        } finally { ui { a.finish() };db.deleteCall(answered);db.deleteCall(missed) }
    }
}
