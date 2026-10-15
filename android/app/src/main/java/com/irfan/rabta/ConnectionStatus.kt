package com.irfan.rabta

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object ConnectionStatus {
    fun text(context: Context): String {
        if (WsClient.connected) return "Online" + if (MeshBridge.peersConnected > 0) " · Nearby: ${MeshBridge.peersConnected}" else ""
        if (MeshBridge.running) return if (MeshBridge.peersConnected > 0) "Nearby mode · ${MeshBridge.peersConnected} connected" else "Nearby mode · Searching…"
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val internet = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return if (internet) "Connecting…" else "Offline · Nearby unavailable"
    }
}
