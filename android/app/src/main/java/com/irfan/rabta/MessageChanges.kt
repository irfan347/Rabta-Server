package com.irfan.rabta

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Changes are acknowledged only after SQLite stored them, including changes arriving before a message. */
object MessageChanges {
    private val worker=Executors.newSingleThreadExecutor()
    private val running=AtomicBoolean(false)
    private var lastAccount=""
    private var lastSync=0L
    fun sync(force:Boolean=false) {
        if(!Session.loggedIn||!WsClient.connected)return
        val account=Session.id;val now=SystemClock.elapsedRealtime()
        if(!force&&lastAccount==account&&now-lastSync<30000)return
        if(!running.compareAndSet(false,true))return
        lastAccount=account;lastSync=now
        val server=Session.serverUrl;val token=Session.token;val ctx=Session.context();val db=Db.get(ctx)
        worker.execute {
            try {
                repeat(10) {
                    if(Session.id!=account)return@execute
                    val batch=FeatureApi.request("/api/messages/changes",serverUrl=server,token=token).getJSONArray("changes")
                    if(batch.length()==0)return@execute
                    val ack=JSONArray()
                    for(i in 0 until batch.length()) {
                        if(Session.id!=account)return@execute
                        val change=batch.getJSONObject(i)
                        val conv=db.receiveChange(change)
                        ChatNotifications.clear(ctx,conv);RabtaEvents.postHistoryChanged(conv)
                        ack.put(JSONObject().put("id",change.getString("id")).put("from",change.getString("from")).put("revision",change.getLong("revision")))
                    }
                    FeatureApi.request("/api/messages/changes/ack","POST",JSONObject().put("items",ack),server,token)
                    if(batch.length()<100)return@execute
                }
            } catch (_:Exception) { /* Leave unacknowledged changes for reconnect/next heartbeat. */ }
            finally {running.set(false)}
        }
    }
    fun submit(a:Activity,m:Db.Message,action:String,text:String="",done:()->Unit) {
        val account=Session.id;val server=Session.serverUrl;val token=Session.token;val db=Db.get(a)
        val body=JSONObject().put("id",m.id).put("action",action).put("text",text).put("operationId",UUID.randomUUID().toString())
        FeatureApi.run(a,{
            val answer=FeatureApi.request("/api/messages/change","POST",body,server,token)
            if(Session.id==account)db.receiveChange(answer.getJSONObject("change"))
        }) {
            if(Session.id==account) {
                ChatNotifications.clear(a,m.convId);RabtaEvents.postHistoryChanged(m.convId);sync(true);done()
                Toast.makeText(a,if(action=="edit")"Message edited" else "Deletion sent. Other devices apply it when they sync.",Toast.LENGTH_LONG).show()
            }
        }
    }
}
