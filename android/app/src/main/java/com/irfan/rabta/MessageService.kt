package com.irfan.rabta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder

/** Background service: keeps WebSocket alive and shows notifications. */
class MessageService : Service(), RabtaEvents.Listener, Router.UiListener {

    companion object {
        const val MSG_CHANNEL  = "rabta_messages"
        const val CALL_CHANNEL = "rabta_calls_ringtone_v2"
        const val CALL_NOTIF   = 2
        const val RADAR_NOTIF = 3
        const val RADAR_CHANNEL = "rabta_radar_requests"
        private var msgId      = 100
        private val pendingCalls=java.util.concurrent.ConcurrentHashMap<String,Long>()
        fun accepted(ctx:Context,id:String) { pendingCalls.remove(id)?.let { Db.get(ctx).deleteCall(it) } }

        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, MessageService::class.java)) } catch (_: Exception) {MeshBridge.status="Open Rabta to start Nearby service"}
        }
        fun restartNearby(ctx:Context) {try {ctx.startForegroundService(Intent(ctx,MessageService::class.java).setAction("retry_nearby"))}catch(_:Exception){MeshBridge.status="Open Rabta to restart Nearby"}}
        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, MessageService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var ringingId=""
    private val callHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val dismissCall=Runnable { cancelRinging() }
    private fun cancelRinging() { callHandler.removeCallbacks(dismissCall); ringingId=""; (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(CALL_NOTIF) }
    private var router: Router? = null
    private val repair=object:Runnable { override fun run(){startMeshIfAllowed();callHandler.postDelayed(this,15000)} }
    private val network=object:android.net.ConnectivityManager.NetworkCallback(){override fun onAvailable(n:android.net.Network){callHandler.post {if(Session.loggedIn&&!WsClient.connected)WsClient.retry()}}}
    private var networkRegistered=false

    override fun onCreate() {
        super.onCreate()
        Session.init(this)          // service may be restarted by Android without the app open
        createChannels()
        startForeground(1, persistentNotification())
        RabtaEvents.add(this)
        if (Session.loggedIn) WsClient.connect(Session)
        startMeshIfAllowed();callHandler.postDelayed(repair,15000)
        try{(getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).registerDefaultNetworkCallback(network);networkRegistered=true}catch(_:Exception){}
    }

    override fun onDestroy() {
        cancelRinging();callHandler.removeCallbacks(repair)
        getSystemService(NotificationManager::class.java).cancel(RADAR_NOTIF)
        if(networkRegistered)runCatching {(getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(network)}
        RabtaEvents.remove(this)
        MeshBridge.detach(); router?.stop(); router = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Session.loggedIn && !WsClient.connected) WsClient.connect(Session)
        if(intent?.action=="retry_nearby"){router?.pauseTransport();MeshBridge.status="Restarting Nearby…"}
        startMeshIfAllowed()        // e.g. after the user granted Bluetooth permission
        return START_STICKY
    }

    // ---------- offline mesh ----------
    private fun startMeshIfAllowed() {
        if(!Session.loggedIn){router?.pauseTransport();MeshBridge.status="Sign in once to use Nearby";return}
        try {
            if(router!=null&&router?.myId!=Session.id){router?.stop();router=null;MeshBridge.detach()}
            if(router?.running!=true) {
                router?.stop()
                val r=Router(this,this);r.start(Session.id,Session.name);router=r;MeshBridge.attach(r)
            }
            if(!RadarPrefs.enabled(this)){router?.pauseTransport();MeshBridge.status="Radar is off — you are not discoverable";return}
            val problem=NearbySetup.problem(this)
            if(problem!=null){router?.pauseTransport();MeshBridge.status=problem;return}
            router?.retryTransport()
        }catch(_:Exception){MeshBridge.status="Nearby could not start — reopen app and check permissions"}
    }
    override fun onStatus(msg:String){MeshBridge.status=msg}
    override fun onRadarRequest(name:String) {
        if(!RadarPrefs.enabled(this))return
        val review=PendingIntent.getActivity(this,RADAR_NOTIF,Intent(this,NearbyActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,RADAR_CHANNEL).setSmallIcon(R.drawable.ic_stat_rabta)
            .setContentTitle("Radar connection request")
            .setContentText(if(Privacy.hidePreviews(this))"Open Radar to review" else "${name.take(80)} wants to connect")
            .setContentIntent(review).setAutoCancel(true).setOnlyAlertOnce(true).setTimeoutAfter(RadarGate.REQUEST_LIFE).build()
        runCatching { getSystemService(NotificationManager::class.java).notify(RADAR_NOTIF,notification) }
    }

    // Router.UiListener: a message reached us over the mesh
    override fun onMeshMessage(msgId: String, fromId: String, fromName: String, body: String, sentAt: Long, hops: Int, type: String, stored: () -> Unit) {
        Inbox.receive(msgId, fromId, fromName, body, sentAt, fromId, fromName, type, false, stored)
    }

    // --- RabtaEvents.Listener ---
    override fun onMessage(fromId: String, fromName: String, msgId: String, body: String, t: Long, msgType: String, groupId: String) {
        if (msgType!="reaction" && !Privacy.blocked(this,fromId) && ChatActivity.openPeer != fromId) showMessageNotification(fromId, fromName, when(msgType) { "file"->"File"; "video"->"Video"; "image"->"Photo"; "voice"->"Voice note"; "sticker"->"Sticker"; else->body.take(160) })
    }

    override fun onIncomingCall(fromId: String, fromName: String, callId:String) { showCallNotification(fromId, fromName, false,callId) }
    override fun onVideoOffer(fromId: String, fromName: String, callId: String, sdp: String) { showCallNotification(fromId, fromName, true, callId, sdp) }

    override fun onCallEnd(fromId: String,callId:String) { if(callId==ringingId) cancelRinging() }
    override fun onVideoEnd(callId:String) { if(callId==ringingId) cancelRinging() }

    // --- Notifications ---
    private fun showMessageNotification(fromId: String, fromName: String, body: String) {
        val chatIntent = Intent(this, ChatActivity::class.java)
            .putExtra("peerId", fromId).putExtra("peerName", fromName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, fromId.hashCode(), chatIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, MSG_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rabta)
            .setContentTitle(fromName)
            .setContentText(if(Privacy.hidePreviews(this)) "New message" else body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(msgId++, n)
    }

    private fun showCallNotification(fromId: String, fromName: String, isVideo: Boolean = false, callId: String = "", offerSdp: String? = null) {
        if(Privacy.blocked(this,fromId))return
        if(ringingId==callId && callId.isNotEmpty()) return
        if(pendingCalls.size>100)pendingCalls.clear()
        if(!pendingCalls.containsKey(callId))pendingCalls[callId]=Db.get(this).logCallStart(fromId,fromName,"missed")
        ringingId=callId; callHandler.removeCallbacks(dismissCall); callHandler.postDelayed(dismissCall,45000)
        val callIntent = Intent(this, IncomingCallActivity::class.java)
            .putExtra("fromId", fromId).putExtra("fromName", fromName)
            .putExtra("isVideo", isVideo).putExtra("callId", callId)
            .also { if (offerSdp != null) it.putExtra("offerSdp", offerSdp) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, 0, callIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rabta)
            .setContentTitle(if (isVideo) "Incoming Video Call" else "Incoming Call")
            .setContentText("$fromName is ${if (isVideo) "video " else ""}calling...")
            .setTimeoutAfter(45000)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
        n.flags=n.flags or Notification.FLAG_INSISTENT
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(CALL_NOTIF, n)
        WsClient.sendRaw(org.json.JSONObject().put("type","call_ringing").put("to",fromId).put("callId",callId).toString())
        // Also launch the full-screen activity directly
        try { startActivity(callIntent) } catch (_: Exception) {}
    }

    private fun persistentNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, ConversationListActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, MSG_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rabta)
            .setContentTitle("Rabta")
            .setContentText("Messages ki waiting mein...")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(RADAR_CHANNEL,"Radar requests",NotificationManager.IMPORTANCE_DEFAULT).apply { description="Review nearby connection requests in Radar" })
        nm.createNotificationChannel(NotificationChannel(MSG_CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "New message notifications"; enableLights(true); enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CALL_CHANNEL, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming calls use your phone ringtone and respect silent / Do Not Disturb settings"
            setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI,android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
        })
    }
}
