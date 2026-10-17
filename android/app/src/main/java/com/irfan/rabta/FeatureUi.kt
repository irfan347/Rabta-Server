package com.irfan.rabta

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*

object FeatureUi {
    fun dp(a:android.content.Context,n:Int)=(n*a.resources.displayMetrics.density).toInt()
    fun page(a:Activity,title:String):LinearLayout {
        val root=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;isFocusableInTouchMode=true;setBackgroundColor(AppTheme.bg(a)) }
        val head=LinearLayout(a).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(a,8),dp(a,8),dp(a,16),dp(a,8)) }
        head.addView(RabtaUi.iconButton(a,"back","Back") { a.finish() })
        head.addView(RabtaUi.text(a,title,24f,true).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this,true) })
        root.addView(head);val scroll=ScrollView(a);val col=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(a,20),0,dp(a,20),dp(a,24)) };scroll.addView(col);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));a.setContentView(root);return col
    }
    fun text(a:Activity,value:String)=RabtaUi.text(a,value,14f,color=AppTheme.sub(a)).apply { setPadding(dp(a,4),dp(a,10),dp(a,4),dp(a,16)) }
    fun button(a:Activity,label:String,action:()->Unit)=RabtaUi.button(a,label,action=action)
}
