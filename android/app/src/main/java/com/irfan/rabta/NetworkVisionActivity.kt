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
    private lateinit var paging:LinearLayout
    private lateinit var previous:TextView
    private lateinit var next:TextView
    private lateinit var pageNumber:TextView
    private var current=VisionState()
    private var owner=""
    private var selected:String?=null
    private var visible=false
    private var listLimit=40
    private val poll=object:Runnable {override fun run(){if(visible){refresh();handler.postDelayed(this,2000)}}}
    private fun dp(n:Int)=RabtaUi.dp(this,n)
    private fun text(value:String,size:Float=14f,strong:Boolean=false,color:Int=AppTheme.text(this))=RabtaUi.text(this,value,size,strong,color)
    private fun button(label:String,run:()->Unit)=RabtaUi.button(this,label,action=run).apply {
        setTextColor(AppTheme.accent(this@NetworkVisionActivity));background=RabtaUi.ripple(this@NetworkVisionActivity,AppTheme.surface(this@NetworkVisionActivity),12)
        setPadding(dp(14),dp(10),dp(14),dp(10))
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(!Session.loggedIn){finish();return}
        owner=Session.id
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(AppTheme.bg(this@NetworkVisionActivity))}
        val head=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(8),dp(20),dp(4))}
        head.addView(RabtaUi.iconButton(this,"back","Back") {onBackPressedDispatcher.onBackPressed()}.apply { UiIcons.set(this,"back",AppTheme.text(this@NetworkVisionActivity),label="Back") })
        head.addView(text("Network Vision",24f,true),LinearLayout.LayoutParams(0,-2,1f));root.addView(head)
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),dp(24))}
        counts=text("Checking current peers…",15f,true);counts.tag="vision-counts";body.addView(counts)
        condition=text("",13f,color=AppTheme.sub(this@NetworkVisionActivity)).apply {setPadding(0,dp(6),0,0)};body.addView(condition)
        graph=NetworkVisionView(this).apply {
            tag="network-graph"
            onPeer={ selectPeer(it) }
        }
        body.addView(graph,LinearLayout.LayoutParams(-1,dp(300)).apply {topMargin=dp(16)})
        val graphTools=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        graphTools.addView(text("Network view",13f,color=AppTheme.sub(this@NetworkVisionActivity)),LinearLayout.LayoutParams(0,-2,1f))
        graphTools.addView(RabtaUi.iconButton(this,"zoom_out","Zoom out") {graph.zoomBy(0.8f)})
        graphTools.addView(RabtaUi.iconButton(this,"zoom_in","Zoom in") {graph.zoomBy(1.25f)})
        graphTools.addView(RabtaUi.iconButton(this,"restore","Reset network view") {graph.resetViewport()})
        body.addView(graphTools)
        paging=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        previous=RabtaUi.iconButton(this,"back","Previous peers") {graph.changePage(-1)}
        next=RabtaUi.iconButton(this,"forward","Next peers") {graph.changePage(1)}
        pageNumber=text("",13f,color=AppTheme.sub(this@NetworkVisionActivity)).apply {gravity=Gravity.CENTER}
        paging.addView(previous);paging.addView(pageNumber,LinearLayout.LayoutParams(0,-2,1f));paging.addView(next);body.addView(paging)
        graph.onViewportChanged={updatePaging()}
        updatePaging()
        graphCaption=text("Schematic layout · no distance estimates\nTeal: verified direct link · Violet: verifying key",12f,color=AppTheme.sub(this@NetworkVisionActivity))
        body.addView(graphCaption)
        empty=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(0,dp(18),0,dp(12))
            addView(text("No nearby devices yet",18f,true))
            addView(text("Turn on discoverability in Radar on both phones. Connections appear here after discovery; approval stays in your control.",13f,color=AppTheme.sub(this@NetworkVisionActivity)))
            addView(button("Open Radar") {startActivity(Intent(this@NetworkVisionActivity,NearbyActivity::class.java))})
        };body.addView(empty)
        details=text("",14f).apply {
            tag="vision-details";setPadding(dp(16),dp(16),dp(16),dp(16))
            background=RabtaUi.shape(this@NetworkVisionActivity,AppTheme.surface(this@NetworkVisionActivity),16)
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        };body.addView(details,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(16);bottomMargin=dp(16)})
        body.addView(text("Peers",18f,true))
        list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};body.addView(list)
        body.addView(text("Network activity",16f,true).apply {setPadding(0,dp(20),0,dp(8))})
        evidence=text("",13f,color=AppTheme.sub(this@NetworkVisionActivity));body.addView(evidence)
        body.addView(text("Latest message activity",16f,true).apply {setPadding(0,dp(20),0,dp(8))})
        activity=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};body.addView(activity)
        body.addView(text("Peer labels are private to this screen. Names, phone numbers, account IDs, endpoint IDs, message contents and keys are not displayed.",12f,color=AppTheme.sub(this@NetworkVisionActivity)).apply {setPadding(0,dp(20),0,dp(8))})
        body.addView(RabtaUi.row(this,"About network information","Privacy, link quality and relay limits","info") {
            android.app.AlertDialog.Builder(this).setTitle("Network information").setMessage("Only observed devices and direct connections are drawn. Layout does not represent location or distance. Signal strength and radio RTT are not exposed by the current Nearby transport. Remote relay routes cannot be verified: packets contain no signed route proof. Motion means a live link, not message traffic.").setPositiveButton("Close",null).show()
        })
        root.addView(ScrollView(this).apply {addView(body);isVerticalScrollBarEnabled=false},LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
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
        graphCaption.text="Schematic layout · no distance estimates\nTeal: verified direct link · Violet: verifying key"
        evidence.text="${current.relays} signed relay envelopes · ${current.receipts} recipient receipts\nVerified remote relay links: unavailable."
        updatePaging()
    }
    private fun updatePaging() {
        if(!::paging.isInitialized)return
        paging.visibility=if(graph.pageCount()>1)View.VISIBLE else View.GONE
        pageNumber.text="Page ${graph.pageNumber()} of ${graph.pageCount().coerceAtLeast(1)}"
        previous.isEnabled=graph.pageNumber()>1;next.isEnabled=graph.pageNumber()<graph.pageCount()
        previous.alpha=if(previous.isEnabled)1f else 0.4f;next.alpha=if(next.isEnabled)1f else 0.4f

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
        details.text=if(p==null)"This phone\n${current.directCount} active direct connections. Select a node or peer for details.\nLinks appear only for active direct connections." else
            "${p.label}\n${peerStatus(p)}\n${if(p.discovered)"Currently discovered by Nearby" else "Connected directly; not currently advertised"}\nIdentity: ${if(p.keyMatched)"signature matches saved key (trust on first use)" else "not confirmed"}\nLast recipient receipt: ${p.receiptMillis?.let { "$it ms · includes queue / relay time; not radio RTT" }?:"not measured in this service session"}"
    }
    private fun renderEvents() {
        activity.removeAllViews()
        if(current.events.isEmpty()){activity.addView(text("No observed message events in this service session.",13f,color=AppTheme.sub(this@NetworkVisionActivity)));return}
        current.events.takeLast(6).asReversed().forEach { event ->
            val label=when(event.kind){NetworkEventKind.QUEUED->"Nearby message queued";NetworkEventKind.RECEIVED->"Nearby message saved";NetworkEventKind.DELIVERED->"Recipient delivery confirmed";NetworkEventKind.RELAY_ACCEPTED->"Signed relay envelope accepted"}
            val time=android.text.format.DateFormat.getTimeFormat(this).format(Date(event.time))
            activity.addView(text("$time · $label${event.peerLabel?.let { " · $it" }.orEmpty()}",13f,color=AppTheme.sub(this@NetworkVisionActivity)).apply {setPadding(0,dp(5),0,dp(5))})
        }
    }
}
