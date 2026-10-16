package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.json.JSONObject

/** The same real Nearby controls in the home destination and standalone activity. */
class NearbyPanel(private val activity: Activity) {
    val view = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private val info: TextView
    private val handler=Handler(Looper.getMainLooper())
    private var active=false
    private var reading=false
    private val tick=object : Runnable {
        override fun run() { refresh(); if(active) handler.postDelayed(this,3000) }
    }
    init {
        val card=RabtaUi.card(activity)
        card.addView(RabtaUi.text(activity,"Nearby network",19f,true))
        info=RabtaUi.text(activity,"Checking nearby connection…",14f,color=AppTheme.sub(activity)).apply {
            setPadding(0,RabtaUi.dp(activity,12),0,0)
        }
        card.addView(info);view.addView(card)
        view.addView(RabtaUi.button(activity,"My contact QR",true) { showQr() })
        view.addView(RabtaUi.button(activity,"Scan contact QR") {
            IntentIntegrator(activity).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Scan a trusted Rabta contact").setBeepEnabled(false).setOrientationLocked(false).initiateScan()
        })
        view.addView(RabtaUi.button(activity,"Connection tools") { tools() })
        view.addView(RabtaUi.button(activity,"Connectivity diagnostics") { activity.startActivity(Intent(activity,ConnectivityDiagnosticsActivity::class.java)) })
        view.addView(FeatureUi.text(activity,"Keep Bluetooth, Wi-Fi and Nearby permissions enabled. Text and built-in stickers can travel through nearby phones. Files, Status, groups and calls require internet. Delivery is confirmed only after the recipient receives it."))
    }
    fun resume() { if(active)return;active=true;MessageService.start(activity);handler.post(tick) }
    fun pause() { active=false;handler.removeCallbacks(tick) }
    fun refresh() {
        if(!active||reading) return
        reading=true
        val problem=NearbySetup.problem(activity)?:MeshBridge.status
        val peers=MeshBridge.peerNames()
        Thread {
            val waiting=runCatching { Db.get(activity).pendingMesh() }.getOrNull()
            activity.runOnUiThread {
                reading=false
                if(!active||activity.isDestroyed||activity.isFinishing)return@runOnUiThread
                info.text=buildString {
                    append(problem)
                    append("\n\nConnected phones: ${peers.size}")
                    if(peers.isNotEmpty()) append("\n"+peers.joinToString("\n"))
                    if(waiting==null) append("\n\nCould not read waiting messages. Retry connection.") else {
                        append("\n\nWaiting messages: ${waiting.size}")
                        waiting.take(30).forEach {
                            val mins=((it.sentAt+86400000-System.currentTimeMillis())/60000).coerceAtLeast(0)
                            append("\n${it.convId} · ${MessageDelivery.label(it.status)} · ${mins/60}h ${mins%60}m left")
                        }
                    }
                }
            }
        }.start()
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
            AlertDialog.Builder(activity).setTitle("Scan to add me offline").setView(image).setPositiveButton("Close",null).show()
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
        runCatching {
            require(raw.length<4096);val o=JSONObject(raw);val id=o.getString("id")
            require(MeshPolicy.validId(id)&&id!=Session.id)
            val key=o.getString("key");val code=MeshCrypto.fingerprint(key)
            Triple(id,o.optString("name",id).take(80),key) to code
        }.onSuccess { (contact,code) ->
            AlertDialog.Builder(activity).setTitle("Add ${contact.second}?")
                .setMessage("${contact.first}\n\n$code\n\nCompare this code with the person before adding.")
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
