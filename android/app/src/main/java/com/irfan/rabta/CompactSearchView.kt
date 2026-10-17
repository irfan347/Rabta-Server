package com.irfan.rabta

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat

/** Filters supplied records only; it never initiates discovery or a network query. */
class CompactSearchView(c:Context, label:String, changed:(String)->Unit):LinearLayout(c) {
    val field=EditText(c)
    val clear=RabtaUi.iconButton(c,"close","Clear nearby search") {field.setText("")}
    init {
        gravity=Gravity.CENTER_VERTICAL;minimumHeight=RabtaUi.dp(c,48)
        setPadding(RabtaUi.dp(c,12),0,0,0)
        background=RabtaUi.shape(c,AppTheme.surface(c),16)
        addView(TextView(c).apply {
            UiIcons.set(this,"search",AppTheme.sub(c),20,label);gravity=Gravity.CENTER
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LayoutParams(RabtaUi.dp(c,24),RabtaUi.dp(c,24)))
        field.apply {
            hint=label;contentDescription=label;textSize=14f;setSingleLine(true)
            setTextColor(AppTheme.text(c));setHintTextColor(AppTheme.sub(c));background=null
            minimumWidth=0;minimumHeight=RabtaUi.dp(c,48)
            setPadding(RabtaUi.dp(c,8),RabtaUi.dp(c,8),0,RabtaUi.dp(c,8))
            imeOptions=EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener {_,action,_->
                if(action==EditorInfo.IME_ACTION_SEARCH){clearFocus();
                    (c as? android.app.Activity)?.let {WindowCompat.getInsetsController(it.window,it.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())};true
                }else false
            }
        }
        addView(field,LayoutParams(0,-2,1f))
        clear.visibility=View.GONE;addView(clear,LayoutParams(RabtaUi.dp(c,48),RabtaUi.dp(c,48)))
        field.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun afterTextChanged(s:Editable?) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                clear.visibility=if(s.isNullOrEmpty())View.GONE else View.VISIBLE;changed(s?.toString().orEmpty())
            }
        })
    }
}
