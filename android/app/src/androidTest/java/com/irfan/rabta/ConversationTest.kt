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
    /** The view described as [label] that is on screen, e.g. an action in the selection bar. */
    private fun press(a:ChatActivity,label:String) {
        var target:View?=null
        waitUi { target=all(a.window.decorView).lastOrNull { it.isShown&&it.contentDescription?.toString()==label };target!=null }
        ui { target!!.performClick() }
    }
    private fun launch(peerId:String,name:String)=inst.startActivitySync(Intent(ctx,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("peerId",peerId).putExtra("peerName",name)) as ChatActivity
    private fun windows()=WindowInspector.getGlobalWindowViews().flatMap { all(it) }
    /** A real finger tap at the fraction [fx],[fy] of [view], delivered by the screen like a person's. */
    private fun tap(view:View,fx:Float=0.5f,fy:Float=0.5f) {
        val at=IntArray(2);var w=0;var h=0
        ui { view.getLocationOnScreen(at);w=view.width;h=view.height }
        val x=at[0]+w*fx;val y=at[1]+h*fy;val t=android.os.SystemClock.uptimeMillis()
        inst.sendPointerSync(android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_DOWN,x,y,0))
        inst.sendPointerSync(android.view.MotionEvent.obtain(t,t+60,android.view.MotionEvent.ACTION_UP,x,y,0))
    }
    /** A real one-second 96×64 H.264 video (made with ffmpeg for this test). */
    private val tinyVideo=android.util.Base64.decode("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",android.util.Base64.DEFAULT)
    private fun jpeg(w:Int,h:Int)=java.io.ByteArrayOutputStream().also { android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(200,60,40)) }.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,it) }.toByteArray()
    private fun b64(bytes:ByteArray)=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
    /** [seconds] of silence as a WAV voice note. */
    private fun wav(seconds:Int):String {
        val samples=16000*seconds;val buffer=java.nio.ByteBuffer.allocate(44+samples*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buffer.putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
        repeat(samples) { buffer.putShort(0) }
        return b64(buffer.array())
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
                val row=views().first { it.tag=="message:convo-incoming" }
                assertTrue("Blank row space accepts touch gestures",row.isClickable)
                assertTrue("Holding beside the bubble opens its actions",row.performLongClick())
            }
            // Holding a message selects it, and the header offers its actions, as in WhatsApp.
            waitUi { views().any { it.isShown&&it.contentDescription=="Cancel selection" } }
            press(a,"Reply")
            waitUi { views().filterIsInstance<TextView>().any { it.isShown&&it.text.contains("Reply to")&&it.text.contains("Original message") } }
            ui { assertFalse("Replying ends the selection",views().any { it.isShown&&it.contentDescription=="Cancel selection" });views().first { it.contentDescription=="Cancel reply" }.performClick();incoming().performLongClick() }
            press(a,"More options")
            choose("Copy")
            ui { assertEquals("Original message",(ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString());incoming().performLongClick() }
            press(a,"Delete")
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
    /** The header says what the contact is doing as the server tells it: online, last seen, typing, recording audio. */
    @Test fun theHeaderShowsOnlineLastSeenTypingAndRecordingAsTheServerSays() {
        prepare();val server=MockWebServer();val frames=java.util.concurrent.CopyOnWriteArrayList<JSONObject>();var socket:WebSocket?=null
        val reports=java.util.concurrent.atomic.AtomicReference("")
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path!="/ws")return MockResponse().setResponseCode(404)
                reports.set(request.getHeader("X-Rabta-Presence").orEmpty())
                return MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
                    override fun onOpen(ws:WebSocket,response:Response) { socket=ws;ws.send("{\"type\":\"ready\",\"id\":\"AAAABBBBCCCC\",\"protocol\":2}") }
                    override fun onMessage(ws:WebSocket,text:String) { frames.add(JSONObject(text)) }
                })
            }
        }
        server.start();val url=server.url("/").toString().trimEnd('/')
        ui { Session.serverUrl=url;WsClient.connect(Session) }
        val deadline=System.currentTimeMillis()+10000
        while(!WsClient.connected&&System.currentTimeMillis()<deadline)Thread.sleep(50)
        assertTrue("Local authenticated socket ready",WsClient.connected)
        val a=launch()
        try {
            fun shown()=all(a.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
            fun last(type:String)=frames.lastOrNull { it.optString("type")==type }
            waitUi { last("watch")?.optString("id")==peer }
            assertEquals("The phone tells the server it reports when Rabta is on screen","1",reports.get())
            waitUi { last("presence")?.optBoolean("active")==true }
            socket!!.send(JSONObject().put("type","presence").put("id",peer).put("online",true).put("lastSeen",0).toString())
            waitUi { shown().contains("online") }
            socket!!.send(JSONObject().put("type","presence").put("id",peer).put("online",false).put("lastSeen",System.currentTimeMillis()).toString())
            waitUi { shown().any { it.startsWith("last seen today at") }&&!shown().contains("online") }
            socket!!.send(JSONObject().put("type","typing").put("from",peer).toString())
            waitUi { shown().contains("typing…") }
            socket!!.send(JSONObject().put("type","typing").put("from",peer).put("kind","recording").toString())
            waitUi { shown().contains("recording audio…")&&!shown().contains("typing…") }
            ui { a.finish() }
            waitUi { last("watch")?.optString("id")=="" }
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
        } finally { ui { a.finish();WsClient.disconnect();VoicePlayer.stop() };MessageService.stop(ctx) }
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

    /** Starting a voice message pauses the one playing, as in WhatsApp. */
    @Test fun onlyOneVoiceMessagePlaysAtATime() {
        prepare();val db=Db.get(ctx);val chat="convo-voices-peer";val now=System.currentTimeMillis()
        db.upsertConversation(chat,"Voices contact","",now,false)
        db.saveMessage("convo-voice-first",chat,chat,wav(6),now,false,"received","voice")
        db.saveMessage("convo-voice-second",chat,Session.id,wav(6),now+1,true,"sent","voice")
        val a=launch(chat,"Voices contact")
        try {
            fun voices()=all(a.window.decorView).filterIsInstance<VoiceMessageView>()
            fun state(v:VoiceMessageView)=all(v).mapNotNull { it.contentDescription?.toString() }.firstOrNull { it.endsWith("voice message") }
            waitUi { voices().size==2 }
            ui { all(voices()[0]).first { it.contentDescription=="Play voice message" }.performClick() }
            waitUi { state(voices()[0])=="Pause voice message" }
            ui { all(voices()[1]).first { it.contentDescription=="Play voice message" }.performClick() }
            waitUi { state(voices()[1])=="Pause voice message" }
            ui { assertEquals("The first one paused for the second","Resume voice message",state(voices()[0])) }
        } finally { ui { a.finish();VoicePlayer.stop() };db.deleteChat(chat) }
    }

    /** A photo fills its bubble in its own shape instead of sitting small in a box, and a tap shows it full screen. */
    @Test fun aPhotoFillsItsBubbleAndOpensFullScreen() {
        prepare();val db=Db.get(ctx);val chat="convo-photo-peer"
        db.upsertConversation(chat,"Photo contact","",System.currentTimeMillis(),false)
        db.saveMessage("convo-tall-photo",chat,chat,b64(jpeg(300,600)),System.currentTimeMillis(),false,"received","image")
        val a=launch(chat,"Photo contact")
        try {
            fun photo()=all(a.window.decorView).first { it.tag=="photo:convo-tall-photo" }
            // The picture is decoded off the main thread, then shown.
            waitUi { all(a.window.decorView).any { it.tag=="photo:convo-tall-photo"&&it.height>0 }&&all(photo()).filterIsInstance<android.widget.ImageView>().first().drawable!=null }
            ui {
                assertEquals("As wide as WhatsApp's photos",RabtaUi.dp(a,250),photo().width)
                assertEquals("A tall photo is as tall as allowed, not boxed in",RabtaUi.dp(a,330),photo().height)
                assertEquals(android.widget.ImageView.ScaleType.CENTER_CROP,all(photo()).filterIsInstance<android.widget.ImageView>().first().scaleType)
            }
            // A real tap anywhere on the picture opens it (the picture must not keep the tap to itself).
            var picture:View?=null
            ui { picture=photo() }
            tap(picture!!,0.3f,0.3f)
            waitUi { windows().any { it is ZoomImageView&&it.isShown } }
            ui { windows().last { it.isShown&&it.contentDescription=="Close photo" }.performClick() }
            waitUi { windows().none { it is ZoomImageView&&it.isShown } }
            ui { photo().performLongClick() }
            waitUi { all(a.window.decorView).any { it.isShown&&it.contentDescription=="Cancel selection" } }
            // The phone's own back (a key, as from the navigation bar): on Android 16 it reaches the chat's back handling only
            // through the back callbacks, no longer through onBackPressed.
            inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitUi { all(a.window.decorView).none { it.isShown&&it.contentDescription=="Cancel selection" } }
            assertFalse("Back cleared the selection and left the chat open",a.isFinishing)
        } finally { ui { a.finish() };db.deleteChat(chat) }
    }

    /** A video shows its picture before it is downloaded; the size in the corner downloads it, then shows its length. */
    @Test fun aVideoShowsItsPictureAndTheCornerDownloadsIt() {
        prepare();val db=Db.get(ctx);val chat="convo-video-peer"
        val bytes=tinyVideo
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val remote=(1..32).map { "0123456789abcdef".random() }.joinToString("")
        val downloads=java.util.concurrent.atomic.AtomicInteger()
        val server=MockWebServer().apply { dispatcher=object:Dispatcher() {
            override fun dispatch(r:RecordedRequest):MockResponse = when(r.path) {
                "/api/files/$remote" -> { downloads.incrementAndGet();MockResponse().setBody(okio.Buffer().write(bytes)) }
                "/api/files/$remote/got" -> MockResponse().setBody("{\"ok\":true}")
                else -> MockResponse().setResponseCode(404)
            }
        };start() }
        val url=server.url("/").toString().trimEnd('/')
        ui { Session.serverUrl=url }
        val body=JSONObject().put("remote",remote).put("name","clip.mp4").put("mime","video/mp4").put("size",bytes.size).put("sha256",sha)
            .put("thumb",b64(jpeg(160,90))).put("w",160).put("h",90).put("ms",1000).toString()
        db.upsertConversation(chat,"Video contact","",System.currentTimeMillis(),false)
        db.saveMessage("convo-video",chat,chat,body,System.currentTimeMillis(),false,"received","video")
        val a=launch(chat,"Video contact")
        try {
            fun video()=all(a.window.decorView).first { it.tag=="video:convo-video" } as VideoBubble
            waitUi { all(a.window.decorView).any { it.tag=="video:convo-video"&&it.height>0 } }
            ui {
                val v=video()
                assertEquals(RabtaUi.dp(a,250),v.width)
                assertTrue("Shaped like the video (wide)",v.height<v.width)
                assertTrue("The picture came with the message",all(v).filterIsInstance<android.widget.ImageView>().any { it.drawable!=null&&it.scaleType==android.widget.ImageView.ScaleType.CENTER_CROP })
                assertEquals("Download video, ${Attachments.details(body)}",v.chip.contentDescription)
                assertEquals("Play video",v.centre.contentDescription)
                assertEquals(0,downloads.get())
                v.chip.performClick()
            }
            waitUi { video().chip.contentDescription=="Video, 0:01" }
            assertEquals(1,downloads.get())
            assertArrayEquals(bytes,Transfers.onPhone(ctx,body)!!.readBytes())
            // A real tap on the picture (not just its play button) plays it here, in Rabta's own player.
            var clip:View?=null
            ui { clip=video() }
            tap(clip!!,0.25f,0.3f)
            // The player opened: playing, or (on a phone without this codec) saying so with another app to try.
            fun player()=windows().any { it.isShown&&(it is android.widget.VideoView||(it is TextView&&it.text=="This video can't play here")) }
            waitUi { player() }
            ui { windows().last { it.isShown&&it.contentDescription=="Close video" }.performClick() }
            waitUi { !player() }
        } finally {
            ui { a.finish() }
            Transfers.onPhone(ctx,body)?.delete()
            ctx.getSharedPreferences("downloads_${Session.id}",0).edit().remove(remote).remove("got:$remote").commit()
            server.shutdown();db.deleteChat(chat)
        }
    }

    /** Hold messages to select them; Forward sends them to the chats chosen, and media have a forward button beside them. */
    @Test fun heldMessagesForwardToAnotherChat() {
        prepare();val db=Db.get(ctx);val chat="convo-forward-from";val target="convo-forward-to";val now=System.currentTimeMillis()
        db.upsertConversation(chat,"Forward source","",now,false)
        db.upsertConversation(target,"Forward contact","",now-5000,false)
        db.saveMessage("convo-fwd-first",chat,chat,"First to pass on",now,false,"received")
        db.saveMessage("convo-fwd-second",chat,Session.id,"Second to pass on",now+1,true,"sent")
        db.saveMessage("convo-fwd-photo",chat,chat,b64(jpeg(120,90)),now+2,false,"received","image")
        val a=launch(chat,"Forward source")
        try {
            fun views()=all(a.window.decorView)
            fun selecting()=views().any { it.isShown&&it.contentDescription=="Cancel selection" }
            waitUi { views().any { it.tag=="bubble:convo-fwd-second" }&&views().any { it.tag=="forward:convo-fwd-photo"&&it.isShown } }
            ui {
                assertTrue("Text has no forward button",views().none { it.tag=="forward:convo-fwd-first" })
                views().first { it.tag=="bubble:convo-fwd-first" }.performLongClick()
            }
            waitUi { selecting() }
            ui {
                assertFalse("Forward buttons step aside while selecting",views().first { it.tag=="forward:convo-fwd-photo" }.isShown)
                views().first { it.tag=="bubble:convo-fwd-second" }.performLongClick()
            }
            waitUi { views().filterIsInstance<TextView>().any { it.isShown&&it.text=="2" } }
            ui { assertFalse("Reply is for one message",views().any { it.isShown&&it.contentDescription=="Reply" }) }
            press(a,"Forward")
            var row:View?=null
            waitUi { row=windows().firstOrNull { it.isShown&&it.contentDescription=="Forward contact, not selected" };row!=null }
            ui { row!!.performClick() }
            var send:View?=null
            waitUi { send=windows().lastOrNull { it.isShown&&it.contentDescription=="Send" };send!=null }
            ui { send!!.performClick() }
            waitUi { db.messages(target).count { it.mine }==2 }
            assertEquals(setOf("First to pass on","Second to pass on"),db.messages(target).filter { it.mine }.map { it.body }.toSet())
            waitUi { !selecting() }
        } finally { ui { a.finish() };db.deleteChat(chat);db.deleteChat(target) }
    }

    /** A new tick changes only its time line, and a new message is added without drawing the whole chat again. */
    @Test fun aTickOrANewMessageDoesNotRedrawTheChat() {
        prepare();val db=Db.get(ctx);val chat="convo-update-peer";val now=System.currentTimeMillis()
        db.upsertConversation(chat,"Update contact","",now,false)
        db.saveMessage("convo-upd-old",chat,chat,"Earlier words",now-60_000,false,"received")
        db.saveMessage("convo-upd-mine",chat,Session.id,"My words",now,true,MessageDelivery.RELAYED)
        val a=launch(chat,"Update contact")
        try {
            fun row(id:String)=all(a.window.decorView).first { it.tag=="message:$id" }
            waitUi { all(a.window.decorView).any { it.tag=="message:convo-upd-mine" } }
            var old:View?=null;var mine:View?=null
            ui { old=row("convo-upd-old");mine=row("convo-upd-mine") }
            assertTrue(db.updateStatus("convo-upd-mine",MessageDelivery.DELIVERED))
            RabtaEvents.postHistoryChanged(chat)
            waitUi { all(row("convo-upd-mine")).any { it.contentDescription?.contains("Delivered")==true } }
            ui { assertSame("The tick changed in place",mine,row("convo-upd-mine"));assertSame("Other messages stay as drawn",old,row("convo-upd-old")) }
            db.saveMessage("convo-upd-new",chat,chat,"Newer words",now+1000,false,"received")
            RabtaEvents.postHistoryChanged(chat)
            waitUi { all(a.window.decorView).any { it.tag=="message:convo-upd-new" } }
            ui { assertSame("A new message is added to the chat as drawn",old,row("convo-upd-old"));assertSame(mine,row("convo-upd-mine")) }
        } finally { ui { a.finish() };db.deleteChat(chat) }
    }

    /** As in WhatsApp, a voice message goes on after its chat closes, and a bar in other chats pauses or stops it. */
    @Test fun aVoiceMessageGoesOnAfterItsChatCloses() {
        prepare();val db=Db.get(ctx);val chat="convo-voice-away";val other="convo-voice-other";val now=System.currentTimeMillis()
        db.upsertConversation(chat,"Away contact","",now,false);db.upsertConversation(other,"Other contact","",now,false)
        db.saveMessage("convo-voice-away-1",chat,chat,wav(8),now,false,"received","voice")
        val a=launch(chat,"Away contact")
        var b:ChatActivity?=null
        try {
            waitUi { all(a.window.decorView).any { it is VoiceMessageView } }
            ui { all(a.window.decorView).first { it.contentDescription=="Play voice message" }.performClick() }
            waitUi { VoicePlayer.playing }
            ui { a.finish() }
            b=launch(other,"Other contact")
            fun bar()=all(b!!.window.decorView).firstOrNull { it.isShown&&it.contentDescription?.startsWith("Voice message from")==true }
            waitUi { bar()!=null }
            ui { assertTrue("Still playing after its chat closed",VoicePlayer.playing);assertEquals("convo-voice-away-1",VoicePlayer.key) }
            ui { all(b!!.window.decorView).first { it.isShown&&it.contentDescription=="Pause voice message" }.performClick() }
            waitUi { !VoicePlayer.playing&&VoicePlayer.key=="convo-voice-away-1" }
            ui { all(b!!.window.decorView).first { it.isShown&&it.contentDescription=="Stop voice message" }.performClick() }
            waitUi { VoicePlayer.key.isEmpty()&&bar()==null }
        } finally { ui { a.finish();b?.finish();VoicePlayer.stop() };db.deleteChat(chat);db.deleteChat(other) }
    }
}
