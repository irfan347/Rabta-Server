package com.irfan.rabta
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
object ConnectionHelp {
    fun show(a:Activity) {
        val nearby=NearbySetup.problem(a)?:MeshBridge.status
        AlertDialog.Builder(a).setTitle("Connection details").setMessage("Internet: ${WsClient.status}\nServer: ${Session.serverUrl}\n\nNearby: $nearby\nConnected phones: ${MeshBridge.peersConnected}\n\nBoth phones need Rabta open, Bluetooth, Wi-Fi, Location and Nearby permissions. Nearby uses the app; pairing in Bluetooth settings alone does not connect a chat.")
            .setPositiveButton(if(WsClient.authRequired)"Sign in again" else "Retry") { _,_ -> if(WsClient.authRequired)a.startActivity(Intent(a,AuthActivity::class.java).putExtra("reauth",true))else {WsClient.retry();MessageService.restartNearby(a)} }
            .setNeutralButton("Nearby settings") { _,_ ->a.startActivity(Intent(a,NearbyActivity::class.java)) }.setNegativeButton("Close",null).show()
    }
}
