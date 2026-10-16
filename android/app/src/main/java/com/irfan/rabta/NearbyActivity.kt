package com.irfan.rabta

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject

class NearbyActivity:ThemedActivity() {
    private lateinit var info:TextView
    private val handler=Handler(Looper.getMainLooper())
    private val tick=object:Runnable { override fun run(){ update();handler.postDelayed(this,3000) } }
    override fun onCreate(state:Bundle?) { super.onCreate(state);val col=FeatureUi.page(this,"Nearby");info=FeatureUi.text(this,"");col.addView(info)
        col.addView(FeatureUi.text(this,"Keep Bluetooth, Wi-Fi and Nearby permissions enabled. Text and built-in stickers can travel through nearby phones. Files, Status, groups and calls require internet. Delivery is confirmed only after the recipient receives it."))
        col.addView(FeatureUi.button(this,"My contact QR") { FeatureApi.run(this,{JSONObject().put("v",1).put("id",Session.id).put("name",Session.name).put("key",MeshSecurity(this,Session.id).publicText()).toString()}) { bundle -> val image=ImageView(this).apply { setImageBitmap(BarcodeEncoder().encodeBitmap(bundle,BarcodeFormat.QR_CODE,700,700));adjustViewBounds=true;setPadding(20,20,20,20) };AlertDialog.Builder(this).setTitle("Scan to add me offline").setView(image).setPositiveButton("Close",null).show() } })
        col.addView(FeatureUi.button(this,"Scan contact QR") { IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt("Scan a trusted Rabta contact").setBeepEnabled(false).setOrientationLocked(false).initiateScan() })
        col.addView(FeatureUi.button(this,"Allow Nearby permissions") {NearbySetup.request(this)})
        col.addView(FeatureUi.button(this,"Bluetooth settings") {startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))})
        col.addView(FeatureUi.button(this,"Wi-Fi settings") {startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))})
        col.addView(FeatureUi.button(this,"Location settings") {startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))})
        col.addView(FeatureUi.button(this,"App permissions") {startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))})
        col.addView(FeatureUi.button(this,"Check Google Play services") {com.google.android.gms.common.GoogleApiAvailability.getInstance().makeGooglePlayServicesAvailable(this).addOnSuccessListener {MessageService.restartNearby(this)}.addOnFailureListener {Toast.makeText(this,"Update or enable Google Play services on this phone",1).show()} })
        col.addView(FeatureUi.button(this,"Retry connection") {WsClient.retry();MessageService.restartNearby(this);update()})
    }
    override fun onResume(){super.onResume();MessageService.start(this);handler.post(tick)}
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==NearbySetup.PERMISSIONS){MessageService.restartNearby(this);update()}}
    override fun onPause(){handler.removeCallbacks(tick);super.onPause()}
    private fun update(){if(!::info.isInitialized)return;val peers=MeshBridge.peerNames();val waiting=Db.get(this).pendingMesh();info.text=(NearbySetup.problem(this)?:MeshBridge.status)+"\n\nConnected phones: ${peers.size}\n"+peers.joinToString("\n")+"\n\nWaiting messages: ${waiting.size}\n"+waiting.take(30).joinToString("\n") { val mins=((it.sentAt+86400000-System.currentTimeMillis())/60000).coerceAtLeast(0);"${it.convId} · ${MessageDelivery.label(it.status)} · ${mins/60}h ${mins%60}m left" }}
    override fun onActivityResult(req:Int,res:Int,data:Intent?){super.onActivityResult(req,res,data);val result=IntentIntegrator.parseActivityResult(req,res,data)?:return;val raw=result.contents?:return
        runCatching { require(raw.length<4096);val o=JSONObject(raw);val id=o.getString("id");require(MeshPolicy.validId(id)&&id!=Session.id);val key=o.getString("key");val code=MeshCrypto.fingerprint(key);Triple(id,o.optString("name",id).take(80),key) to code }.onSuccess { (contact,code) ->
            AlertDialog.Builder(this).setTitle("Add ${contact.second}?").setMessage("${contact.first}\n\n$code\n\nCompare this code with the person before adding.").setNegativeButton("Cancel",null).setPositiveButton("Add contact") { _,_ ->
                if(MeshSecurity(this,Session.id).pin(contact.first,contact.third)){Db.get(this).upsertConversation(contact.first,contact.second,"",System.currentTimeMillis(),false);Toast.makeText(this,"Contact added",0).show()}
                else Toast.makeText(this,"Key differs from saved contact. Verify with the person; key was not replaced.",1).show()
            }.show()
        }.onFailure { Toast.makeText(this,"Invalid Rabta contact QR",1).show() }
    }
}
