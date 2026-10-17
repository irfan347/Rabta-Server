package com.irfan.rabta

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/** Read-only diagnostics; no message bodies, tokens, or key material are displayed. */
class ConnectivityDiagnosticsActivity:ThemedActivity() {
    private val uiHandler=Handler(Looper.getMainLooper())
    private var visible=false
    private var reading=false
    private lateinit var radios:TextView
    private lateinit var links:TextView
    private lateinit var peers:TextView
    private lateinit var queues:TextView
    private lateinit var deliveries:TextView
    private lateinit var relay:TextView
    private lateinit var updated:TextView
    private val tick=object:Runnable {
        override fun run() { if(!visible)return;refresh();uiHandler.postDelayed(this,5000) }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val body=FeatureUi.page(this,"Connectivity diagnostics")
        body.addView(FeatureUi.text(this,"Live connection information from this phone. Refreshes every 5 seconds while this screen is open."))
        radios=section(body,"Radios & permissions")
        links=section(body,"Active connections")
        peers=section(body,"Connected peers")
        queues=section(body,"Offline queue")
        deliveries=section(body,"Outgoing delivery states")
        relay=section(body,"Relay evidence")
        updated=FeatureUi.text(this,"Loading diagnostics…");body.addView(updated)
        body.addView(RabtaUi.button(this,"Network Vision") { startActivity(Intent(this,NetworkVisionActivity::class.java)) })
        body.addView(RabtaUi.button(this,"Refresh diagnostics",true) { refresh() })
        body.addView(RabtaUi.button(this,"Retry connections") {
            if(WsClient.authRequired)startActivity(Intent(this,AuthActivity::class.java).putExtra("reauth",true))
            else { WsClient.retry();MessageService.restartNearby(this) }
            refresh()
        })
        body.addView(RabtaUi.button(this,"Allow Nearby permissions") { NearbySetup.request(this) })
        body.addView(RabtaUi.button(this,"Nearby tools & contact QR") { startActivity(Intent(this,NearbyActivity::class.java)) })
        body.addView(RabtaUi.button(this,"Android app settings") { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) })
    }
    private fun section(body:LinearLayout,title:String):TextView {
        val card=RabtaUi.card(this)
        card.addView(RabtaUi.text(this,title,18f,true).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this,true) })
        val value=RabtaUi.text(this,"Checking…",14f,color=AppTheme.sub(this)).apply { setPadding(0,RabtaUi.dp(this@ConnectivityDiagnosticsActivity,8),0,0) }
        card.addView(value);body.addView(card);return value
    }
    override fun onResume() { super.onResume();visible=true;uiHandler.post(tick) }
    override fun onPause() { visible=false;uiHandler.removeCallbacks(tick);super.onPause() }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grants:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grants)
        if(requestCode==NearbySetup.PERMISSIONS){MessageService.restartNearby(this);refresh()}
    }
    private fun bluetoothState():String {
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return "Permission needed"
        return runCatching {
            val adapter=(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if(adapter==null)"Unavailable" else if(adapter.isEnabled)"On" else "Off"
        }.getOrDefault("Unavailable or permission denied")
    }
    private fun internetBearer():String = runCatching {
        val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps=cm.getNetworkCapabilities(cm.activeNetwork)?:return@runCatching "No active network"
        val types=buildList {
            if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))add("Wi-Fi")
            if(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))add("Mobile data")
            if(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))add("Ethernet")
            if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))add("VPN")
        }.joinToString(" + ").ifEmpty { "Other network" }
        "$types · ${if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))"Android validated internet" else "Internet not validated"}"
    }.getOrDefault("Network information unavailable")
    private fun refresh() {
        if(!visible||reading)return
        reading=true
        val account=Session.id
        val snapshot=MeshBridge.snapshot()
        val bluetooth=bluetoothState()
        val wifi=runCatching { if((applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled)"On" else "Off" }.getOrDefault("Unavailable")
        val missing=NearbySetup.permissions().filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }.map { it.substringAfterLast('.') }
        radios.text="Bluetooth: $bluetooth\nWi-Fi: $wifi\nPermissions: ${if(missing.isEmpty())"Granted" else missing.joinToString(", ")}\n${NearbySetup.problem(this)?:"Nearby prerequisites available"}"
        links.text="Internet: ${WsClient.status}\n${internetBearer()}\nOffline: ${if(snapshot.peers.isEmpty())"No connected peer" else "Nearby Connections · P2P_CLUSTER"}\nRadio for each Nearby link: managed by Nearby; not exposed to Rabta\nRadar discoverability: ${if(RadarPrefs.enabled(this))"On · approval required" else "Off"}\nDiscovery: ${if(snapshot.active)"Advertising and searching" else "Not active"}\nFound: ${snapshot.discovered} · Connecting: ${snapshot.connecting}\n${MeshBridge.status}"
        peers.text=if(snapshot.peers.isEmpty())"No phones connected." else snapshot.peers.joinToString("\n\n") {
            "${it.name}\n${it.id}\n${if(it.keyMatched)"Signed key matches pinned identity" else "Key exchange not confirmed"}"
        }
        relay.text="Signature-checked relay envelopes accepted this service session: ${snapshot.relayAccepted}\nValid recipient receipts accepted this session: ${snapshot.receiptsAccepted}\n\nThese counters verify packet processing, not the physical route. Multi-hop radio delivery has not been verified. Hop counts are mutable and are not proof of a route.\n\nIdentity keys use Android Keystore. Contact keys use trust on first use; compare security codes through a trusted channel."
        Thread {
            val result=runCatching {
                require(MeshPolicy.validId(account)){"Sign in to inspect your offline queue"}
                val stats=if(getDatabasePath("mesh_v2_$account.db").exists())MeshStore(applicationContext,account).use { it.stats() } else MeshStore.Stats()
                val database=Db.get(applicationContext)
                Triple(stats,database.pendingOutgoing().filter { it.fromId==account },database.deliveryStates(account))
            }
            uiHandler.post {
                reading=false
                if(!visible||isDestroyed||Session.id!=account)return@post
                result.onSuccess { (stats,pending,states) ->
                    queues.text="Awaiting encryption or carry space: ${stats.pendingPlaintext}\nEncrypted outgoing packets: ${stats.ownPackets}\nStored relay messages: ${stats.relayMessages}\nQueued receipts: ${stats.receipts}\nEncrypted packet storage: ${stats.bytes/1024} KB\n\n"+
                        (if(pending.isEmpty())"No outgoing messages waiting for a transport." else pending.take(20).joinToString("\n") { "${it.id} · ${MessageDelivery.label(it.status)}" })+
                        (if(pending.size>20)"\nShowing 20 of ${pending.size}" else "")
                    deliveries.text="Saved outgoing messages for this account, across online and offline transport:\n"+
                        (if(states.isEmpty())"No outgoing messages." else states.toSortedMap().entries.joinToString("\n") { "${MessageDelivery.label(it.key)}: ${it.value}" })
                    updated.text="Updated ${android.text.format.DateFormat.getTimeFormat(this).format(Date())}. Receipt counters reset with the service; queues survive restart."
                }.onFailure {
                    queues.text="Queue information unavailable. Refresh to retry."
                    deliveries.text="Delivery counts unavailable."
                    updated.text=it.message?:"Could not read diagnostics"
                }
            }
        }.start()
    }
}

