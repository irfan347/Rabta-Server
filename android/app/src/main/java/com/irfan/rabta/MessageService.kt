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
        /** Rabta's notification channels. Creating one that already exists changes nothing, so anything about to notify may call this. */
        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
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
            nm.createNotificationChannel(NotificationChannel(ChatNotifications.MISSED_CHANNEL, "Missed calls", NotificationManager.IMPORTANCE_HIGH).apply { description = "Calls you did not answer" })
        }
        private val pendingCalls=java.util.concurrent.ConcurrentHashMap<String,Long>()
        private class Ring(val fromId:String,val fromName:String,val video:Boolean)
        private val rings=java.util.concurrent.ConcurrentHashMap<String,Ring>()
        fun accepted(ctx:Context,id:String) { rings.remove(id); pendingCalls.remove(id)?.let { Db.get(ctx).deleteCall(it) } }
        /** Turned down on purpose: no missed-call notice. */
        fun declined(id:String) { rings.remove(id) }
        /** A ring that stopped without an answer (the caller hung up, or nobody picked up) becomes a missed call. */
        fun missed(ctx:Context,id:String) { rings.remove(id)?.let { ChatNotifications.missedCall(ctx,it.fromId,it.fromName,it.video) } }
        /** Decline from the call notification: tell the caller, and close the ringing screen if it is open. */
        fun decline(ctx:Context,fromId:String,callId:String,video:Boolean) {
            declined(callId)
            WsClient.sendRaw(org.json.JSONObject().put("type",if(video)"video_end" else "call_end").put("to",fromId).put("callId",callId).toString())
            runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(CALL_NOTIF) }
            if(video) RabtaEvents.postVideoEnd(callId) else RabtaEvents.postCallEnd(fromId,callId)
        }

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
        fun callStarted(ctx: Context, peerId: String, peerName: String, video: Boolean) {
            try { ctx.startService(Intent(ctx, MessageService::class.java).setAction(CALL_STARTED).putExtra("peerId", peerId).putExtra("peerName", peerName).putExtra("video", video)) } catch (_: Exception) {}
        }
        @Volatile private var running: MessageService? = null
        /**
         * Screen sharing in a video call: Android captures the screen only while this notice carries the
         * screen-sharing type, so it is set here, at once, before capture starts. False if Android refused it.
         */
        fun sharingStarted(): Boolean = running?.sharing(true) == true
        fun sharingEnded() { running?.sharing(false) }
        fun callEnded(ctx: Context) {
            try { ctx.startService(Intent(ctx, MessageService::class.java).setAction(CALL_ENDED)) } catch (_: Exception) {}
        }
        private const val CALL_STARTED = "call_started"
        private const val CALL_ENDED = "call_ended"

        const val KEEP_ALIVE = "com.irfan.rabta.KEEP_ALIVE"
        private const val KEEP_ALIVE_MS = 5 * 60_000L
        private fun keepAliveAlarm(ctx: Context) = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, StartReceiver::class.java).setAction(KEEP_ALIVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        private var connectWake: PowerManager.WakeLock? = null
        /** Keeps the phone awake up to [ms] so a check or reconnect started now can finish with the screen off. */
        fun awake(ctx: Context, ms: Long) {
            runCatching {
                val lock = connectWake ?: ctx.applicationContext.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rabta:connect")
                    ?.apply { setReferenceCounted(false) }?.also { connectWake = it }
                lock?.acquire(ms)
                // Usually the server answers within a second: the phone goes back to sleep then, not after [ms].
                WsClient.settled = { runCatching { connectWake?.takeIf { it.isHeld }?.release() } }
            }
        }
        /**
         * Rabta has no push service, so while it is closed its own connection must stay alive: every few minutes (less
         * often in Android's deep sleep) this alarm wakes the phone briefly, checks the connection still answers and
         * reconnects if not, restarting the service if Android ended it.
         */
        fun keepAlive(ctx: Context) {
            awake(ctx, 15_000)
            if (running == null) start(ctx)
            WsClient.wake()
            scheduleKeepAlive(ctx)
        }
        private fun scheduleKeepAlive(ctx: Context) {
            runCatching { ctx.getSystemService(android.app.AlarmManager::class.java).setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + KEEP_ALIVE_MS, keepAliveAlarm(ctx)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var ringingId=""
    private val callHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val dismissCall=Runnable { val id=ringingId; cancelRinging(); missed(this,id) }
    private fun cancelRinging() { callHandler.removeCallbacks(dismissCall); ringingId=""; (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(CALL_NOTIF) }
    private var router: Router? = null
    private val repair=object:Runnable { override fun run(){startMeshIfAllowed();callHandler.postDelayed(this,15000)} }
    // A new network (Wi-Fi to mobile data and back), or Android letting Rabta use the network again after deep
    // sleep: the old socket is likely dead even if it looks connected, so check it now.
    private val network=object:android.net.ConnectivityManager.NetworkCallback(){
        override fun onAvailable(n:android.net.Network){callHandler.post { if(Session.loggedIn) { awake(this@MessageService,15_000);WsClient.wake() } }}
        override fun onBlockedStatusChanged(n:android.net.Network,blocked:Boolean){ if(!blocked) callHandler.post { if(Session.loggedIn) { awake(this@MessageService,15_000);WsClient.wake() } } }
    }
    private var networkRegistered=false
    private var callWake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        Session.init(this)          // service may be restarted by Android without the app open
        channels(this)
        showConnection()
        RabtaEvents.add(this)
        if (Session.loggedIn) WsClient.connect(Session)
        scheduleKeepAlive(this)
        startMeshIfAllowed();callHandler.postDelayed(repair,15000)
        try{(getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).registerDefaultNetworkCallback(network);networkRegistered=true}catch(_:Exception){}
    }

    override fun onDestroy() {
        if (running === this) running = null
        cancelRinging();callHandler.removeCallbacks(repair);releaseCallWake()
        // Stopped on purpose (signing out): the swipe-away restart below must not undo it.
        runCatching { getSystemService(android.app.AlarmManager::class.java).cancel(restartAlarm()) }
        runCatching { getSystemService(android.app.AlarmManager::class.java).cancel(keepAliveAlarm(this)) }
        getSystemService(NotificationManager::class.java).cancel(RADAR_NOTIF)
        if(networkRegistered)runCatching {(getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(network)}
        RabtaEvents.remove(this)
        MeshBridge.detach(); router?.stop(); router = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CALL_STARTED -> { inCall(intent.getStringExtra("peerId").orEmpty(), intent.getStringExtra("peerName").orEmpty(), intent.getBooleanExtra("video", false)); return START_STICKY }
            CALL_ENDED -> { callPeerId = ""; releaseCallWake(); if (!showConnection()) runCatching { getSystemService(NotificationManager::class.java).notify(1, persistentNotification()) }; return START_STICKY }
        }
        if (Session.loggedIn && !WsClient.connected) { WsClient.connect(Session); WsClient.wake() }
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
    private var callPeerId = ""
    private var callPeerName = ""
    private var callVideo = false
    /** "Ongoing voice call": tap returns to the call (this chat's call screen, or the video call), Hang up ends it. */
    private fun callNotice(): Notification {
        val back = (if (callVideo) Intent(this, VideoCallActivity::class.java)
            else Intent(this, ChatActivity::class.java).putExtra("peerId", callPeerId).putExtra("peerName", callPeerName).putExtra("showCall", true))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val hangUp = PendingIntent.getBroadcast(this, 3, Intent(this, NotificationActionReceiver::class.java).setAction(ChatNotifications.HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, SERVICE_CHANNEL).setSmallIcon(R.drawable.ic_stat_rabta)
            .setContentTitle(if (callVideo) "Ongoing video call" else "Ongoing voice call")
            .setContentText("${callPeerName.ifBlank { "Rabta contact" }.take(60)} · Tap to return to the call")
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 1, back, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_rabta), "Hang up", hangUp).build())
            .build()
    }
    /** The call's notice and foreground types: microphone for the call, and screen sharing while the screen is shared. */
    private fun callForeground(sharing: Boolean): Boolean {
        val notice = callNotice()
        return try {
            when {
                Build.VERSION.SDK_INT >= 30 -> startForeground(1, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    (if (sharing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0))
                Build.VERSION.SDK_INT >= 29 && sharing -> startForeground(1, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                else -> startForeground(1, notice)
            }
            true
        } catch (_: Exception) { runCatching { getSystemService(NotificationManager::class.java).notify(1, notice) }; false }
    }
    private fun sharing(on: Boolean): Boolean = if (callPeerId.isEmpty()) !on else callForeground(on)
    private fun inCall(peerId: String, peerName: String, video: Boolean) {
        callPeerId = peerId; callPeerName = peerName; callVideo = video
        callForeground(false)
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
        val convId=groupId.ifEmpty { fromId }
        if (msgType=="reaction" || Privacy.blocked(this,fromId) || ChatActivity.openPeer == convId) return
        val title=if(groupId.isEmpty()) fromName else runCatching { Db.get(this).group(groupId)?.name }.getOrNull() ?: "Group"
        ChatNotifications.message(this, convId, title, groupId.isNotEmpty(), fromId, fromName, ChatNotifications.preview(body, msgType))
    }

    override fun onIncomingCall(fromId: String, fromName: String, callId:String) { showCallNotification(fromId, fromName, false,callId) }
    override fun onVideoOffer(fromId: String, fromName: String, callId: String, sdp: String) { showCallNotification(fromId, fromName, true, callId, sdp) }
    // Connected: the server learns this phone's push token, so it can wake Rabta once it is closed.
    override fun onConnected() { Push.sync(this) }

    override fun onCallEnd(fromId: String,callId:String) { if(callId==ringingId) cancelRinging(); missed(this,callId) }
    override fun onVideoEnd(callId:String) { if(callId==ringingId) cancelRinging(); missed(this,callId) }

    // --- Notifications ---
    private fun showCallNotification(fromId: String, fromName: String, isVideo: Boolean = false, callId: String = "", offerSdp: String? = null) {
        if(Privacy.blocked(this,fromId))return
        if(ringingId==callId && callId.isNotEmpty()) return
        if(pendingCalls.size>100)pendingCalls.clear()
        if(!pendingCalls.containsKey(callId))pendingCalls[callId]=Db.get(this).logCallStart(fromId,fromName,if(isVideo) "missed video" else "missed")
        if(callId.isNotEmpty())rings[callId]=Ring(fromId,fromName,isVideo)
        ringingId=callId; callHandler.removeCallbacks(dismissCall); callHandler.postDelayed(dismissCall,45000)
        val callIntent = Intent(this, IncomingCallActivity::class.java)
            .putExtra("fromId", fromId).putExtra("fromName", fromName)
            .putExtra("isVideo", isVideo).putExtra("callId", callId)
            .also { if (offerSdp != null) it.putExtra("offerSdp", offerSdp) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, 0, callIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Answer and Decline right on the notification, like a phone call.
        val answer = PendingIntent.getActivity(this, 1, Intent(callIntent).setData(android.net.Uri.parse("rabta://answer/$callId")).putExtra("answerNow", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val decline = PendingIntent.getBroadcast(this, 2, Intent(this, NotificationActionReceiver::class.java).setAction(ChatNotifications.DECLINE)
            .setData(android.net.Uri.parse("rabta://decline/$callId")).putExtra("id", callId).putExtra("fromId", fromId).putExtra("isVideo", isVideo),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rabta)
            .setContentTitle(if (isVideo) "Incoming Video Call" else "Incoming Call")
            .setContentText("${Names.of(this, fromId, fromName)} is ${if (isVideo) "video " else ""}calling...")
            .setTimeoutAfter(45000)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(false)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 31) builder.setStyle(Notification.CallStyle.forIncomingCall(
            android.app.Person.Builder().setName(Names.of(this, fromId, fromName.ifBlank { "Rabta contact" })).setImportant(true).setIcon(ChatNotifications.photo(fromId)).build(), decline, answer).setIsVideo(isVideo))
        else {
            val icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_rabta)
            builder.addAction(Notification.Action.Builder(icon, "Decline", decline).build()).addAction(Notification.Action.Builder(icon, "Answer", answer).build())
        }
        val n = builder.build()
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

}
