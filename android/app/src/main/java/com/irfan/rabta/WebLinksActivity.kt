package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject

class WebLinksActivity:ThemedActivity() {
    private lateinit var list:LinearLayout
    private val scan=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK)preview(result.data?.getStringExtra("pairing_code").orEmpty())
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(!Session.loggedIn){finish();return}
        val page=FeatureUi.page(this,"Linked browsers")
        page.addView(FeatureUi.text(this,"Link only a browser you control. Keep this phone connected. Web access includes recent one-to-one text chats; locked, archived, blocked and message-request chats stay on your phone. Links expire after 8 hours or a server restart."))
        page.addView(RabtaUi.button(this,"Scan browser QR code",true) { scan.launch(Intent(this,InstantScanActivity::class.java).putExtra("web_pairing",true)) })
        page.addView(RabtaUi.button(this,"Enter pairing code") {
            val input=EditText(this).apply {hint="32-character code shown in Rabta Web";setSingleLine(true)}
            AlertDialog.Builder(this).setTitle("Link a browser").setView(input).setNegativeButton("Cancel",null)
                .setPositiveButton("Continue") {_,_->preview(input.text.toString().trim())}.show()
        })
        list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};page.addView(list)
    }
    override fun onResume(){super.onResume();load()}
    private fun identity()="${Session.serverUrl}|${Session.id}|${Session.token}"
    private fun toast(value:String)=android.widget.Toast.makeText(this,value,android.widget.Toast.LENGTH_LONG).show()
    private fun preview(raw:String) {
        val code=runCatching {
            if(raw.startsWith("{"))JSONObject(raw).also { require(it.optString("kind")=="rabta-web" && it.optInt("v")==1) }.optString("code") else raw
        }.getOrDefault("")
        if(!code.matches(Regex("[a-f0-9]{32}"))){toast("Invalid browser pairing code");return}
        val account=identity()
        WsClient.api("POST","/api/web-links/preview",JSONObject().put("code",code)) {status,info ->
            if(identity()!=account || isFinishing)return@api
            if(status!=200){toast(info.optString("error","Web linking is not available on this server yet."));return@api}
            AlertDialog.Builder(this).setTitle("Link ${info.optString("name","this browser")}?")
                .setMessage("Web address: ${info.optString("origin")}\n\nThis browser can read recent unlocked chats and send text messages through your phone. Approve only if you just opened Rabta Web yourself. You can revoke access here.")
                .setNegativeButton("Cancel",null).setPositiveButton("Link browser") {_,_->
                    if(identity()!=account)return@setPositiveButton
                    WsClient.api("POST","/api/web-links/approve",JSONObject().put("code",code)) {result,linked ->
                        if(identity()!=account || isFinishing)return@api
                        if(result==200 && WebCompanion.allow(this,linked.optString("id"),linked.optLong("expires")))toast("Browser linked")
                        else toast(linked.optString("error","Could not approve this browser"))
                        load()
                    }
                }.show()
        }
    }
    private fun load() {
        val account=identity();list.removeAllViews();list.addView(FeatureUi.text(this,"Loading linked browsers…"))
        WsClient.api("GET","/api/web-links",null) {status,body ->
            if(identity()!=account || isFinishing)return@api
            list.removeAllViews()
            if(status!=200){list.addView(FeatureUi.text(this,body.optString("error","Browser linking is not enabled on this server yet.")));return@api}
            val links=body.optJSONArray("links") ?: org.json.JSONArray()
            if(links.length()==0)list.addView(FeatureUi.text(this,"No linked browsers"))
            for(i in 0 until links.length()) {
                val link=links.getJSONObject(i);val id=link.optString("id")
                list.addView(RabtaUi.row(this,link.optString("name","Browser"),"Tap to unlink","devices") {
                    AlertDialog.Builder(this).setTitle("Unlink this browser?").setNegativeButton("Cancel",null).setPositiveButton("Unlink") {_,_->
                        if(identity()!=account)return@setPositiveButton
                        WebCompanion.revoke(this,id)
                        WsClient.api("DELETE","/api/web-links/$id",null) {result,_->toast(if(result==200)"Browser unlinked" else "Blocked on this phone. Reconnect to remove its server session.");load()}
                    }.show()
                })
            }
        }
    }
}
