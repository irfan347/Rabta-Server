package com.irfan.rabta

import android.content.Context
import org.json.JSONObject
object Privacy {
    fun prefs(c:Context)=c.getSharedPreferences("privacy_${Session.id}",0)
    fun blocked(c:Context,id:String,account:String=Session.id)=c.getSharedPreferences("privacy_$account",0).getStringSet("blocked",emptySet())!!.contains(id)
    fun setBlocked(c:Context,id:String,value:Boolean) {
        val p=prefs(c);val set=p.getStringSet("blocked",emptySet())!!.toMutableSet();if(value)set.add(id)else set.remove(id)
        p.edit().putStringSet("blocked",set).putBoolean("pending_$id",value).commit()
        Thread { sync(c) }.start()
    }
    @Synchronized fun sync(c:Context) { for((k,v) in prefs(c).all)if(k.startsWith("pending_"))runCatching { FeatureApi.request("/api/block","POST",JSONObject().put("target",k.removePrefix("pending_")).put("blocked",v));if(prefs(c).all[k]==v)prefs(c).edit().remove(k).apply() } }
    fun hidePreviews(c:Context)=prefs(c).getBoolean("hide_previews",false)||prefs(c).getBoolean("lock",false)
}
