package com.irfan.rabta

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date

/** Read-only view of current MeshBridge observations. Does not start services, discovery or connections. */
class NetworkVisionActivity:ThemedActivity() {
    private val handler=Handler(Looper.getMainLooper())
    private val reducer=NetworkVisionState()
    private lateinit var graph:NetworkVisionView
    private lateinit var counts:TextView
    private lateinit var condition:TextView
    private lateinit var details:TextView
    private lateinit var list:LinearLayout
    private lateinit var activity:LinearLayout
    private lateinit var empty:LinearLayout
    private lateinit var graphCaption:TextView
    private lateinit var evidence:TextView
    private var current=VisionState()
    private var owner=""
    private var selected:String?=null
    private var visible=false
    private var listLimit=40
    private val poll=object:Runnable {override fun run(){if(visible){refresh();handler.postDelayed(this,2000)}}}
    private fun dp(n:Int)=RabtaUi.dp(this,n)
    private fun text(value:String,size:Float=14f,strong:Boolean=false,color:Int=NetworkVisionView.TEXT)=RabtaUi.text(this,value,size,strong,color)
    private fun button(label:String,run:()->Unit)=RabtaUi.button(this,label,action=run).apply {
        setTextColor(NetworkVisionView.VIOLET);background=RabtaUi.ripple(this@NetworkVisionActivity,0xFF171D2C.toInt(),12)
        setPadding(dp(14),dp(10),dp(14),dp(10))
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(!Session.loggedIn){finish();return}
        owner=Session.id
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(NetworkVisionView.GRAPHITE)}
        val head=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(8),dp(20),dp(4))}
        head.addView(RabtaUi.iconButton(this,"back","Back") {onBackPressedDispatcher.onBackPressed()}.apply { UiIcons.set(this,"back",NetworkVisionView.TEXT,label="Back") })
        head.addView(text("Network Vision",24f,true),LinearLayout.LayoutParams(0,-2,1f));root.addView(head)
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),dp(24))}
        counts=text("Checking current peers…",15f,true);counts.tag="vision-counts";body.addView(counts)
        condition=text("",13f,color=NetworkVisionView.SUB).apply {setPadding(0,dp(6),0,0)};body.addView(condition)
        graph=NetworkVisionView(this).apply {
            tag="network-graph"
            onPeer={ selectPeer(it) }
        }
        body.addView(graph,LinearLayout.LayoutParams(-1,dp(360)))
        graphCaption=text("Schematic layout · no distance estimates\nTeal line: direct link · violet: key verification pending\nMotion marks a live link, not message traffic.",12f,color=NetworkVisionView.SUB)
        body.addView(graphCaption)
        empty=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(0,dp(18),0,dp(12))
            addView(text("No nearby devices yet",18f,true))
            addView(text("Open Radar on both phones and choose discoverability when you want to be found. No devices are simulated.",13f,color=NetworkVisionView.SUB))
            addView(button("Open Radar") {startActivity(Intent(this@NetworkVisionActivity,NearbyActivity::class.java))})
        };body.addView(empty)
        details=text("",14f).apply {
            tag="vision-details";setPadding(dp(16),dp(16),dp(16),dp(16))
            background=RabtaUi.shape(this@NetworkVisionActivity,0xFF171D2C.toInt(),16)
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        };body.addView(details,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(16);bottomMargin=dp(16)})
        body.addView(text("Peers",18f,true))
        list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};body.addView(list)
        body.addView(text("Relay evidence",18f,true).apply {setPadding(0,dp(20),0,dp(8))})
        evidence=text("",13f,color=NetworkVisionView.SUB);body.addView(evidence)
        body.addView(text("Message activity · current service session",18f,true).apply {setPadding(0,dp(20),0,dp(8))})
        activity=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};body.addView(activity)
        body.addView(text("Peer labels are private to this screen. Names, phone numbers, account IDs, endpoint IDs, message contents and keys are not displayed.",12f,color=NetworkVisionView.SUB).apply {setPadding(0,dp(20),0,dp(8))})
        body.addView(button("Connectivity diagnostics") {startActivity(Intent(this,ConnectivityDiagnosticsActivity::class.java))})
        root.addView(ScrollView(this).apply {addView(body);isVerticalScrollBarEnabled=false},LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);RabtaUi.systemBars(this,true)
        showDetails();renderEvents()
    }
    override fun onResume(){super.onResume();if(!::graph.isInitialized)return;visible=true;handler.post(poll);graph.foreground(true)}
    override fun onPause(){visible=false;handler.removeCallbacks(poll);if(::graph.isInitialized)graph.foreground(false);super.onPause()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);reducer.clear();super.onDestroy()}
    private fun refresh() {
        if(!Session.loggedIn||Session.id!=owner){reducer.clear();current=VisionState();graph.update(current,null);finish();return}
        val blocked=Privacy.prefs(this).getStringSet("blocked",emptySet()).orEmpty().toSet()
        val next=reducer.render(owner,RadarPrefs.enabled(this),MeshBridge.networkObservation(),blocked)
        counts.text="${next.peers.size} nearby peers · ${next.directCount} direct · ${next.discoveredCount} discovered"
        condition.text=when { !next.enabled->"Radar is off · Vision does not enable discovery.";next.active->"Reading live Nearby observations · updates every 2 seconds";else->"Discovery is not active. Open Radar or diagnostics for permissions and radio guidance." }
        if(next!=current) {
            current=next
            if(current.peers.none { it.id==selected })selected=null
            renderPeers();renderEvents();showDetails()
        }
        graph.update(current,selected)
        empty.visibility=if(current.peers.isEmpty())View.VISIBLE else View.GONE
        graphCaption.text="Schematic layout · no distance estimates\n${if(current.peers.size>12)"Showing 12 of ${current.peers.size} peers; select a peer below.\n" else ""}Teal line: direct link · violet: key verification pending\nMotion marks a live link, not message traffic."
        evidence.text="Signature-checked relay envelopes accepted: ${current.relays}\nValid recipient receipts accepted: ${current.receipts}\n\nVerified remote relay links: unavailable. Existing packets do not contain signed route proofs, so Vision draws no peer-to-peer relay routes. Counters do not prove topology."
    }
    private fun peerStatus(p:VisionPeer)=if(p.connected) {
        if(p.keyMatched)"Direct · key matches pinned identity" else "Direct · verifying identity key"
    }else when(p.stage) {
        RadarStage.INCOMING->"Discovered · approval requested"
        RadarStage.OUTGOING,RadarStage.APPROVED->"Discovered · connection pending"
        RadarStage.REJECTED->"Discovered · request declined"
        RadarStage.FAILED->"Discovered · disconnected"
        else->"Discovered · not connected"
    }
    private fun renderPeers() {
        list.removeAllViews()
        current.peers.take(listLimit).forEach { p ->
            list.addView(button("${p.label}\n${peerStatus(p)}") {
                selectPeer(p.id)
                details.requestRectangleOnScreen(android.graphics.Rect(0,0,details.width,details.height),true)
            }.apply {contentDescription="${p.label} details";isSelected=p.id==selected})
        }
        if(current.peers.size>listLimit)list.addView(button("Show more peers") {listLimit+=40;renderPeers()})
    }
    private fun selectPeer(id:String?) {
        selected=id;graph.update(current,selected);showDetails();renderPeers()
    }
    private fun showDetails() {
        val p=current.peers.firstOrNull {it.id==selected}
        details.text=if(p==null)"This phone\n${current.directCount} active direct connections. Select a node or peer for details.\nSignal strength / radio RTT: unavailable from the current Nearby API." else
            "${p.label}\n${peerStatus(p)}\n${if(p.discovered)"Currently discovered by Nearby" else "Connected directly; not currently advertised"}\nIdentity: ${if(p.keyMatched)"signature matches saved key (trust on first use)" else "not confirmed"}\nSignal strength / radio RTT: unavailable\nLast recipient receipt: ${p.receiptMillis?.let { "$it ms · includes queue / relay time; not radio RTT" }?:"not measured in this service session"}"
    }
    private fun renderEvents() {
        activity.removeAllViews()
        if(current.events.isEmpty()){activity.addView(text("No observed message events in this service session.",13f,color=NetworkVisionView.SUB));return}
        current.events.takeLast(12).asReversed().forEach { event ->
            val label=when(event.kind){NetworkEventKind.QUEUED->"Nearby message queued";NetworkEventKind.RECEIVED->"Nearby message saved";NetworkEventKind.DELIVERED->"Recipient delivery confirmed";NetworkEventKind.RELAY_ACCEPTED->"Signed relay envelope accepted"}
            val time=android.text.format.DateFormat.getTimeFormat(this).format(Date(event.time))
            activity.addView(text("$time · $label${event.peerLabel?.let { " · $it" }.orEmpty()}",13f,color=NetworkVisionView.SUB).apply {setPadding(0,dp(5),0,dp(5))})
        }
    }
}
