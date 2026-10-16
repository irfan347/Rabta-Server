package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri

object ConnectionHelp {
    fun show(activity: Activity) {
        val nearby = NearbySetup.problem(activity) ?: MeshBridge.status
        val internetDetail = buildString {
            append("${WsClient.status}\n")
            append("Server: ${Uri.parse(Session.serverUrl).host ?: "Not configured"}")
            if (WsClient.connected && WsClient.protocol < 2) append("\nThis server uses an older call protocol. Calls need the current Rabta server.")
        }
        val nearbyDetail = buildString {
            append(nearby)
            append("\nConnected phones: ${MeshBridge.peersConnected}")
            append("\nBoth phones need Rabta open, Bluetooth, Wi-Fi and Nearby permissions.")
        }
        RabtaUi.sheet(activity, "Connection & permissions", listOf(
            RabtaUi.SheetAction("Internet connection", internetDetail) {
                AlertDialog.Builder(activity).setTitle("Internet diagnostics").setMessage(internetDetail)
                    .setPositiveButton("Close", null).show()
            },
            RabtaUi.SheetAction("Nearby network", nearbyDetail) {
                activity.startActivity(Intent(activity, NearbyActivity::class.java))
            },
            RabtaUi.SheetAction(if (WsClient.authRequired) "Sign in again" else "Retry connections", "Restart internet and Nearby connection") {
                if (WsClient.authRequired) activity.startActivity(Intent(activity, AuthActivity::class.java).putExtra("reauth", true))
                else { WsClient.retry(); MessageService.restartNearby(activity) }
            },
            RabtaUi.SheetAction("Nearby permissions", "Review Bluetooth, Wi-Fi and location access") { NearbySetup.request(activity) },
            RabtaUi.SheetAction("Android app permissions", "Open Rabta's system permission controls") {
                activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
            },
            RabtaUi.SheetAction("Bluetooth settings", "Bluetooth pairing alone does not connect Rabta chats") {
                activity.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        ))
    }
}
