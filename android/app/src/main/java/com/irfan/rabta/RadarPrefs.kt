package com.irfan.rabta

import android.content.Context

object RadarPrefs {
    fun enabled(c:Context,account:String=Session.id)=MeshPolicy.validId(account)&&c.getSharedPreferences("radar_$account",0).getBoolean("discoverable",false)
    fun setEnabled(c:Context,value:Boolean) { if(MeshPolicy.validId(Session.id))c.getSharedPreferences("radar_${Session.id}",0).edit().putBoolean("discoverable",value).commit() }
}
