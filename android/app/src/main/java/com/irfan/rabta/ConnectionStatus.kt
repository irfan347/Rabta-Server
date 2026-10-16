package com.irfan.rabta

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object ConnectionStatus {
    fun text(context: Context): String {
        val nearby=if(MeshBridge.peersConnected>0) "Nearby: ${MeshBridge.peersConnected} connected" else MeshBridge.status
        return WsClient.status+" · "+nearby
    }
}
