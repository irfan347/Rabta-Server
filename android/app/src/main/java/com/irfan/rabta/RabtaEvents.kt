package com.irfan.rabta

import android.os.Handler
import android.os.Looper

object RabtaEvents {
    interface Listener {
        fun onMessage(fromId: String, fromName: String, msgId: String, body: String, t: Long, msgType: String = "text", groupId: String = "") {}
        fun onAck(msgId: String, status: String) {}
        fun onRead(msgId: String, byId: String) {}
        fun onTyping(fromId: String) {}
        fun onIncomingCall(fromId: String, fromName: String) {}
        fun onCallAudio(fromId: String, data: String) {}
        fun onCallEnd(fromId: String) {}
        fun onHistoryChanged(convId: String) {}
        fun onConnected() {}
        fun onDisconnected() {}
        fun onGroupCreated(groupId: String, groupName: String, members: List<String>) {}
        fun onVideoOffer(fromId: String, fromName: String, callId: String, sdp: String) {}
        fun onVideoAnswer(callId: String, sdp: String) {}
        fun onIceCandidateEvent(callId: String, sdpMid: String, sdpMLineIndex: Int, sdp: String) {}
        fun onVideoEnd(callId: String) {}
    }

    private val listeners = mutableListOf<Listener>()
    private val handler = Handler(Looper.getMainLooper())

    @Synchronized fun add(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    @Synchronized fun remove(l: Listener) { listeners.remove(l) }
    private fun fire(block: Listener.() -> Unit) {
        handler.post { synchronized(listeners) { listeners.toList() }.forEach { it.block() } }
    }

    fun postMessage(fromId: String, fromName: String, msgId: String, body: String, t: Long, msgType: String = "text", groupId: String = "") =
        fire { onMessage(fromId, fromName, msgId, body, t, msgType, groupId) }
    fun postAck(msgId: String, status: String)               = fire { onAck(msgId, status) }
    fun postRead(msgId: String, byId: String)                = fire { onRead(msgId, byId) }
    fun postTyping(fromId: String)                           = fire { onTyping(fromId) }
    fun postIncomingCall(fromId: String, fromName: String)   = fire { onIncomingCall(fromId, fromName) }
    fun postCallAudio(fromId: String, data: String)          = fire { onCallAudio(fromId, data) }
    fun postCallEnd(fromId: String)                          = fire { onCallEnd(fromId) }
    fun postHistoryChanged(convId: String) = fire { onHistoryChanged(convId) }
    fun postConnected()                                      = fire { onConnected() }
    fun postDisconnected()                                   = fire { onDisconnected() }
    fun postGroupCreated(gId: String, gName: String, members: List<String>) = fire { onGroupCreated(gId, gName, members) }
    fun postVideoOffer(fromId: String, fromName: String, cId: String, sdp: String) = fire { onVideoOffer(fromId, fromName, cId, sdp) }
    fun postVideoAnswer(cId: String, sdp: String)                                  = fire { onVideoAnswer(cId, sdp) }
    fun postIceCandidate(cId: String, mid: String, idx: Int, sdp: String)          = fire { onIceCandidateEvent(cId, mid, idx, sdp) }
    fun postVideoEnd(cId: String)                                                  = fire { onVideoEnd(cId) }
}
