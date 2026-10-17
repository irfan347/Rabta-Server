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
    @Volatile var connected = false; private set
    @Volatile var protocol=0; private set
    @Volatile var authRequired = false; private set
    @Volatile var status = "Offline"; private set
    private var reconnectDelay = 2000L
    private var shouldConnect = false
    private var generation=0L
    private var identity=""
    private var retryTask:Runnable?=null
    private var readyTimeout:Runnable?=null
    private fun main(work:()->Unit) { if(Looper.myLooper()==Looper.getMainLooper())work() else handler.post {work()} }
    private fun announce(value:String) { status=value;RabtaEvents.postDisconnected() }
    private fun cancelTimers(){retryTask?.let {handler.removeCallbacks(it)};readyTimeout?.let {handler.removeCallbacks(it)};retryTask=null;readyTimeout=null}

    fun connect(session: Session) = main {
        if(session.serverUrl.isBlank()||session.token.isBlank())return@main
        val next=session.serverUrl+"|"+session.id+"|"+session.token
        if(identity!=next){generation++;cancelTimers();ws?.cancel();ws=null;connected=false;authRequired=false;identity=next;shouldConnect=false}
        if(shouldConnect||authRequired)return@main
        shouldConnect=true;reconnectDelay=2000L;doConnect()
    }
    fun retry() = main {
        if(!Session.loggedIn)return@main
        if(authRequired){announce("Sign in again — session expired or rejected");return@main}
        generation++;cancelTimers();ws?.cancel();ws=null;connected=false;shouldConnect=false
        connect(Session)
    }
    private fun lost(attempt:Long,value:String,auth:Boolean=false) {
        if(attempt!=generation)return
        generation++;cancelTimers();connected=false;ws?.cancel();ws=null;authRequired=auth;announce(value)
        if(shouldConnect&&!auth) {
            val delay=reconnectDelay;reconnectDelay=(reconnectDelay*1.5).toLong().coerceAtMost(30000)
            retryTask=Runnable { retryTask=null;doConnect() }.also {handler.postDelayed(it,delay)}
        }
    }
    private fun doConnect() {
        if(!shouldConnect||authRequired)return
        val attempt=++generation
        announce("Connecting to server…")
        try {
            val url=Session.serverUrl.trimEnd('/').replace("https://","wss://").replace("http://","ws://")+"/ws"
            val expectedId=Session.id
            ws=http.newWebSocket(Request.Builder().url(url).header("Authorization","Bearer ${Session.token}").build(),object:WebSocketListener(){
                override fun onOpen(socket:WebSocket,r:Response)=main {
                    if(attempt!=generation){socket.cancel();return@main}
                    // A successful HTTP upgrade is not an authenticated session.
                    announce("Authenticating…")
                    readyTimeout=Runnable {lost(attempt,"Server did not confirm login — retrying…")}.also {handler.postDelayed(it,15000)}
                }
                override fun onMessage(socket:WebSocket,text:String)=main {
                    if(attempt!=generation)return@main
                    val o=runCatching {JSONObject(text)}.getOrNull()?:return@main
                    if(o.optString("type")=="ready") {
                        if(o.optString("id")!=expectedId){lost(attempt,"Server rejected account identity",true);return@main}
                        readyTimeout?.let {handler.removeCallbacks(it)};readyTimeout=null
                        protocol=o.optInt("protocol",1);connected=true;status=if(protocol>=2)"Online" else "Online — backend update needed for calls";reconnectDelay=2000L;RabtaEvents.postConnected()
                    } else if(connected)handle(o)
                }
                override fun onClosing(socket:WebSocket,code:Int,reason:String)=main {
                    if(attempt!=generation)return@main
                    socket.close(code,reason)
                    lost(attempt,if(code==1008)"Sign in again — session expired or rejected" else "Connection closed — retrying…",code==1008)
                }
                override fun onClosed(socket:WebSocket,code:Int,reason:String)=main {
                    lost(attempt,if(code==1008)"Sign in again — session expired or rejected" else "Disconnected — retrying…",code==1008)
                }
                override fun onFailure(socket:WebSocket,t:Throwable,r:Response?)=main {
                    val auth=r?.code in listOf(401,403)
                    val message=when {auth->"Sign in again — session expired or rejected";t is java.net.UnknownHostException->"Server address could not be found";t is javax.net.ssl.SSLException->"Secure connection failed; check phone date and time";r!=null->"Server unavailable (HTTP ${r.code}) — retrying…";else->"Server unreachable — check internet; retrying…"}
                    lost(attempt,message,auth)
                }
            })
        } catch(_:Exception) {lost(attempt,"Invalid server address — check connection settings",true)}
    }

    private fun handle(o: JSONObject) {
        when (o.optString("type")) {
            "msg" -> {
                val id = o.optString("id"); val from = o.optString("from")
                // Confirm only after the message (or an earlier copy with the same ID) is stored on this phone.
                Inbox.receive(id, from, o.optString("fromName"), o.optString("body"), o.optLong("t",System.currentTimeMillis()), from, o.optString("fromName"), o.optString("msgType","text")) { sendReceipt(from, id) }
            }
            "group_msg" -> {
                val gId = o.optString("groupId"); val gName = o.optString("groupName")
                Inbox.receive(o.optString("id"), o.optString("from"), o.optString("fromName"), o.optString("body"), o.optLong("t",System.currentTimeMillis()), gId, gName, o.optString("msgType","text"), true)
            }
            "ack" -> RabtaEvents.postAck(o.optString("id"), MessageDelivery.SERVER_PREFIX + o.optString("status"))
            "receipt" -> RabtaEvents.postReceipt(o.optString("id"), o.optString("by"), false)
            "device_request" -> o.optJSONObject("request")?.let { RabtaEvents.postDeviceRequest(it.optString("id"), it.optString("name")) }
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

    fun disconnect() = main { shouldConnect=false;generation++;cancelTimers();ws?.cancel();ws=null;connected=false;authRequired=false;identity="";announce("Offline") }

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
    fun sendReceipt(toId: String, msgId: String) = sendRaw(JSONObject().put("type","receipt").put("to",toId).put("id",msgId).toString())
    fun sendRead(toId: String, msgId: String) = sendRaw(JSONObject().put("type","read").put("to",toId).put("msgId",msgId).toString())

    // HTTP
    /** Signs this phone's Keystore proof into [body]; a phone without a usable key store cannot sign in safely. */
    private fun withDevice(body: JSONObject, purpose: String, cb: (Boolean,String,JSONObject?)->Unit): String? =
        runCatching { body.put("device", DeviceIdentity.proof(purpose)).toString() }.getOrElse { handler.post { cb(false, "This phone's secure key store is unavailable", null) }; null }
    fun register(ctx: Context, srv: String, un: String, pin: String, name: String, cb: (Boolean,String,JSONObject?)->Unit) {
        val body = withDevice(JSONObject().put("username",un).put("pin",pin).put("name",name), "register:$un", cb) ?: return
        post(srv, "/api/register", body, null, cb)
    }
    /** A 202 response with "pending" means a trusted phone must approve this one; see [pollLogin]. */
    fun login(ctx: Context, srv: String, un: String, pin: String, recoveryCode: String? = null, cb: (Boolean,String,JSONObject?)->Unit) {
        val body = withDevice(JSONObject().put("username",un).put("pin",pin).apply { if (recoveryCode != null) put("recoveryCode", recoveryCode) }, "login:$un", cb) ?: return
        post(srv, "/api/login", body, null, cb)
    }
    fun pollLogin(srv: String, requestId: String, cb: (Boolean,String,JSONObject?)->Unit) {
        val body = withDevice(JSONObject(), "poll:$requestId", cb) ?: return
        post(srv, "/api/login/request/$requestId", body, null, cb)
    }
    /** Authenticated JSON request for account screens; [cb] gets the HTTP status (0 when unreachable) and body. */
    fun api(method: String, path: String, body: JSONObject?, cb: (Int, JSONObject) -> Unit) {
        val payload = body?.toString()?.toRequestBody("application/json".toMediaType())
        val rb = Request.Builder().url(Session.serverUrl.trimEnd('/') + path).header("Authorization", "Bearer ${Session.token}")
            .method(method, if (method == "GET") null else payload ?: "{}".toRequestBody("application/json".toMediaType()))
        http.newCall(rb.build()).enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) { handler.post { cb(0, JSONObject().put("error", "Server unreachable — check internet")) } }
            override fun onResponse(c: Call, r: Response) { val b = r.body?.string() ?: ""; val o = runCatching { JSONObject(b) }.getOrDefault(JSONObject()); handler.post { cb(r.code, o) } }
        })
    }
    fun findUser(srv: String, un: String, tok: String, cb: (Boolean,String,JSONObject?)->Unit) {
        http.newCall(Request.Builder().url(srv.trimEnd('/')+"/api/user/${un.lowercase()}").header("Authorization","Bearer $tok").build()).enqueue(object:Callback{
            override fun onFailure(c: Call, e: IOException) { handler.post { cb(false,e.message?:"Network error",null) } }
            override fun onResponse(c: Call, r: Response) { val b=r.body?.string()?: ""; val o=try{JSONObject(b)}catch(_:Exception){JSONObject()}; handler.post { if(r.isSuccessful) cb(true,"ok",o) else cb(false,o.optString("error","Not found"),null) } }
        })
    }
    fun uploadPhoto(srv: String, tok: String, b64: String, cb: (Boolean,String) -> Unit) =
        post(srv, "/api/me/photo", JSONObject().put("photo", b64).toString(), tok) { ok, msg, _ -> cb(ok, msg) }
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
            override fun onFailure(c: Call, e: IOException) { handler.post { cb(false, unreachable(e), null) } }
            override fun onResponse(c: Call, r: Response) { val b=r.body?.string()?: ""; val o=try{JSONObject(b)}catch(_:Exception){JSONObject()}; handler.post { if(r.isSuccessful) cb(true,"ok",o) else cb(false,o.optString("error","Error"),null) } }
        })
    }
    /** What the user can do about a request that never reached the server. */
    fun unreachable(e: IOException) = when {
        e is java.net.SocketTimeoutException || e.message?.contains("timeout", true) == true -> "The server is taking too long. It may be waking up — try again in a minute."
        e is java.net.UnknownHostException -> "No internet connection, or the server address could not be found."
        e is javax.net.ssl.SSLException -> "Secure connection failed. Check the phone's date and time."
        else -> "Can't reach the Rabta server. Check your internet and try again."
    }
    /** Public check used while creating an account; [cb] gets the HTTP status (0 when unreachable) and body. */
    fun usernameAvailable(srv: String, name: String, cb: (Int, JSONObject) -> Unit) {
        http.newCall(Request.Builder().url(srv.trimEnd('/') + "/api/username/available?u=" + java.net.URLEncoder.encode(name, "UTF-8")).build()).enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) { handler.post { cb(0, JSONObject().put("error", unreachable(e))) } }
            override fun onResponse(c: Call, r: Response) { val b = r.body?.string() ?: ""; val o = runCatching { JSONObject(b) }.getOrDefault(JSONObject()); handler.post { cb(r.code, o) } }
        })
    }
}
