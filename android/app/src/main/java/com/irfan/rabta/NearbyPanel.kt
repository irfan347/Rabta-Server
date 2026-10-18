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
    private val privacyPill:TextView
    init {
        val d={n:Int -> RabtaUi.dp(activity,n)}
        // ── Signal Orbit card ──
        val card=RabtaUi.card(activity).apply { setPadding(d(16),d(14),d(12),d(8));background=RabtaUi.shape(activity,AppTheme.surface(activity),24) }
        val header=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
        header.addView(TextView(activity).apply {
            UiIcons.set(this,"nearby",AppTheme.accent(activity),22,"Signal Orbit");gravity=Gravity.CENTER
            background=android.graphics.drawable.GradientDrawable().apply { shape=android.graphics.drawable.GradientDrawable.OVAL;setStroke(d(2),AppTheme.accent(activity)) }
            importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(d(40),d(40)))
        val title=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(d(12),0,d(8),0)}
        title.addView(RabtaUi.text(activity,"Signal Orbit",19f,true).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this,true) })
        title.addView(RabtaUi.text(activity,"Discover. Connect. Share.",12f,color=AppTheme.sub(activity)))
        header.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        // Mirrors the real discoverability setting; tapping it flips the same switch.
        privacyPill=RabtaUi.text(activity,"Private",12f,true,AppTheme.text(activity)).apply {
            gravity=Gravity.CENTER;minHeight=d(36);setPadding(d(12),0,d(12),0);isFocusable=true
            background=android.graphics.drawable.InsetDrawable(RabtaUi.ripple(activity,AppTheme.raised(activity),999),0,d(6),0,d(6))
            setOnClickListener { toggleDiscoverable() }
        }
        header.addView(privacyPill,LinearLayout.LayoutParams(-2,d(48)))
        header.addView(RabtaUi.iconButton(activity,"more","Nearby options") { options() }.apply { UiIcons.set(this,"more",AppTheme.sub(activity),22,"Nearby options") })
        card.addView(header)
        radar=RadarView(activity)
        card.addView(radar,LinearLayout.LayoutParams(-1,d(280)))
        card.addView(RabtaUi.text(activity,"Rings show connection stage, not distance or direction.",11f,color=AppTheme.sub(activity)).apply {
            gravity=Gravity.CENTER;setPadding(d(8),0,d(12),d(6))
        })
        view.addView(card)

        // ── Discoverability card ──
        val visibility=LinearLayout(activity).apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(d(16),d(12),d(12),d(12));background=RabtaUi.shape(activity,AppTheme.surface(activity),20)
        }
        val labels=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        labels.addView(RabtaUi.text(activity,"Discoverability",16f,true))
        info=RabtaUi.text(activity,"Off · You are not visible to nearby devices",12f,color=AppTheme.sub(activity)).apply {
            accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,d(2),0,0)
        }
        labels.addView(info)
        visibility.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        discoverable=Switch(activity).apply {
            minHeight=d(48);minWidth=d(48);isChecked=RadarPrefs.enabled(activity)
            contentDescription="Radar discoverability"
            thumbTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(AppTheme.onPrimary(activity),AppTheme.sub(activity)))
            trackTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(AppTheme.primary(activity),AppTheme.line(activity)))
            setOnCheckedChangeListener { _,enabled ->
                if(syncingDiscoverability)return@setOnCheckedChangeListener
                if(!Session.loggedIn){isChecked=false;Toast.makeText(activity,"Sign in to use Radar",Toast.LENGTH_SHORT).show();return@setOnCheckedChangeListener}
                MeshBridge.setDiscoverable(activity,enabled);pendingOpen=null;rendered="";refresh()
                if(enabled&&NearbySetup.problem(activity)!=null)guidance()
            }
        }
        visibility.addView(discoverable)
        view.addView(visibility,LinearLayout.LayoutParams(-1,-2).apply { topMargin=d(4);bottomMargin=d(10) })

        // ── Quick tools: each opens a real, existing feature ──
        val tools=LinearLayout(activity)
        val quick=listOfNotNull(
            tile("send","Quick Drop","Share files nearby") { activity.startActivity(Intent(activity,DropActivity::class.java)) },
            tile("qr","QR Connect","Scan or share ID") { activity.startActivity(Intent(activity,InstantConnectActivity::class.java)) },
            if(Config.testBuild(activity)) tile("vision","Network Vision","See live links") { activity.startActivity(Intent(activity,NetworkVisionActivity::class.java)) } else null
        )
        quick.forEachIndexed { i,v -> tools.addView(v,LinearLayout.LayoutParams(0,-1,1f).apply { if(i<quick.size-1) marginEnd=d(8) }) }
        view.addView(tools)

        // ── Nearby devices ──
        val nearbyHeading=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(d(4),d(12),0,0)}
        count=RabtaUi.text(activity,"Nearby devices (0)",16f,true)
        nearbyHeading.addView(count,LinearLayout.LayoutParams(0,-2,1f))
        nearbyHeading.addView(RabtaUi.iconButton(activity,"restore","Retry discovery") { retry() })
        view.addView(nearbyHeading)
        search=CompactSearchView(activity,"Search nearby users") { value ->query=value;rendered="";refresh() }
        view.addView(search,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=d(8)})
        view.addView(users)
        view.addView(RabtaUi.text(activity,"No phone numbers are shared. Radar shows activity, not distance or location.",12f,color=AppTheme.sub(activity)).apply {setPadding(d(4),d(8),0,d(12))})
        view.addView(RabtaUi.row(activity,"Connection help","Permissions and settings","connection") { guidance() })
    }
    private fun toggleDiscoverable() { discoverable.isChecked=!discoverable.isChecked }
    private fun tile(icon:String,title:String,detail:String,action:()->Unit)=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;isFocusable=true;minimumHeight=RabtaUi.dp(activity,104)
        setPadding(RabtaUi.dp(activity,6),RabtaUi.dp(activity,14),RabtaUi.dp(activity,6),RabtaUi.dp(activity,12))
        background=RabtaUi.ripple(activity,AppTheme.surface(activity),20);contentDescription="$title. $detail"
        setOnClickListener { action() }
        addView(TextView(activity).apply {
            UiIcons.set(this,icon,AppTheme.accent(activity),28,title);gravity=Gravity.CENTER
            importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(RabtaUi.dp(activity,36),RabtaUi.dp(activity,36)))
        addView(RabtaUi.text(activity,title,14f,true).apply { gravity=Gravity.CENTER;maxLines=1;setPadding(0,RabtaUi.dp(activity,8),0,0);importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        addView(RabtaUi.text(activity,detail,11f,color=AppTheme.sub(activity)).apply { gravity=Gravity.CENTER;maxLines=2;importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO })
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
        RabtaUi.SheetAction("My contact QR","Let people add you by scanning") {showQr()},
        RabtaUi.SheetAction("Scan contact QR") {
            IntentIntegrator(activity).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt("Scan a trusted Rabta contact").setBeepEnabled(false).setOrientationLocked(false).initiateScan()
        },
        RabtaUi.SheetAction("NFC pairing","Tap phones to pair, where supported") {
            if(android.nfc.NfcAdapter.getDefaultAdapter(activity)==null) Toast.makeText(activity,"This phone has no NFC. Use QR Connect instead.",Toast.LENGTH_LONG).show()
            else activity.startActivity(Intent(activity,InstantConnectActivity::class.java))
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
        info.text=if(!enabled)"Off · You are not visible to nearby devices" else problem?:"On · ${MeshBridge.status}"
        privacyPill.text=if(enabled) "Visible" else "Private"
        privacyPill.setTextColor(if(enabled) AppTheme.accent(activity) else AppTheme.text(activity))
        privacyPill.contentDescription=if(enabled) "Visible to nearby devices. Tap to go private" else "Private. Tap to become discoverable"
        info.setTextColor(if(problem!=null)AppTheme.error(activity)else AppTheme.sub(activity))
        val peers=if(enabled)MeshBridge.radarPeers()else emptyList()
        radar.peers(peers.map { RadarView.Dot(it.id,it.name,when(it.stage) {
            RadarStage.CONNECTED -> RadarView.Orbit.CONNECTED
            RadarStage.OUTGOING,RadarStage.INCOMING,RadarStage.APPROVED -> RadarView.Orbit.PENDING
            else -> RadarView.Orbit.DISCOVERED
        }) })
        val matches=NearbySearch.filter(peers,query)
        count.text=if(query.isBlank())"Nearby devices (${peers.size})" else "${matches.size} of ${peers.size} devices"
        val now=SystemClock.elapsedRealtime()
        val signature=peers.joinToString { "${it.endpoint}:${it.id}:${it.name}:${it.stage}:${it.code}:${now>=it.retryAt}" }+snapshot.peers.toString()+enabled+problem+query
        if(signature!=rendered) {
            rendered=signature;users.removeAllViews()
            if(matches.isEmpty()) {
                if(query.isNotBlank())users.addView(RabtaUi.empty(activity,"search","No nearby matches","Try another display name or Rabta ID.","Clear search") {search.field.setText("")})
                else {
                users.addView(RabtaUi.empty(activity,"profile","No devices found",if(enabled)"Both people need Rabta open with discoverability on." else "Turn on discoverability when you want to meet someone nearby.").apply {
                    background=RabtaUi.shape(activity,AppTheme.surface(activity),20)
                })
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
            RadarStage.FOUND -> "Found nearby · send a request to connect"
            RadarStage.OUTGOING -> "Request sent · waiting for approval"
            RadarStage.INCOMING -> "Wants to connect with you"
            RadarStage.APPROVED -> "Accepted · connecting…"
            RadarStage.CONNECTED -> if(keyMatched)"Connected · verified" else "Connected · checking security…"
            RadarStage.FAILED -> "Not connected · try again"
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
            else -> card.addView(RabtaUi.button(activity,if(now<peer.retryAt)"Please wait a moment" else "Request connection",now>=peer.retryAt) {
                if(MeshBridge.requestConnection(peer.endpoint))pendingOpen=peer.id else Toast.makeText(activity,"Couldn't send the request. Try again in a moment.",Toast.LENGTH_SHORT).show()
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
        db.saveContact(peer.id,peer.name)   // a contact, not an empty chat: the chat appears once you write
        activity.startActivity(Intent(activity,ChatActivity::class.java).putExtra("peerId",peer.id).putExtra("peerName",peer.name))
    }
    private fun guidance() {
        val message=NearbySetup.problem(activity)?:"Everything is ready. Turn on discoverability on both phones, send a request, and check that the connection code matches before accepting."
        AlertDialog.Builder(activity).setTitle("Nearby permissions").setMessage(message+"\n\nAndroid needs these permissions to find phones nearby. Rabta never shows your location or phone number.")
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
            AlertDialog.Builder(activity).setTitle("My contact QR").setMessage("Anyone who scans this code can add you as a contact. For a one-time code, use QR Connect.").setView(image).setPositiveButton("Close",null).show()
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
            if(Config.testBuild(activity)) RabtaUi.SheetAction("Connectivity diagnostics") {activity.startActivity(Intent(activity,ConnectivityDiagnosticsActivity::class.java))} else null
        ).filterNotNull())
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
                .setMessage("Security code: $code\n\nCheck that this code matches on the other person's phone before adding them.")
                .setNegativeButton("Cancel",null).setPositiveButton("Add contact") { _,_ ->
                    if(MeshSecurity(activity,Session.id).pin(contact.first,contact.third)) {
                        Db.get(activity).saveContact(contact.first,contact.second)
                        Toast.makeText(activity,"Contact added",Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(activity,"This contact's security code has changed. Check with them before adding again.",Toast.LENGTH_LONG).show()
                }.show()
        }.onFailure { Toast.makeText(activity,"Invalid Rabta contact QR",Toast.LENGTH_LONG).show() }
        return true
    }
}

