package com.irfan.rabta

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Phone-owned, bounded text snapshots. A web session never becomes a phone login. */
object WebCompanion {
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private fun identity()="${Session.serverUrl}|${Session.id}|${Session.token}"
    private fun prefs(c:Context)=c.getSharedPreferences("web_links_"+java.security.MessageDigest.getInstance("SHA-256")
        .digest("${Session.serverUrl}|${Session.id}".toByteArray()).joinToString("") { "%02x".format(it) }.take(32),0)
    fun allow(c:Context,id:String,expires:Long)=prefs(c).edit().putLong(id,expires).commit()
    fun revoke(c:Context,id:String) { prefs(c).edit().remove(id).commit() }
    fun allowed(c:Context,id:String)=prefs(c).getLong(id,0)>System.currentTimeMillis()
    fun visible(c:Context,chat:Db.Conversation)=!chat.isGroup && !chat.archived &&
        !Privacy.chatLocked(c,chat.peerId) && !Privacy.blocked(c,chat.peerId) && !Privacy.request(c,chat.peerId)

    fun handle(o:JSONObject) {
        val account=identity();val accountId=Session.id;val ctx=Session.context();val link=o.optString("linkId");val request=o.optString("requestId")
        if(!request.matches(Regex("[a-f0-9]{32}")))return
        fun reply(ok:Boolean,data:JSONObject=JSONObject()) { main.post {
            if(identity()==account) {
                val permitted=ok && allowed(ctx,link)
                WsClient.sendRaw(JSONObject().put("type","web_reply").put("requestId",request).put("ok",permitted).put("data",if(permitted)data else JSONObject()).toString())
            }
        } }
        worker.execute {
            if(identity()!=account || !allowed(ctx,link)) {reply(false);return@execute}
            runCatching {
                val db=Db.get(ctx);require(db.account==accountId && identity()==account);val chats=db.conversations().filter { visible(ctx,it) }
                val peer=o.optString("peer");val op=o.optString("op")
                when(op) {
                    "chats" -> reply(true,JSONObject().put("chats",JSONArray().apply {
                        chats.take(100).forEach { put(JSONObject().put("id",it.peerId).put("name",Names.of(ctx,it.peerId,it.shownName).take(80)).put("unread",it.unread)) }
                    }))
                    "messages" -> {
                        val chat=chats.firstOrNull { it.peerId==peer } ?: error("Chat unavailable")
                        val rows=JSONArray();var bytes=0
                        // Keep newest history within a bounded response, including long UTF-8 messages.
                        val messages=db.messages(peer,80).asReversed().mapNotNull { m ->
                            val text=if(m.msgType=="text")m.body.take(4000)+(if(m.body.length>4000) "… (open on phone)" else "")
                                else "[${m.msgType} — open on phone]"
                            val row=JSONObject().put("id",m.id).put("text",text).put("mine",m.mine).put("at",m.sentAt).put("status",m.status)
                            val weight=row.toString().toByteArray().size
                            if(bytes+weight>180000)null else {bytes+=weight;row}
                        }.asReversed()
                        messages.forEach { rows.put(it) }
                        if(!visible(ctx,chat) || !allowed(ctx,link))error("Access changed")
                        reply(true,JSONObject().put("messages",rows))
                    }
                    "send" -> {
                        val chat=chats.firstOrNull { it.peerId==peer } ?: error("Chat unavailable")
                        val body=o.optString("text");val id=o.optString("messageId")
                        require(id.matches(Regex("[A-F0-9]{12}")) && body.isNotBlank() && body.toByteArray().size<=12000)
                        Outbox.sendFromWeb(peer,chat.peerName,body,id,guard={identity()==account && allowed(ctx,link) && visible(ctx,chat)}) { ok ->
                            reply(ok,JSONObject().put("queued",ok).put("messageId",id))
                        }
                    }
                    else -> reply(false)
                }
            }.onFailure { reply(false) }
        }
    }
}
