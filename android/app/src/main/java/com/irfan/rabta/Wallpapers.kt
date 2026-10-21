package com.irfan.rabta

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

object Wallpapers {
    val names=arrayOf("Rabta doodles", "Plain theme", "Ocean", "Forest", "Sunset", "Midnight")
    fun drawable(c:Context,peer:String):Drawable {
        val prefs=Privacy.prefs(c);val choice=prefs.getInt("wallpaper_$peer",prefs.getInt("wallpaper_default",0))
        if(choice==0)return ChatWallpaper(c)
        val colors=when(choice) {
            2->intArrayOf(0xFF102A43.toInt(),0xFF174A5B.toInt())
            3->intArrayOf(0xFF102D25.toInt(),0xFF275341.toInt())
            4->intArrayOf(0xFF432C47.toInt(),0xFF753C37.toInt())
            5->intArrayOf(0xFF090D20.toInt(),0xFF242044.toInt())
            else->intArrayOf(AppTheme.bg(c),AppTheme.bg(c))
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,colors)
    }
    fun choose(a:ThemedActivity,peer:String="default",done:()->Unit={a.recreate()}) {
        val options=if(peer=="default")names.toList() else listOf("Use default wallpaper")+names
        RabtaUi.sheet(a,"Chat wallpaper",options.mapIndexed { i,name -> RabtaUi.SheetAction(name) {
            val edit=Privacy.prefs(a).edit()
            if(peer!="default"&&i==0)edit.remove("wallpaper_$peer") else edit.putInt("wallpaper_$peer",if(peer=="default")i else i-1)
            edit.apply();done()
        } })
    }
}
