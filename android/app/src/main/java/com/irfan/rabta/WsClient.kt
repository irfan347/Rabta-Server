package com.irfan.rabta

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object WsClient {
    // A connect that has not answered in 20 s is stuck (often a network Android had blocked while Rabta slept); a
    // fresh attempt is quicker than waiting longer.
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
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
    private var probe:Runnable?=null
    @Volatile private var lastHeard=0L      // elapsedRealtime of the server's last frame
    // Servers that announce receipts (every current Rabta server) answer {"type":"ping"}; others are not checked.
    @Volatile private var answersPing=false
    private var attemptStarted=0L
    /** Told once the connection is known good again, so a keep-alive wake lock can end early. */
    @Volatile var settled:(()->Unit)?=null
    private fun main(work:()->Unit) { if(Looper.myLooper()==Looper.getMainLooper())work() else handler.post {work()} }
    private fun announce(value:String) { status=value;RabtaEvents.postDisconnected() }
    private fun cancelTimers(){retryTask?.let {handler.removeCallbacks(it)};readyTimeout?.let {handler.removeCallbacks(it)};probe?.let {handler.removeCallbacks(it)};retryTask=null;readyTimeout=null;probe=null}

    /** How long a reconnect may take before screens say Rabta is offline: a quick one, as when Rabta opens, goes unseen. */
    const val QUIET_MS = 2000L
    @Volatile private var quietUntil = 0L
    private fun quiet() { quietUntil = SystemClock.elapsedRealtime() + QUIET_MS }
    /** Whether screens should say Rabta is offline or connecting; not while a reconnect is in its first [QUIET_MS]. */
    fun showsOffline() = !connected && SystemClock.elapsedRealtime() >= quietUntil

    /**
     * Rabta came on screen ([onScreen]), the network changed or the keep-alive alarm fired. A phone that slept or
     * changed network keeps a socket that only looks connected, and its reconnect timer stops while it sleeps: connect
     * now instead of after a backoff, and make sure a connection that looks fine still answers, or replace it. Someone
     * looking at Rabta waits for that check, so it is shorter then.
     */
    fun wake(onScreen: Boolean = false) = main {
        if(!Session.loggedIn||authRequired||Session.serverUrl.isBlank())return@main
        val now=SystemClock.elapsedRealtime()
        // Not connected: a waiting backoff or a stuck attempt is replaced by a fresh one (never after a deliberate disconnect).
        if(!connected) { if(onScreen) quiet(); if(shouldConnect&&(ws==null||now-attemptStarted>5000)) retry(); return@main }
        if(now-lastHeard<20_000||!answersPing) { settled?.invoke(); return@main }
        if(probe!=null)return@main
        val attempt=generation
        if(ws?.send("{\"type\":\"ping\"}")!=true) { lost(attempt,"Reconnecting…",atOnce=true);return@main }
        probe=Runnable { probe=null; if(attempt==generation&&lastHeard<now) lost(attempt,"Reconnecting…",atOnce=true) }.also { handler.postDelayed(it,if(onScreen) 3000 else 6000) }
    }

    fun connect(session: Session) = main {
        if(session.serverUrl.isBlank()||session.token.isBlank())return@main
        val next=session.serverUrl+"|"+session.id+"|"+session.token
        if(identity!=next){generation++;cancelTimers();ws?.cancel();ws=null;connected=false;authRequired=false;identity=next;shouldConnect=false}
        if(shouldConnect||authRequired)return@main
        shouldConnect=true;reconnectDelay=2000L;quiet();doConnect()
    }
    fun retry() = main {
        if(!Session.loggedIn)return@main
        if(authRequired){announce("Sign in again — session expired or rejected");return@main}
        generation++;cancelTimers();ws?.cancel();ws=null;connected=false;shouldConnect=false
        connect(Session)
    }
    /** [atOnce]: the socket stopped answering while the network works, so a new one is made now, not after the backoff. */
    private fun lost(attempt:Long,value:String,auth:Boolean=false,atOnce:Boolean=false) {
        if(attempt!=generation)return
        if(connected) quiet()
        generation++;cancelTimers();connected=false;ws?.cancel();ws=null;authRequired=auth;announce(value)
        if(shouldConnect&&!auth) {
            val delay=if(atOnce) 0L else reconnectDelay.also { reconnectDelay=(it*1.5).toLong().coerceAtMost(30000) }
            retryTask=Runnable { retryTask=null;doConnect() }.also {handler.postDelayed(it,delay)}
        }
    }
    private fun doConnect() {
        if(!shouldConnect||authRequired)return
        val attempt=++generation
        attemptStarted=SystemClock.elapsedRealtime()
        announce("Connecting to server…")
        try {
            val url=Session.serverUrl.trimEnd('/').replace("https://","wss://").replace("http://","ws://")+"/ws"
            val expectedId=Session.id
            ws=http.newWebSocket(Request.Builder().url(url).header("Authorization","Bearer ${Session.token}").build(),object:WebSocketListener(){
                override fun onOpen(socket:WebSocket,r:Response)=main {
                    if(attempt!=generation){socket.cancel();return@main}
                    // A successful HTTP upgrade is not an authenticated session.
                    announce("Connecting…")
                    readyTimeout=Runnable {lost(attempt,"Reconnecting…")}.also {handler.postDelayed(it,15000)}
                }
                override fun onMessage(socket:WebSocket,text:String)=main {
                    if(attempt!=generation)return@main
                    lastHeard=SystemClock.elapsedRealtime()   // any frame, the server's "pong" too, proves the socket alive
                    probe?.let { handler.removeCallbacks(it);probe=null;settled?.invoke() }
                    val o=runCatching {JSONObject(text)}.getOrNull()?:return@main
                    if(o.optString("type")=="ready") {
                        if(o.optString("id")!=expectedId){lost(attempt,"Sign in again",true);return@main}
                        readyTimeout?.let {handler.removeCallbacks(it)};readyTimeout=null
                        protocol=o.optInt("protocol",1);answersPing=o.optBoolean("receipts");connected=true;status="Online";reconnectDelay=2000L;RabtaEvents.postConnected();settled?.invoke()
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
                    val message=when {auth->"Sign in again — session expired or rejected";t is java.net.UnknownHostException->"Can't reach Rabta — check your internet";t is javax.net.ssl.SSLException->"Secure connection failed; check phone date and time";r!=null->"Rabta is unavailable — retrying…";else->"No connection — check your internet; retrying…"}
                    lost(attempt,message,auth)
                }
            })
        } catch(_:Exception) {lost(attempt,"Can't connect to Rabta — sign in again",true)}
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
        runCatching { body.put("device", DeviceIdentity.proof(purpose)).toString() }.getOrElse { handler.post { cb(false, "This phone can't sign in securely. Restart it and try again.", null) }; null }
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
            override fun onFailure(c: Call, e: IOException) { handler.post { cb(false,unreachable(e),null) } }
            override fun onResponse(c: Call, r: Response) { val b=r.body?.string()?: ""; val o=try{JSONObject(b)}catch(_:Exception){JSONObject()}; handler.post { if(r.isSuccessful) cb(true,"ok",o) else cb(false,o.optString("error","Not found"),null) } }
        })
    }
    fun uploadPhoto(srv: String, tok: String, b64: String, cb: (Boolean,String) -> Unit) =
        post(srv, "/api/me/photo", JSONObject().put("photo", b64).toString(), tok) { ok, msg, _ -> cb(ok, msg) }
    /** A profile photo reply: [photo] is "" when the person has none; [unchanged] means it still matches the tag sent. */
    class PhotoAnswer(val photo: String, val tag: String, val unchanged: Boolean)
    /** [cb] gets null when the server could not answer. [tag] from an earlier answer turns an unchanged photo into a tiny reply. */
    fun fetchPhoto(srv: String, tok: String, userId: String, tag: String = "", cb: (PhotoAnswer?) -> Unit) {
        val rq = Request.Builder().url(srv.trimEnd('/')+"/api/user/photo/$userId").header("Authorization","Bearer $tok")
        if (tag.isNotEmpty()) rq.header("If-None-Match", tag)
        http.newCall(rq.build()).enqueue(object:Callback{
            override fun onFailure(c: Call, e: IOException) { handler.post { cb(null) } }
            override fun onResponse(c: Call, r: Response) {
                val answer = r.use { when {
                    r.code == 304 -> PhotoAnswer("", tag, true)
                    r.code == 404 -> PhotoAnswer("", "", false)
                    r.isSuccessful -> try { PhotoAnswer(JSONObject(r.body?.string() ?: "").optString("photo", ""), r.header("ETag").orEmpty(), false) } catch (_: Exception) { null }
                    else -> null
                } }
                handler.post { cb(answer) }
            }
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
