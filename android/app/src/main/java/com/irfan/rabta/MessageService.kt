package com.irfan.rabta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

/** Background service: keeps WebSocket alive and shows notifications. */
class MessageService : Service(), RabtaEvents.Listener, Router.UiListener {

    companion object {
        const val MSG_CHANNEL  = "rabta_messages"
        const val SERVICE_CHANNEL = "rabta_connection"
        const val CALL_CHANNEL = "rabta_calls_ringtone_v2"
        const val CALL_NOTIF   = 2
        const val RADAR_NOTIF = 3
        const val RADAR_CHANNEL = "rabta_radar_requests"
        const val DEVICE_NOTIF = 4
        const val SECURITY_CHANNEL = "rabta_security"
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
        /**
         * From an open call screen: Android keeps a call's microphone working with the screen locked only for a
         * foreground service of the microphone type that was given that type while the app was on screen.
         */
        fun callStarted(ctx: Context, peerName: String, video: Boolean) {
            try { ctx.startService(Intent(ctx, MessageService::class.java).setAction(CALL_STARTED).putExtra("peerName", peerName).putExtra("video", video)) } catch (_: Exception) {}
        }
        fun callEnded(ctx: Context) {
            try { ctx.startService(Intent(ctx, MessageService::class.java).setAction(CALL_ENDED)) } catch (_: Exception) {}
        }
        private const val CALL_STARTED = "call_started"
        private const val CALL_ENDED = "call_ended"
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
    private var callWake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Session.init(this)          // service may be restarted by Android without the app open
        createChannels()
        showConnection()
        RabtaEvents.add(this)
        if (Session.loggedIn) WsClient.connect(Session)
        startMeshIfAllowed();callHandler.postDelayed(repair,15000)
        try{(getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).registerDefaultNetworkCallback(network);networkRegistered=true}catch(_:Exception){}
    }

    override fun onDestroy() {
        cancelRinging();callHandler.removeCallbacks(repair);releaseCallWake()
        // Stopped on purpose (signing out): the swipe-away restart below must not undo it.
        runCatching { getSystemService(android.app.AlarmManager::class.java).cancel(restartAlarm()) }
        getSystemService(NotificationManager::class.java).cancel(RADAR_NOTIF)
        if(networkRegistered)runCatching {(getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(network)}
        RabtaEvents.remove(this)
        MeshBridge.detach(); router?.stop(); router = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CALL_STARTED -> { inCall(intent.getStringExtra("peerName").orEmpty(), intent.getBooleanExtra("video", false)); return START_STICKY }
            CALL_ENDED -> { releaseCallWake(); if (!showConnection()) runCatching { getSystemService(NotificationManager::class.java).notify(1, persistentNotification()) }; return START_STICKY }
        }
        if (Session.loggedIn && !WsClient.connected) WsClient.connect(Session)
        if(intent?.action=="retry_nearby"){router?.pauseTransport();MeshBridge.status="Restarting Nearby…"}
        startMeshIfAllowed()        // e.g. after the user granted Bluetooth permission
        return START_STICKY
    }

    private fun restartAlarm() = PendingIntent.getBroadcast(this, 0, Intent(this, StartReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    /**
     * Some phones end Rabta's process when it is swiped away from recent apps; ask Android to start it again shortly
     * after. If the service is still running then, the alarm does nothing; if it is stopped normally, onDestroy cancels it.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Session.loggedIn) runCatching {
            getSystemService(android.app.AlarmManager::class.java).set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 5000, restartAlarm())
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * The usual connection notice. Android may refuse it when it restarts this service in the background; the
     * service then still runs for a while and fetches waiting messages instead of crashing.
     */
    private fun showConnection(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, persistentNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, persistentNotification())
        true
    } catch (_: Exception) { false }

    // ---------- calls ----------
    private fun inCall(peerName: String, video: Boolean) {
        val back = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val notice = Notification.Builder(this, SERVICE_CHANNEL).setSmallIcon(R.drawable.ic_stat_rabta)
            .setContentTitle(if (video) "Ongoing video call" else "Ongoing voice call")
            .setContentText("${peerName.ifBlank { "Rabta contact" }.take(60)} · Tap to return to the call")
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true)
            .apply { if (back != null) setContentIntent(PendingIntent.getActivity(this@MessageService, 1, back, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) }
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, notice)
        } catch (_: Exception) { runCatching { getSystemService(NotificationManager::class.java).notify(1, notice) } }
        // Keeps the phone from sleeping between audio frames once the screen is off.
        if (callWake == null) callWake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rabta:call")?.apply { setReferenceCounted(false); acquire(4 * 60 * 60_000L) }
    }
    private fun releaseCallWake() { callWake?.takeIf { it.isHeld }?.release(); callWake = null }

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
    override fun onDeviceRequest(requestId: String, deviceName: String) {
        val review=PendingIntent.getActivity(this,DEVICE_NOTIF,Intent(this,DevicesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,SECURITY_CHANNEL).setSmallIcon(R.drawable.ic_stat_rabta)
            .setContentTitle("New sign-in request").setContentText("${deviceName.take(40)} wants to use your account. Allow only if it's you.")
            .setContentIntent(review).setAutoCancel(true).setTimeoutAfter(5*60_000L).build()
        runCatching { getSystemService(NotificationManager::class.java).notify(DEVICE_NOTIF,notification) }
    }
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
        return Notification.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rabta)
            .setContentTitle("Rabta")
            .setContentText("Tap to open your conversations")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL,"Connection service",NotificationManager.IMPORTANCE_LOW).apply {description="Keeps Rabta available in the background"})
        nm.createNotificationChannel(NotificationChannel(RADAR_CHANNEL,"Radar requests",NotificationManager.IMPORTANCE_DEFAULT).apply { description="Review nearby connection requests in Radar" })
        nm.createNotificationChannel(NotificationChannel(SECURITY_CHANNEL,"Sign-in requests",NotificationManager.IMPORTANCE_HIGH).apply { description="A new phone wants to sign in to your account" })
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
