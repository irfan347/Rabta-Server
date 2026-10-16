package com.irfan.rabta

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*

object FeatureUi {
    fun dp(a:android.content.Context,n:Int)=(n*a.resources.displayMetrics.density).toInt()
    fun page(a:Activity,title:String):LinearLayout {
        val root=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(AppTheme.bg(a)) }
        val head=LinearLayout(a).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(a,8),dp(a,12),dp(a,16),dp(a,12)) }
        head.addView(TextView(a).apply { text="‹";textSize=30f;setTextColor(AppTheme.text(a));gravity=Gravity.CENTER;setOnClickListener { a.finish() } },LinearLayout.LayoutParams(dp(a,48),dp(a,48)))
        head.addView(TextView(a).apply { text=L.t(a,title);textSize=24f;typeface=Typeface.DEFAULT_BOLD;setTextColor(AppTheme.text(a)) })
        root.addView(head);val scroll=ScrollView(a);val col=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(a,16),0,dp(a,16),dp(a,24)) };scroll.addView(col);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));a.setContentView(root);return col
    }
    fun text(a:Activity,value:String)=TextView(a).apply { text=value;setTextColor(AppTheme.sub(a));textSize=14f;setPadding(dp(a,4),dp(a,10),dp(a,4),dp(a,14)) }
    fun button(a:Activity,label:String,action:()->Unit)=TextView(a).apply {
        text=L.t(a,label);textSize=16f;setTextColor(AppTheme.text(a));minHeight=dp(a,52);gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(a,16),dp(a,14),dp(a,16),dp(a,14));background=GradientDrawable().apply { setColor(AppTheme.surface(a));cornerRadius=dp(a,18).toFloat() }
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(a,10) };setOnClickListener { action() }
    }
}
