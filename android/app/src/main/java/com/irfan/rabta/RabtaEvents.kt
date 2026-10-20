package com.irfan.rabta

import android.os.Handler
import android.os.Looper

object RabtaEvents {
    interface Listener {
        fun onMessage(fromId: String, fromName: String, msgId: String, body: String, t: Long, msgType: String = "text", groupId: String = "") {}
        fun onAck(msgId: String, status: String) {}
        /** The recipient [byId] confirmed it stored [msgId]; [direct] when it came over a direct Nearby link. */
        fun onReceipt(msgId: String, byId: String, direct: Boolean) {}
        /** A Nearby peer completed its key exchange, so queued messages may now have a path. */
        fun onNearbyLinked() {}
        /** Another phone entered this account's PIN and waits for this trusted phone to allow it. */
        fun onDeviceRequest(requestId: String, deviceName: String) {}
        fun onRead(msgId: String, byId: String) {}
        /** [kind] is "recording" while they record a voice message, otherwise they are typing. */
        fun onTyping(fromId: String, kind: String) {}
        /** The server says whether [userId] has Rabta open now, or when it last had ([lastSeen], 0 when unknown). */
        fun onPresence(userId: String, online: Boolean, lastSeen: Long) {}
        fun onIncomingCall(fromId: String, fromName: String, callId: String) {}
        fun onCallAudio(fromId: String, data: String, callId: String) {}
        fun onCallState(fromId:String,callId:String,state:String) {}
        fun onCallEnd(fromId: String, callId: String) {}
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
    fun postReceipt(msgId: String, byId: String, direct: Boolean) = fire { onReceipt(msgId, byId, direct) }
    fun postNearbyLinked()                                   = fire { onNearbyLinked() }
    fun postDeviceRequest(requestId: String, deviceName: String) = fire { onDeviceRequest(requestId, deviceName) }
    fun postRead(msgId: String, byId: String)                = fire { onRead(msgId, byId) }
    fun postTyping(fromId: String, kind: String)             = fire { onTyping(fromId, kind) }
    fun postPresence(userId: String, online: Boolean, lastSeen: Long) = fire { onPresence(userId, online, lastSeen) }
    fun postIncomingCall(fromId: String, fromName: String, callId: String)   = fire { onIncomingCall(fromId, fromName, callId) }
    fun postCallAudio(fromId: String, data: String, callId: String)          = fire { onCallAudio(fromId, data, callId) }
    fun postCallState(fromId:String,callId:String,state:String) = fire { onCallState(fromId,callId,state) }
    fun postCallEnd(fromId: String, callId: String)                          = fire { onCallEnd(fromId, callId) }
    fun postHistoryChanged(convId: String) = fire { onHistoryChanged(convId) }
    fun postConnected()                                      = fire { onConnected() }
    fun postDisconnected()                                   = fire { onDisconnected() }
    fun postGroupCreated(gId: String, gName: String, members: List<String>) = fire { onGroupCreated(gId, gName, members) }
    fun postVideoOffer(fromId: String, fromName: String, cId: String, sdp: String) = fire { onVideoOffer(fromId, fromName, cId, sdp) }
    fun postVideoAnswer(cId: String, sdp: String)                                  = fire { onVideoAnswer(cId, sdp) }
    fun postIceCandidate(cId: String, mid: String, idx: Int, sdp: String)          = fire { onIceCandidateEvent(cId, mid, idx, sdp) }
    fun postVideoEnd(cId: String)                                                  = fire { onVideoEnd(cId) }
}
