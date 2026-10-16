package com.irfan.rabta

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
object L {
    fun prefs(c:Context)=c.getSharedPreferences("appearance",0)
    fun signature(c:Context)=prefs(c).getString("language","en")+":"+prefs(c).getFloat("scale",1f)
    fun context(c:Context):Context { val cfg=Configuration(c.resources.configuration);cfg.setLocale(Locale(prefs(c).getString("language","en")?:"en"));cfg.fontScale=prefs(c).getFloat("scale",1f);return c.createConfigurationContext(cfg) }
    private val ur=mapOf("Chats" to "چیٹس","Calls" to "کالز","Status" to "اسٹیٹس","Nearby" to "قریبی رابطے","Settings" to "ترتیبات","Appearance" to "تھیم","Language" to "زبان","Text size" to "متن کا سائز","Privacy" to "رازداری","Hide notification previews" to "اطلاع میں پیغام چھپائیں","App lock" to "ایپ لاک","Encrypted backup" to "محفوظ بیک اپ","Restore backup" to "بیک اپ بحال کریں","Starred messages" to "محفوظ پیغامات","New chat" to "نئی چیٹ","New group" to "نیا گروپ","Message" to "پیغام","Cancel" to "منسوخ","Send" to "بھیجیں","Photo" to "تصویر","Video" to "ویڈیو","Refresh" to "تازہ کریں","My contact QR" to "میرا رابطہ کیو آر","Scan contact QR" to "رابطہ اسکین کریں","Text status" to "تحریری اسٹیٹس","Photo status" to "تصویری اسٹیٹس","Video status" to "ویڈیو اسٹیٹس","Reply" to "جواب","React" to "ردعمل","Forward" to "آگے بھیجیں","Copy" to "نقل","Star / unstar" to "محفوظ کریں / ہٹائیں","Delete for me" to "میرے لیے حذف کریں","Block / unblock" to "بلاک / ان بلاک","Report contact" to "رابطے کی شکایت","Search names or messages" to "نام یا پیغام تلاش کریں","Today" to "آج","Yesterday" to "کل")
    fun t(c:Context,s:String)=if(prefs(c).getString("language","en")=="ur")ur[s]?:s else s
}
