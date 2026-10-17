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
    private val search:CompactSearchView
    private var query=""
    private var syncingDiscoverability=false
    private val handler=Handler(Looper.getMainLooper())
    private var active=false
    private var rendered=""
    private var pendingOpen:String?=null
    private val tick=object:Runnable {
        override fun run() { refresh();if(active)handler.postDelayed(this,1000) }
    }
    init {
        val card=RabtaUi.card(activity).apply { setPadding(RabtaUi.dp(activity,16),RabtaUi.dp(activity,12),RabtaUi.dp(activity,8),RabtaUi.dp(activity,12)) }
        val header=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
        val title=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        title.addView(RabtaUi.text(activity,"Signal Orbit",20f,true).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this,true) })
        info=RabtaUi.text(activity,"Radar is off",13f,color=AppTheme.sub(activity)).apply {
            accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
        }
        title.addView(info)
        header.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(RabtaUi.iconButton(activity,"more","Nearby options") { options() })
        card.addView(header)
        radar=RadarView(activity)
        card.addView(radar,LinearLayout.LayoutParams(-1,RabtaUi.dp(activity,220)))
        card.addView(RabtaUi.text(activity,"Rings show connection stage: connected, waiting, discovered. Not distance or direction.",11f,color=AppTheme.sub(activity)).apply {
            gravity=Gravity.CENTER;setPadding(RabtaUi.dp(activity,8),0,RabtaUi.dp(activity,16),RabtaUi.dp(activity,8))
        })
        discoverable=Switch(activity).apply {
            text="Discoverable";textSize=15f;setTextColor(AppTheme.text(activity))
            typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
            minHeight=RabtaUi.dp(activity,48);isChecked=RadarPrefs.enabled(activity)
            contentDescription="Radar discoverability";setPadding(0,0,RabtaUi.dp(activity,8),0)
            setOnCheckedChangeListener { _,enabled ->
                if(syncingDiscoverability)return@setOnCheckedChangeListener
                if(!Session.loggedIn){isChecked=false;Toast.makeText(activity,"Sign in to use Radar",Toast.LENGTH_SHORT).show();return@setOnCheckedChangeListener}
                MeshBridge.setDiscoverable(activity,enabled);pendingOpen=null;rendered="";refresh()
                if(enabled&&NearbySetup.problem(activity)!=null)guidance()
            }
        }
        card.addView(discoverable)
        card.addView(RabtaUi.text(activity,"Share your display name and Rabta ID only while discoverable. Connections always need approval.",12f,color=AppTheme.sub(activity)).apply {setPadding(0,RabtaUi.dp(activity,4),RabtaUi.dp(activity,8),0)})
        view.addView(card)

        // Quick tools: each opens a real, existing feature.
        view.addView(RabtaUi.text(activity,"Quick tools",15f,true).apply { setPadding(RabtaUi.dp(activity,4),RabtaUi.dp(activity,12),0,RabtaUi.dp(activity,8)) })
        fun tiles(vararg items:android.view.View)=LinearLayout(activity).apply {
            items.forEachIndexed { i,v -> addView(v,LinearLayout.LayoutParams(0,-1,1f).apply { if(i==0) marginEnd=RabtaUi.dp(activity,8) }) }
        }
        view.addView(tiles(
            tile("drop","Quick Drop","Files to a verified contact") { activity.startActivity(Intent(activity,DropActivity::class.java)) },
            tile("qr","QR Connect","Temporary pairing invite") { activity.startActivity(Intent(activity,InstantConnectActivity::class.java)) }
        ),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=RabtaUi.dp(activity,8) })
        val nfc=android.nfc.NfcAdapter.getDefaultAdapter(activity)
        view.addView(tiles(
            tile("vision","Network Vision","Your live connection map") { activity.startActivity(Intent(activity,NetworkVisionActivity::class.java)) },
            tile("nfc","NFC Pair",if(nfc==null) "Not on this phone" else "Tap phones to pair") {
                if(nfc==null) Toast.makeText(activity,"This phone has no NFC. Use QR Connect instead.",Toast.LENGTH_LONG).show()
                else activity.startActivity(Intent(activity,InstantConnectActivity::class.java))
            }
        ))

        search=CompactSearchView(activity,"Search nearby users") { value ->query=value;rendered="";refresh() }
        view.addView(search,LinearLayout.LayoutParams(-1,-2).apply {topMargin=RabtaUi.dp(activity,16);bottomMargin=RabtaUi.dp(activity,8)})
        val nearbyHeading=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
        count=RabtaUi.text(activity,"Nearby users",15f,true)
        nearbyHeading.addView(count,LinearLayout.LayoutParams(0,-2,1f))
        nearbyHeading.addView(RabtaUi.iconButton(activity,"restore","Retry discovery") { retry() })
        view.addView(nearbyHeading);view.addView(users)
        view.addView(RabtaUi.text(activity,"No phone numbers are shared. Radar shows activity, not distance or location.",12f,color=AppTheme.sub(activity)).apply {setPadding(0,RabtaUi.dp(activity,8),0,RabtaUi.dp(activity,16))})
        view.addView(RabtaUi.row(activity,"Connection help","Permissions, radios and discovery","connection") { guidance() })
    }
    private fun tile(icon:String,title:String,detail:String,action:()->Unit)=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;isFocusable=true;minimumHeight=RabtaUi.dp(activity,96)
        setPadding(RabtaUi.dp(activity,14),RabtaUi.dp(activity,14),RabtaUi.dp(activity,12),RabtaUi.dp(activity,12))
        background=RabtaUi.ripple(activity,AppTheme.surface(activity),20);contentDescription="$title. $detail"
        setOnClickListener { action() }
        addView(TextView(activity).apply {
            UiIcons.set(this,icon,AppTheme.accent(activity),20,title);gravity=Gravity.CENTER
            background=RabtaUi.shape(activity,AppTheme.raised(activity),12);importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(RabtaUi.dp(activity,36),RabtaUi.dp(activity,36)))
        addView(RabtaUi.text(activity,title,15f,true).apply { setPadding(0,RabtaUi.dp(activity,10),0,0);importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        addView(RabtaUi.text(activity,detail,12f,color=AppTheme.sub(activity)).apply { setPadding(0,RabtaUi.dp(activity,2),0,0);importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO })
    }
    fun saveState(state:android.os.Bundle) {state.putString("nearbyQuery",query)}
    fun restoreState(state:android.os.Bundle?) {search.field.setText(state?.getString("nearbyQuery").orEmpty())}
    fun handleBack():Boolean {
        if(search.field.hasFocus()) {
            search.field.clearFocus()
            androidx.core.view.WindowCompat.getInsetsController(activity.window,activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            return true
        }
        if(query.isNotEmpty()){search.field.setText("");return true}
        return false
    }
    private fun retry() {
        if(!RadarPrefs.enabled(activity))Toast.makeText(activity,"Enable discoverability first",Toast.LENGTH_SHORT).show()
        else if(NearbySetup.problem(activity)!=null)guidance()
        else {MessageService.restartNearby(activity);rendered="";refresh()}
    }
    private fun options()=RabtaUi.sheet(activity,"Nearby options",listOf(
        RabtaUi.SheetAction("My contact QR","Permanent contact key") {showQr()},
        RabtaUi.SheetAction("Scan contact QR") {
            IntentIntegrator(activity).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt("Scan a trusted Rabta contact").setBeepEnabled(false).setOrientationLocked(false).initiateScan()
        },
        RabtaUi.SheetAction("Connection tools") {tools()}
    ))
    fun resume() { if(active)return;active=true;MessageService.start(activity);handler.post(tick) }
    fun pause() { active=false;handler.removeCallbacks(tick);radar.scanning(false) }
    fun refresh() {
        if(!active||activity.isDestroyed||activity.isFinishing)return
        val enabled=RadarPrefs.enabled(activity)
        if(discoverable.isChecked!=enabled){syncingDiscoverability=true;discoverable.isChecked=enabled;syncingDiscoverability=false}
        val problem=if(enabled)NearbySetup.problem(activity)else null
        val snapshot=MeshBridge.snapshot()
        radar.scanning(enabled&&problem==null&&snapshot.active)
        info.text=if(!enabled)"Off · you are not discoverable" else problem?:MeshBridge.status
        info.setTextColor(if(problem!=null)AppTheme.error(activity)else AppTheme.sub(activity))
        val peers=if(enabled)MeshBridge.radarPeers()else emptyList()
        radar.peers(peers.map { RadarView.Dot(it.id,it.name,when(it.stage) {
            RadarStage.CONNECTED -> RadarView.Orbit.CONNECTED
            RadarStage.OUTGOING,RadarStage.INCOMING,RadarStage.APPROVED -> RadarView.Orbit.PENDING
            else -> RadarView.Orbit.DISCOVERED
        }) })
        val matches=NearbySearch.filter(peers,query)
        count.text=if(query.isBlank())"Nearby users · ${peers.size}" else "${matches.size} of ${peers.size} users"
        val now=SystemClock.elapsedRealtime()
        val signature=peers.joinToString { "${it.endpoint}:${it.id}:${it.name}:${it.stage}:${it.code}:${now>=it.retryAt}" }+snapshot.peers.toString()+enabled+problem+query
        if(signature!=rendered) {
            rendered=signature;users.removeAllViews()
            if(matches.isEmpty()) {
                if(query.isNotBlank())users.addView(RabtaUi.empty(activity,"search","No nearby matches","Try another display name or Rabta ID.","Clear search") {search.field.setText("")})
                else {
                users.addView(RabtaUi.empty(activity,"nearby",if(enabled)"No users found yet" else "Your Radar is private",if(enabled)"Both people need discoverability on, Nearby permissions and compatible Rabta versions. No location or distance is shown." else "Turn on discoverability when you want to meet someone nearby."))
            }
            }else matches.take(50).forEach { peer -> peerCard(peer,snapshot.peers.firstOrNull { it.id==peer.id }?.keyMatched==true,now) }
            if(matches.size>50)users.addView(RabtaUi.text(activity,"Showing 50 of ${matches.size} matching users",13f))
        }
        pendingOpen?.let { id ->
            val peer=peers.firstOrNull { it.id==id&&it.stage==RadarStage.CONNECTED }
            if(peer!=null&&snapshot.peers.any { it.id==id&&it.keyMatched }) {pendingOpen=null;openChat(peer)}
        }
    }
    private fun peerCard(peer:RadarPeer,keyMatched:Boolean,now:Long) {
        val card=RabtaUi.card(activity)
        val head=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
        head.addView(RabtaUi.text(activity,peer.name.take(1).uppercase().ifBlank { "R" },18f,true,AppTheme.accent(activity)).apply {
            gravity=Gravity.CENTER;background=RabtaUi.shape(activity,AppTheme.raised(activity),28,AppTheme.accent(activity))
            contentDescription="${peer.name} avatar"
        },LinearLayout.LayoutParams(RabtaUi.dp(activity,44),RabtaUi.dp(activity,44)))
        val identity=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(RabtaUi.dp(activity,12),0,0,0) }
        identity.addView(RabtaUi.text(activity,peer.name.ifBlank { "Rabta user" },15f,true))
        identity.addView(RabtaUi.text(activity,peer.id,12f,color=AppTheme.sub(activity)))
        head.addView(identity,LinearLayout.LayoutParams(0,-2,1f))
        head.addView(RabtaUi.iconButton(activity,"more","Options for ${peer.name}") {
            val actions=mutableListOf<RabtaUi.SheetAction>()
            if(peer.stage==RadarStage.CONNECTED)actions.add(RabtaUi.SheetAction("Compare security code") {MeshSecurity.showContact(activity,peer.id)})
            actions.add(RabtaUi.SheetAction("Block user") {confirmBlock(peer)})
            RabtaUi.sheet(activity,peer.name.ifBlank {"Rabta user"},actions)
        })
        card.addView(head)
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
            }
            else -> card.addView(RabtaUi.button(activity,if(now<peer.retryAt)"Request cooldown · try again shortly" else "Request connection",now>=peer.retryAt) {
                if(MeshBridge.requestConnection(peer.endpoint))pendingOpen=peer.id else Toast.makeText(activity,"Request unavailable. Refresh Radar or wait for the cooldown.",Toast.LENGTH_SHORT).show()
                refresh()
            }.apply { isEnabled=now>=peer.retryAt;alpha=if(isEnabled)1f else 0.55f })
        }
        users.addView(card)
    }
    private fun confirmBlock(peer:RadarPeer) {
        AlertDialog.Builder(activity).setTitle("Block ${peer.name}?")
            .setMessage("Disconnect this user and reject future Radar requests. Manage blocked users in Privacy settings.")
            .setNegativeButton("Cancel",null).setPositiveButton("Block") { _,_ -> MeshBridge.blockUser(activity,peer.id);if(pendingOpen==peer.id)pendingOpen=null;rendered="";refresh() }.show()
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
                    .addOnFailureListener { Toast.makeText(activity,"Update or enable Google Play services on this phone",Toast.LENGTH_LONG).show() }
            },
            RabtaUi.SheetAction("Retry connection") { WsClient.retry();retry() },
            RabtaUi.SheetAction("Connectivity diagnostics") {activity.startActivity(Intent(activity,ConnectivityDiagnosticsActivity::class.java))}
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
                        Toast.makeText(activity,"Contact added",Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(activity,"Key differs from saved contact. Verify with the person; key was not replaced.",Toast.LENGTH_LONG).show()
                }.show()
        }.onFailure { Toast.makeText(activity,"Invalid Rabta contact QR",Toast.LENGTH_LONG).show() }
        return true
    }
}

