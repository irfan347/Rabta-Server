package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.*

/** Original bundled artwork, rendered as stickers (no dependency on WhatsApp). */
object Stickers {
    private val labels=listOf("Hello!","Shukriya","Love it","Hahaha","Wow!","Oh no","Let's go","Good night","Okay!","Sorry","Congrats!","Chai time")
    fun valid(body:String)=body.removePrefix("rbt:").toIntOrNull()?.let { body.startsWith("rbt:") && it in labels.indices } == true
    fun drawable(body:String):Drawable {
        val index=body.removePrefix("rbt:").toIntOrNull()?.coerceIn(labels.indices) ?: 0
        return object:Drawable() {
            val p=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(c:Canvas) {
                c.save(); c.translate(bounds.left.toFloat(),bounds.top.toFloat()); c.scale(bounds.width()/160f,bounds.height()/160f)
                p.color=Color.WHITE; c.drawRoundRect(10f,8f,150f,150f,36f,36f,p)
                p.color=listOf(0xFFDFD4FF,0xFFFFDEAA,0xFFB6ECDA,0xFFFFCBDC)[index%4].toInt(); c.drawRoundRect(14f,12f,146f,146f,32f,32f,p)
                p.color=0xFF33284D.toInt(); c.drawOval(48f,49f,57f,65f,p); c.drawOval(101f,49f,110f,65f,p)
                p.style=Paint.Style.STROKE; p.strokeWidth=4f
                if(index in listOf(5,9)) c.drawArc(60f,79f,100f,102f,200f,140f,false,p)
                else c.drawArc(58f,65f,102f,95f,5f,170f,false,p)
                p.style=Paint.Style.FILL; p.color=0x55F96D94; c.drawCircle(41f,76f,9f,p); c.drawCircle(118f,76f,9f,p)
                p.color=0xFF33284D.toInt(); p.textSize=15f; p.typeface=Typeface.DEFAULT_BOLD; p.textAlign=Paint.Align.CENTER
                c.drawText(labels[index],80f,128f,p); c.restore()
            }
            override fun setAlpha(a:Int) { p.alpha=a }
            override fun setColorFilter(f:ColorFilter?) { p.colorFilter=f }
            @Deprecated("Deprecated") override fun getOpacity()=PixelFormat.TRANSLUCENT
        }
    }
    fun picker(a:Activity,send:(String)->Unit,import:()->Unit) {
        val dp={n:Int -> (n*a.resources.displayMetrics.density).toInt()}
        val col=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)) }
        val scroll=ScrollView(a); val grid=GridLayout(a).apply { columnCount=3 }
        lateinit var dialog:AlertDialog
        labels.forEachIndexed { i,label -> grid.addView(ImageView(a).apply {
            setImageDrawable(drawable("rbt:$i")); contentDescription=label; setPadding(dp(4),dp(4),dp(4),dp(4)); setOnClickListener { send("rbt:$i"); dialog.dismiss() }
        },GridLayout.LayoutParams().apply { width=dp(88); height=dp(88) }) }
        scroll.addView(grid); col.addView(scroll)
        dialog=AlertDialog.Builder(a).setTitle("Rabta expressions").setView(col).setNeutralButton("Import sticker") { _,_ -> import() }.setNegativeButton("Close",null).create()
        dialog.show()
    }
}
