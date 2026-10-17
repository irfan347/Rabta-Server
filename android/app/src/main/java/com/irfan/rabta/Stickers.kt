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
    private fun items(c:android.content.Context,key:String):List<String> = runCatching {
        val a=org.json.JSONArray(c.getSharedPreferences("stickers",0).getString(key,"[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())
    private fun save(c:android.content.Context,key:String,values:List<String>) { c.getSharedPreferences("stickers",0).edit().putString(key,org.json.JSONArray(values).toString()).apply() }
    fun remember(c:android.content.Context,body:String) { save(c,"recent",(listOf(body)+items(c,"recent")).distinct().take(12)) }
    fun picker(a:Activity,send:(String)->Unit,import:()->Unit) {
        val dp={n:Int -> (n*a.resources.displayMetrics.density).toInt()}
        val col=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)) }
        col.addView(TextView(a).apply { text="Tap to send · Hold to favorite"; textSize=12f; setTextColor(AppTheme.sub(a)); setPadding(0,0,0,dp(12)) })
        val tabs=RadioGroup(a).apply { orientation=RadioGroup.HORIZONTAL }
        val scroll=ScrollView(a); val grid=GridLayout(a).apply { columnCount=3 }
        lateinit var dialog:AlertDialog
        fun show(category:Int) {
            grid.removeAllViews()
            val values=when(category) { 1->items(a,"recent"); 2->items(a,"favorites"); else->labels.indices.map { "rbt:$it" } }
            if(values.isEmpty()) { grid.addView(TextView(a).apply { text="No stickers here yet"; setTextColor(AppTheme.sub(a)); setPadding(dp(12),dp(24),dp(12),dp(24)) }); return }
            for(body in values) {
                val icon=ImageView(a).apply {
                    contentDescription=if(valid(body)) labels[body.removePrefix("rbt:").toInt()] else "Custom sticker"
                    scaleType=ImageView.ScaleType.FIT_CENTER; setPadding(dp(4),dp(4),dp(4),dp(4))
                    if(valid(body)) setImageDrawable(drawable(body)) else runCatching { setImageURI(android.net.Uri.fromFile(Attachments.file(a,body))) }
                    setOnClickListener { remember(a,body); send(body); dialog.dismiss() }
                    setOnLongClickListener {
                        val favorites=items(a,"favorites"); val remove=body in favorites
                        save(a,"favorites",if(remove) favorites-body else (favorites+body).takeLast(24))
                        Toast.makeText(a,if(remove) "Removed from favorites" else "Added to favorites",Toast.LENGTH_SHORT).show()
                        if(category==2) show(2)
                        true
                    }
                }
                grid.addView(icon,GridLayout.LayoutParams().apply { width=dp(88); height=dp(88) })
            }
        }
        val tabIds=listOf(R.id.stickers_bundled,R.id.stickers_recent,R.id.stickers_favorites)
        listOf("Rabta","Recent","Favorites").forEachIndexed { i,label -> tabs.addView(RadioButton(a).apply { id=tabIds[i]; text=label; textSize=12f }) }
        tabs.setOnCheckedChangeListener { _,id -> show(tabIds.indexOf(id).coerceAtLeast(0)) }; col.addView(tabs)
        scroll.addView(grid); col.addView(scroll); tabs.check(R.id.stickers_bundled)
        dialog=AlertDialog.Builder(a).setTitle("Stickers").setView(col).setNeutralButton("Import sticker") { _,_ -> import() }.setNegativeButton("Close",null).create()
        dialog.show()
    }
}
