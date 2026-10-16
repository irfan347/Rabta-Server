package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.json.JSONObject

/** Radar and legacy QR/tools share the real account-scoped Nearby transport. */
class NearbyPanel(private val activity:Activity) {
    val view=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private val info:TextView
    private val discoverable:Switch
    private val radar:RadarView
    private val users=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private val count:TextView
    private val handler=Handler(Looper.getMainLooper())
    private var active=false
    private var rendered=""
    private var pendingOpen:String?=null
    private val tick=object:Runnable {
        override fun run() { refresh();if(active)handler.postDelayed(this,1000) }
    }
    init {
        val card=RabtaUi.card(activity)
        card.addView(RabtaUi.text(activity,"Rabta Radar",25f,true))
        card.addView(RabtaUi.text(activity,"Meet nearby. Stay private.",14f,color=AppTheme.sub(activity)))
        radar=RadarView(activity)
        card.addView(radar,LinearLayout.LayoutParams(-1,RabtaUi.dp(activity,200)))
        discoverable=Switch(activity).apply {
            text="Discoverability";textSize=16f;setTextColor(AppTheme.text(activity))
            minHeight=RabtaUi.dp(activity,56);isChecked=RadarPrefs.enabled(activity)
            contentDescription="Radar discoverability"
            setOnCheckedChangeListener { _,enabled ->
                if(!Session.loggedIn){isChecked=false;Toast.makeText(activity,"Sign in to use Radar",0).show();return@setOnCheckedChangeListener}
                MeshBridge.setDiscoverable(activity,enabled);pendingOpen=null;rendered="";refresh()
                if(enabled&&NearbySetup.problem(activity)!=null)guidance()
            }
        }
        card.addView(discoverable)
        info=RabtaUi.text(activity,"Radar is off",14f,color=AppTheme.sub(activity))
        card.addView(info)
        card.addView(RabtaUi.text(activity,"When on, nearby phones can see your Rabta display name and ID until you turn it off. Every new connection needs approval. Turning off disconnects Nearby; queued messages stay saved.",13f,color=AppTheme.sub(activity)).apply { setPadding(0,RabtaUi.dp(activity,12),0,0) })
        view.addView(card)
        view.addView(RabtaUi.button(activity,"Permissions & discovery help") { guidance() })
        view.addView(RabtaUi.button(activity,"Retry discovery") {
            if(!RadarPrefs.enabled(activity))Toast.makeText(activity,"Enable discoverability first",0).show()
            else if(NearbySetup.problem(activity)!=null)guidance()
            else {MessageService.restartNearby(activity);rendered="";refresh()}
        })
        count=RabtaUi.text(activity,"Nearby users",19f,true).apply { setPadding(0,RabtaUi.dp(activity,8),0,RabtaUi.dp(activity,12)) }
        view.addView(count);view.addView(users)
        view.addView(RabtaUi.text(activity,"No phone numbers are shared. The Radar animation shows discovery activity; it does not measure distance or location.",13f,color=AppTheme.sub(activity)).apply { setPadding(0,RabtaUi.dp(activity,8),0,RabtaUi.dp(activity,16)) })
        view.addView(RabtaUi.button(activity,"Instant Connect · temporary QR",true) { activity.startActivity(Intent(activity,InstantConnectActivity::class.java)) })
        view.addView(RabtaUi.button(activity,"My contact QR") { showQr() })
        view.addView(RabtaUi.button(activity,"Scan contact QR") {
            IntentIntegrator(activity).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Scan a trusted Rabta contact").setBeepEnabled(false).setOrientationLocked(false).initiateScan()
        })
        view.addView(RabtaUi.button(activity,"Connection tools") { tools() })
        view.addView(RabtaUi.button(activity,"Connectivity diagnostics") { activity.startActivity(Intent(activity,ConnectivityDiagnosticsActivity::class.java)) })
        view.addView(FeatureUi.text(activity,"Approved Nearby connections support encrypted text and built-in stickers using the existing mesh. Files, Status, groups and calls require internet. Delivery needs a recipient receipt."))
    }
    fun resume() { if(active)return;active=true;MessageService.start(activity);handler.post(tick) }
    fun pause() { active=false;handler.removeCallbacks(tick);radar.scanning(false) }
    fun refresh() {
        if(!active||activity.isDestroyed||activity.isFinishing)return
        val enabled=RadarPrefs.enabled(activity)
        if(discoverable.isChecked!=enabled)discoverable.isChecked=enabled
        val problem=if(enabled)NearbySetup.problem(activity)else null
        val snapshot=MeshBridge.snapshot()
        radar.scanning(enabled&&problem==null&&snapshot.active)
        info.text=if(!enabled)"Off · you are not discoverable" else problem?:MeshBridge.status
        val peers=if(enabled)MeshBridge.radarPeers()else emptyList()
        count.text="Nearby users · ${peers.size}"
        val now=SystemClock.elapsedRealtime()
        val signature=peers.joinToString { "${it.endpoint}:${it.id}:${it.name}:${it.stage}:${it.code}:${now>=it.retryAt}" }+snapshot.peers.toString()+enabled+problem
        if(signature!=rendered) {
            rendered=signature;users.removeAllViews()
            if(peers.isEmpty()) {
                users.addView(RabtaUi.empty(activity,"nearby",if(enabled)"No users found yet" else "Your Radar is private",if(enabled)"Both people need discoverability on, Nearby permissions and compatible Rabta versions. No location or distance is shown." else "Turn on discoverability when you want to meet someone nearby."))
            }else peers.take(50).forEach { peer -> peerCard(peer,snapshot.peers.firstOrNull { it.id==peer.id }?.keyMatched==true,now) }
            if(peers.size>50)users.addView(RabtaUi.text(activity,"Showing 50 of ${peers.size} discovered users",13f))
        }
        pendingOpen?.let { id ->
            val peer=peers.firstOrNull { it.id==id&&it.stage==RadarStage.CONNECTED }
            if(peer!=null&&snapshot.peers.any { it.id==id&&it.keyMatched }) {pendingOpen=null;openChat(peer)}
        }
    }
    private fun peerCard(peer:RadarPeer,keyMatched:Boolean,now:Long) {
        val card=RabtaUi.card(activity)
        val head=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
        head.addView(RabtaUi.text(activity,peer.name.take(1).uppercase().ifBlank { "R" },22f,true,AppTheme.accent(activity)).apply {
            gravity=Gravity.CENTER;background=RabtaUi.shape(activity,AppTheme.sent(activity),28)
            contentDescription="${peer.name} avatar"
        },LinearLayout.LayoutParams(RabtaUi.dp(activity,52),RabtaUi.dp(activity,52)))
        val identity=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(RabtaUi.dp(activity,12),0,0,0) }
        identity.addView(RabtaUi.text(activity,peer.name.ifBlank { "Rabta user" },17f,true))
        identity.addView(RabtaUi.text(activity,peer.id,12f,color=AppTheme.sub(activity)))
        head.addView(identity,LinearLayout.LayoutParams(0,-2,1f));card.addView(head)
        val status=when(peer.stage) {
            RadarStage.FOUND -> "Discovered · approval required"
            RadarStage.OUTGOING -> "Request sent · waiting for approval"
            RadarStage.INCOMING -> "Wants to connect with you"
            RadarStage.APPROVED -> "Your approval sent · waiting for connection"
            RadarStage.CONNECTED -> if(keyMatched)"Connected · signed key matches saved identity" else "Connected · verifying identity key"
            RadarStage.FAILED -> "Connection ended, failed or timed out"
            RadarStage.REJECTED -> "Request declined"
        }
        card.addView(RabtaUi.text(activity,status,13f,color=AppTheme.sub(activity)).apply { setPadding(0,RabtaUi.dp(activity,10),0,RabtaUi.dp(activity,10)) })
        if(peer.code.isNotEmpty()&&peer.stage!=RadarStage.CONNECTED)card.addView(RabtaUi.text(activity,"Connection code: ${peer.code}\nCompare this code on both phones before accepting.",13f))
        when(peer.stage) {
            RadarStage.INCOMING -> {
                card.addView(RabtaUi.button(activity,"Accept connection",true) { if(MeshBridge.approveConnection(peer.endpoint))pendingOpen=peer.id;refresh() })
                card.addView(RabtaUi.button(activity,"Reject request") { MeshBridge.rejectConnection(peer.endpoint);refresh() })
            }
            RadarStage.OUTGOING,RadarStage.APPROVED -> card.addView(RabtaUi.button(activity,"Cancel request") { MeshBridge.rejectConnection(peer.endpoint);pendingOpen=null;refresh() })
            RadarStage.CONNECTED -> {
                card.addView(RabtaUi.button(activity,if(keyMatched)"Open private chat" else "Waiting for security verification",keyMatched) { if(keyMatched)openChat(peer) }.apply { isEnabled=keyMatched;alpha=if(keyMatched)1f else 0.55f })
                card.addView(RabtaUi.button(activity,"Compare security code") { MeshSecurity.showContact(activity,peer.id) })
            }
            else -> card.addView(RabtaUi.button(activity,if(now<peer.retryAt)"Request cooldown · try again shortly" else "Request connection",now>=peer.retryAt) {
                if(MeshBridge.requestConnection(peer.endpoint))pendingOpen=peer.id else Toast.makeText(activity,"Request unavailable. Refresh Radar or wait for the cooldown.",0).show()
                refresh()
            }.apply { isEnabled=now>=peer.retryAt;alpha=if(isEnabled)1f else 0.55f })
        }
        card.addView(RabtaUi.button(activity,"Block user") {
            AlertDialog.Builder(activity).setTitle("Block ${peer.name}?")
                .setMessage("Disconnect this user and reject future Radar requests. You can manage blocked users in Privacy settings.")
                .setNegativeButton("Cancel",null).setPositiveButton("Block") { _,_ -> MeshBridge.blockUser(activity,peer.id);if(pendingOpen==peer.id)pendingOpen=null;refresh() }.show()
        })
        users.addView(card)
    }
    private fun openChat(peer:RadarPeer) {
        if(!MeshBridge.snapshot().peers.any { it.id==peer.id&&it.keyMatched }||Privacy.blocked(activity,peer.id))return
        val db=Db.get(activity)
        if(db.conversations().none { it.peerId==peer.id })db.upsertConversation(peer.id,peer.name,"",System.currentTimeMillis(),false)
        activity.startActivity(Intent(activity,ChatActivity::class.java).putExtra("peerId",peer.id).putExtra("peerName",peer.name))
    }
    private fun guidance() {
        val message=NearbySetup.problem(activity)?:"Nearby prerequisites are available. Turn discoverability on on both phones, then request a connection. Compare the connection code before accepting."
        AlertDialog.Builder(activity).setTitle("Radar permissions & discovery").setMessage(message+"\n\nRabta uses nearby-device and location permissions required by Android discovery. Radar does not display your coordinates or share a phone number.")
            .setPositiveButton("Allow permissions") { _,_ -> NearbySetup.request(activity) }
            .setNeutralButton("Connection tools") { _,_ -> tools() }.setNegativeButton("Close",null).show()
    }
    private fun showQr() {
        FeatureApi.run(activity, {
            JSONObject().put("v",1).put("id",Session.id).put("name",Session.name)
                .put("key",MeshSecurity(activity,Session.id).publicText()).toString()
        }) { bundle ->
            val image=ImageView(activity).apply {
                setImageBitmap(BarcodeEncoder().encodeBitmap(bundle,BarcodeFormat.QR_CODE,700,700))
                adjustViewBounds=true;setPadding(20,20,20,20);contentDescription="My Rabta contact QR"
            }
            AlertDialog.Builder(activity).setTitle("Legacy contact QR").setMessage("This permanent public contact key does not expire. Use Instant Connect for temporary, single-use invitations.").setView(image).setPositiveButton("Close",null).show()
        }
    }
    private fun tools() {
        fun settings(action: String) = activity.startActivity(Intent(action))
        RabtaUi.sheet(activity,"Connection tools",listOf(
            RabtaUi.SheetAction("Allow Nearby permissions") { NearbySetup.request(activity) },
            RabtaUi.SheetAction("Bluetooth settings") { settings(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) },
            RabtaUi.SheetAction("Wi-Fi settings") { settings(android.provider.Settings.ACTION_WIFI_SETTINGS) },
            RabtaUi.SheetAction("Location settings") { settings(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
            RabtaUi.SheetAction("App permissions") { activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${activity.packageName}"))) },
            RabtaUi.SheetAction("Check Google Play services") {
                com.google.android.gms.common.GoogleApiAvailability.getInstance().makeGooglePlayServicesAvailable(activity)
                    .addOnSuccessListener { MessageService.restartNearby(activity) }
                    .addOnFailureListener { Toast.makeText(activity,"Update or enable Google Play services on this phone",1).show() }
            },
            RabtaUi.SheetAction("Retry connection") { WsClient.retry();MessageService.restartNearby(activity);refresh() }
        ))
    }
    fun handleResult(req: Int,res: Int,data: Intent?): Boolean {
        val result=IntentIntegrator.parseActivityResult(req,res,data)?:return false
        val raw=result.contents?:return true
        if(raw.startsWith(InstantInvitation.PREFIX)) {
            activity.startActivity(Intent(activity,InstantConnectActivity::class.java).putExtra("pairing_code",raw));return true
        }
        runCatching {
            require(raw.length<4096);val o=JSONObject(raw);val id=o.getString("id")
            require(MeshPolicy.validId(id)&&id!=Session.id)
            val key=o.getString("key");val code=MeshCrypto.fingerprint(key)
            Triple(id,o.optString("name",id).take(80),key) to code
        }.onSuccess { (contact,code) ->
            AlertDialog.Builder(activity).setTitle("Add ${contact.second}?")
                .setMessage("${contact.first}\n\n$code\n\nThis is a legacy permanent contact QR. Compare this code with the person before adding. Use Instant Connect for temporary invitations and replay protection.")
                .setNegativeButton("Cancel",null).setPositiveButton("Add contact") { _,_ ->
                    if(MeshSecurity(activity,Session.id).pin(contact.first,contact.third)) {
                        Db.get(activity).upsertConversation(contact.first,contact.second,"",System.currentTimeMillis(),false)
                        Toast.makeText(activity,"Contact added",0).show()
                    } else Toast.makeText(activity,"Key differs from saved contact. Verify with the person; key was not replaced.",1).show()
                }.show()
        }.onFailure { Toast.makeText(activity,"Invalid Rabta contact QR",1).show() }
        return true
    }
}
