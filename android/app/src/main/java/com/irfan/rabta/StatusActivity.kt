package com.irfan.rabta

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray

class StatusActivity:ThemedActivity() {
    private lateinit var feed:LinearLayout
    private var posting=false
    override fun onCreate(state:Bundle?) { super.onCreate(state);val col=FeatureUi.page(this,"Status")
        col.addView(FeatureUi.text(this,"Share an update for 24 hours. Only contacts you select can view it. Photos and videos: up to 8 MB."))
        col.addView(FeatureUi.button(this,"Text status") { val input=EditText(this).apply { hint="Your update";filters=arrayOf(android.text.InputFilter.LengthFilter(700)) };AlertDialog.Builder(this).setTitle(L.t(this,"Text status")).setView(input).setNegativeButton("Cancel",null).setPositiveButton("Next") { _,_ -> if(input.text.isNotBlank()) audience("text",input.text.toString()) }.show() })
        col.addView(FeatureUi.button(this,"Photo status") { pick("image/*",31) });col.addView(FeatureUi.button(this,"Video status") { pick("video/*",32) })
        col.addView(FeatureUi.button(this,"Refresh") { refresh() });feed=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };col.addView(feed);refresh()
    }
    private fun pick(mime:String,code:Int) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE),code) }
    override fun onActivityResult(req:Int,res:Int,data:Intent?) { super.onActivityResult(req,res,data);if(res!=RESULT_OK||req !in 31..32)return;val uri=data?.data?:return;FeatureApi.run(this,{Attachments.pick(this,uri,false)}){ audience(if(req==31)"image" else "video",it) } }
    private fun audience(kind:String,body:String) {
        val contacts=Db.get(this).conversations().filter { !it.isGroup&&!Privacy.blocked(this,it.peerId) };val selected=BooleanArray(contacts.size)
        AlertDialog.Builder(this).setTitle("Who can view this status?").setMultiChoiceItems(contacts.map { it.peerName }.toTypedArray(),selected) { _,i,b -> selected[i]=b }
            .setNegativeButton("Cancel",null).setPositiveButton("Post") { _,_ ->
                if(posting)return@setPositiveButton
                val ids=contacts.filterIndexed { i,_ -> selected[i] }.map { it.peerId }
                if(ids.isEmpty()){Toast.makeText(this,"Select at least one contact",1).show();return@setPositiveButton}
                posting=true;val progress=FeatureUi.text(this,"Posting…");feed.addView(progress)
                Thread { val result=runCatching { val remote=if(kind=="text")body else Transfers.upload(this,body,"status") {};FeatureApi.request("/api/status","POST",JSONObject().put("kind",kind).put("body",remote).put("audience",JSONArray(ids))) };runOnUiThread { posting=false;if(isDestroyed)return@runOnUiThread;feed.removeView(progress);result.onSuccess { refresh() }.onFailure { Toast.makeText(this,it.message,1).show() } } }.start()
            }.show()
    }
    private fun refresh() { FeatureApi.run(this,{FeatureApi.request("/api/status").getJSONArray("statuses")}){ render(it) } }
    private fun render(items:JSONArray) {
        feed.removeAllViews();if(items.length()==0)feed.addView(FeatureUi.text(this,"No updates yet"))
        for(i in 0 until items.length()) {val s=items.getJSONObject(i);val own=s.getString("owner")==Session.id;val minutes=((s.getLong("expires")-System.currentTimeMillis())/60000).coerceAtLeast(0)
            feed.addView(FeatureUi.button(this,s.optString("name")+" · "+s.getString("kind")+"\n"+(if(own)"My status · " else "")+"${minutes/60}h ${minutes%60}m left") { view(s) })
            if(own){val viewers=s.optJSONArray("viewers")?:JSONArray();feed.addView(FeatureUi.button(this,"${viewers.length()} views · Manage") { AlertDialog.Builder(this).setTitle("Status views").setMessage((0 until viewers.length()).map { id -> val v=viewers.getString(id);Db.get(this).conversations().find { it.peerId==v }?.peerName?:v }.joinToString("\n").ifEmpty { "No views yet" }).setNegativeButton("Close",null).setPositiveButton("Delete status") { _,_ -> FeatureApi.run(this,{FeatureApi.request("/api/status/${s.getString("id")}","DELETE")}){refresh()} }.show() })}
        }
    }
    private fun view(s:JSONObject) {
        if(s.getLong("expires")<=System.currentTimeMillis()){refresh();return}
        FeatureApi.run(this,{FeatureApi.request("/api/status/${s.getString("id")}/view","POST")}) {
            if(s.getString("kind")=="text")AlertDialog.Builder(this).setTitle(s.optString("name")).setMessage(s.getString("body")).setPositiveButton("Close",null).show()
            else Attachments.open(this,s.getString("body"))
        }
    }
}
