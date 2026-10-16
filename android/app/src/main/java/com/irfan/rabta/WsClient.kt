package com.irfan.rabta

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object WsClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    var connected = false; private set
    private var reconnectDelay = 2000L
    private var shouldConnect = false

    fun connect(session: Session) {
        if (session.serverUrl.isEmpty() || session.token.isEmpty()) return
        if (shouldConnect) return
        shouldConnect = true; reconnectDelay = 2000L; doConnect(session)
    }

    private fun doConnect(session: Session) {
        if (!shouldConnect) return
        val url = session.serverUrl.replace("https://","wss://").replace("http://","ws://") + "/ws"
        ws = http.newWebSocket(Request.Builder().url(url).header("Authorization","Bearer ${session.token}").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, r: Response) { connected = true; reconnectDelay = 2000L; handler.post { RabtaEvents.postConnected() } }
                override fun onMessage(ws: WebSocket, text: String) { val o = try { JSONObject(text) } catch (_: Exception) { return }; handler.post { handle(o) } }
                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) { connected = false; handler.post { RabtaEvents.postDisconnected() }
                    if (shouldConnect) handler.postDelayed({ doConnect(session) }, reconnectDelay.also { reconnectDelay = (it*1.5).toLong().coerceAtMost(30000) }) }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) { connected = false; handler.post { RabtaEvents.postDisconnected() }
                    if (shouldConnect) handler.postDelayed({ doConnect(session) }, reconnectDelay) }
            })
    }

    private fun handle(o: JSONObject) {
        when (o.optString("type")) {
            "msg" -> Inbox.receive(o.optString("id"), o.optString("from"), o.optString("fromName"), o.optString("body"), o.optLong("t",System.currentTimeMillis()), o.optString("from"), o.optString("fromName"), o.optString("msgType","text"))
            "group_msg" -> {
                val gId = o.optString("groupId"); val gName = o.optString("groupName")
                Inbox.receive(o.optString("id"), o.optString("from"), o.optString("fromName"), o.optString("body"), o.optLong("t",System.currentTimeMillis()), gId, gName, o.optString("msgType","text"), true)
            }
            "ack" -> RabtaEvents.postAck(o.optString("id"), o.optString("status"))
            "typing" -> RabtaEvents.postTyping(o.optString("from"))
            "read" -> RabtaEvents.postRead(o.optString("msgId"), o.optString("by"))
            "incoming_call" -> RabtaEvents.postIncomingCall(o.optString("from"), o.optString("fromName"),o.optString("callId"))
            "call_state" -> RabtaEvents.postCallState(o.optString("from"),o.optString("callId"),o.optString("state"))
            "call_audio" -> RabtaEvents.postCallAudio(o.optString("from"), o.optString("data"),o.optString("callId"))
            "call_end" -> RabtaEvents.postCallEnd(o.optString("from"),o.optString("callId"))
            "video_offer"   -> RabtaEvents.postVideoOffer(o.optString("from"), o.optString("fromName"), o.optString("callId"), o.optString("sdp"))
            "video_answer"  -> RabtaEvents.postVideoAnswer(o.optString("callId"), o.optString("sdp"))
            "ice_candidate" -> RabtaEvents.postIceCandidate(o.optString("callId"), o.optString("sdpMid"), o.optInt("sdpMLineIndex"), o.optString("sdp"))
            "video_end"     -> RabtaEvents.postVideoEnd(o.optString("callId"))
            "group_created" -> {
                val g = o.optJSONObject("group") ?: return
                val members = mutableListOf<String>()
                val arr = g.optJSONArray("members"); if (arr != null) for (i in 0 until arr.length()) members.add(arr.getString(i))
                RabtaEvents.postGroupCreated(g.optString("id"), g.optString("name"), members)
            }
        }
    }

    fun disconnect() { shouldConnect = false; ws?.close(1000,"bye"); ws = null; connected = false }

    fun send(toId: String, msgId: String, body: String, msgType: String = "text"): Boolean {
        if (!connected) return false
        return ws?.send(JSONObject().put("type","msg").put("id",msgId).put("to",toId).put("body",body).put("msgType",msgType).put("t",System.currentTimeMillis()).toString()) == true
    }

    fun sendGroupMsg(groupId: String, msgId: String, body: String, msgType: String = "text"): Boolean {
        if (!connected) return false
        return ws?.send(JSONObject().put("type","group_msg").put("id",msgId).put("to",groupId).put("body",body).put("msgType",msgType).put("t",System.currentTimeMillis()).toString()) == true
    }

    fun sendRaw(json: String): Boolean = if (connected) (ws?.send(json) == true) else false
    fun sendTyping(toId: String) = sendRaw(JSONObject().put("type","typing").put("to",toId).toString())
    fun sendRead(toId: String, msgId: String) = sendRaw(JSONObject().put("type","read").put("to",toId).put("msgId",msgId).toString())

    // HTTP
    fun register(ctx: Context, srv: String, un: String, pin: String, name: String, cb: (Boolean,String,JSONObject?)->Unit) =
        post(srv, "/api/register", JSONObject().put("username",un).put("pin",pin).put("name",name).toString(), null, cb)
    fun login(ctx: Context, srv: String, un: String, pin: String, cb: (Boolean,String,JSONObject?)->Unit) =
        post(srv, "/api/login", JSONObject().put("username",un).put("pin",pin).toString(), null, cb)
    fun findUser(srv: String, un: String, tok: String, cb: (Boolean,String,JSONObject?)->Unit) {
        http.newCall(Request.Builder().url(srv.trimEnd('/')+"/api/user/${un.lowercase()}").header("Authorization","Bearer $tok").build()).enqueue(object:Callback{
            override fun onFailure(c: Call, e: IOException) { handler.post { cb(false,e.message?:"Network error",null) } }
            override fun onResponse(c: Call, r: Response) { val b=r.body?.string()?: ""; val o=try{JSONObject(b)}catch(_:Exception){JSONObject()}; handler.post { if(r.isSuccessful) cb(true,"ok",o) else cb(false,o.optString("error","Not found"),null) } }
        })
    }
    fun uploadPhoto(srv: String, tok: String, b64: String) {
        Thread { try { post(srv, "/api/me/photo", org.json.JSONObject().put("photo", b64.take(200000)).toString(), tok) { _,_,_ -> } } catch (_: Exception) {} }.start()
    }
    fun fetchPhoto(srv: String, tok: String, userId: String, cb: (String?) -> Unit) {
        http.newCall(Request.Builder().url(srv.trimEnd('/')+"/api/user/photo/$userId").header("Authorization","Bearer $tok").build()).enqueue(object:Callback{
            override fun onFailure(c: Call, e: IOException) { handler.post { cb(null) } }
            override fun onResponse(c: Call, r: Response) { val b=r.body?.string()?: ""; val o=try{JSONObject(b)}catch(_:Exception){JSONObject()}; handler.post { cb(o.optString("photo","").ifEmpty { null }) } }
        })
    }
    fun createGroup(srv: String, tok: String, name: String, members: List<String>, cb: (Boolean,String,JSONObject?)->Unit) =
        post(srv, "/api/groups", JSONObject().put("name",name).put("members", JSONArray(members)).toString(), tok, cb)

    private fun post(srv: String, path: String, body: String, tok: String?, cb: (Boolean,String,JSONObject?)->Unit) {
        val rb = Request.Builder().url(srv.trimEnd('/')+path).post(body.toRequestBody("application/json".toMediaType()))
        if (tok != null) rb.header("Authorization","Bearer $tok")
        http.newCall(rb.build()).enqueue(object:Callback{
            override fun onFailure(c: Call, e: IOException) { handler.post { cb(false, when { e.message?.contains("timeout",true)==true -> "Server so gaya hai — 1 min ruk kar dobara try karein"; else -> e.message?:"Network error" }, null) } }
            override fun onResponse(c: Call, r: Response) { val b=r.body?.string()?: ""; val o=try{JSONObject(b)}catch(_:Exception){JSONObject()}; handler.post { if(r.isSuccessful) cb(true,"ok",o) else cb(false,o.optString("error","Error"),null) } }
        })
    }
}
