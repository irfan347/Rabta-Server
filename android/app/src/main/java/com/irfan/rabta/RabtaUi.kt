package com.irfan.rabta

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Native Views only. Tokens are dp/sp; colours follow the saved System/Light/Dark mode. */
object RabtaUi {
    const val SPACE = 8
    const val GUTTER = 20
    const val CORNER = 20
    const val TOUCH = 48
    fun dp(c: Context, value: Int) = (value * c.resources.displayMetrics.density).toInt()

    fun shape(c: Context, color: Int, radius: Int = CORNER, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radius).toFloat()
        if(stroke != null) setStroke(dp(c, 1), stroke)
    }

    fun ripple(c: Context, color: Int, radius: Int = 16) = RippleDrawable(
        ColorStateList.valueOf(if(AppTheme.dark(c)) 0x33BFFF76 else 0x223A6E12),
        shape(c,color,radius), shape(c,Color.WHITE,radius))

    fun text(c: Context, value: String, size: Float = 15f, strong: Boolean = false, color: Int = AppTheme.text(c)) = TextView(c).apply {
        text = L.t(c,value); textSize = size; setTextColor(color)
        typeface = Typeface.create(if(strong) "sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        setLineSpacing(dp(c,2).toFloat(),1.05f)
    }

    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(c,AppTheme.surface(c),CORNER)
        setPadding(dp(c,16),dp(c,16),dp(c,16),dp(c,16))
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(c,8) }
    }

    fun button(c: Context, label: String, primary: Boolean = false, action: () -> Unit) = text(c,label,15f,true,
        if(primary) AppTheme.onPrimary(c) else AppTheme.text(c)).apply {
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(c,48); minimumWidth = dp(c,TOUCH)
        setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12))
        background = ripple(c,if(primary) AppTheme.primary(c) else AppTheme.surface(c),16)
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(c,8) }
        isFocusable = true
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info); info.className = "android.widget.Button"
            }
        }
        setOnClickListener { action() }
    }

    fun iconButton(c: Context, icon: String, label: String, action: () -> Unit) = TextView(c).apply {
        UiIcons.set(this,icon,AppTheme.accent(c),label=L.t(c,label))
        gravity = Gravity.CENTER; minWidth = dp(c,TOUCH); minHeight = dp(c,TOUCH)
        isFocusable = true; background = ripple(c,Color.TRANSPARENT,16)
        layoutParams = LinearLayout.LayoutParams(dp(c,TOUCH),dp(c,TOUCH))
        setOnClickListener { action() }
    }

    fun empty(c: Context, icon: String, title: String, body: String, actionLabel: String? = null, action: (() -> Unit)? = null) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(c,16),dp(c,24),dp(c,16),dp(c,24))
        addView(TextView(c).apply {
            UiIcons.set(this,icon,AppTheme.accent(c),24,title)
            gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = shape(c,AppTheme.sent(c),16)
        },LinearLayout.LayoutParams(dp(c,48),dp(c,48)).apply { bottomMargin=dp(c,16) })
        addView(text(c,title,18f,true).apply { gravity=Gravity.CENTER })
        addView(text(c,body,14f,color=AppTheme.sub(c)).apply {
            gravity=Gravity.CENTER; setPadding(0,dp(c,8),0,dp(c,16))
        })
        if(actionLabel != null && action != null) addView(button(c,actionLabel,true,action).apply { gravity=Gravity.CENTER })
    }

    fun loading(c: Context, label: String) = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(c,20),dp(c,24),dp(c,20),dp(c,24))
        addView(ProgressBar(c).apply {
            indeterminateTintList=ColorStateList.valueOf(AppTheme.accent(c)); contentDescription=L.t(c,label)
        },LinearLayout.LayoutParams(dp(c,24),dp(c,24)).apply { marginEnd=dp(c,12) })
        addView(text(c,label,14f,color=AppTheme.sub(c)))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun enter(view: View) {
        if(!ValueAnimator.areAnimatorsEnabled()) return
        view.animate().cancel(); view.alpha=0f
        view.animate().alpha(1f).setDuration(140).start()
    }

    data class SheetAction(val label: String, val detail: String? = null, val run: () -> Unit)

    /** Platform Dialog avoids a second UI framework and retains native accessibility/focus. */
    fun sheet(a: Activity, title: String, actions: List<SheetAction>) {
        val dialog = Dialog(a)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(a).apply {
            orientation=LinearLayout.VERTICAL
            background=shape(a,AppTheme.surface(a),28).apply {
                val r=dp(a,28).toFloat(); cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)
            }
            setPadding(dp(a,20),dp(a,12),dp(a,20),dp(a,12))
        }
        root.addView(View(a).apply { background=shape(a,AppTheme.line(a),2) },
            LinearLayout.LayoutParams(dp(a,36),dp(a,4)).apply { gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(a,20) })
        root.addView(text(a,title,20f,true).apply { setPadding(0,0,0,dp(a,20));isAccessibilityHeadingCompat() })
        val items=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        actions.forEach { item ->
            val row=button(a,item.label) { dialog.dismiss();item.run() }
            item.detail?.let { detail ->
                val heading=L.t(a,item.label)
                row.text=android.text.SpannableString(heading+"\n"+L.t(a,detail)).apply {
                    val start=heading.length+1
                    setSpan(android.text.style.ForegroundColorSpan(AppTheme.sub(a)),start,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.AbsoluteSizeSpan(12,true),start,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.TypefaceSpan("sans-serif"),start,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            items.addView(row)
        }
        val scroll=ScrollView(a).apply { isFillViewport=false;addView(items);isVerticalScrollBarEnabled=false }
        root.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.5f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(this,false)
        }
        dialog.show()
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM);setLayout(-1,-2)
            navigationBarColor=AppTheme.surface(a)
            WindowInsetsControllerCompat(this,decorView).isAppearanceLightNavigationBars=!AppTheme.dark(a)
            decorView.post {
                val limit=(a.resources.displayMetrics.heightPixels*0.72f).toInt()
                if(root.height>limit) setLayout(-1,limit)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, wi ->
            val bars=wi.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(a,20)+bars.left,dp(a,12),dp(a,20)+bars.right,dp(a,12)+bars.bottom)
            wi
        }
        ViewCompat.requestApplyInsets(root)
        enter(root)
    }

    fun inputSheet(a:Activity,title:String,hint:String,submitLabel:String,onSubmit:(String,TextView,Dialog)->Unit) {
        val dialog=Dialog(a)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root=LinearLayout(a).apply {
            orientation=LinearLayout.VERTICAL
            background=shape(a,AppTheme.surface(a),28).apply {
                val r=dp(a,28).toFloat();cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)
            }
            setPadding(dp(a,20),dp(a,12),dp(a,20),dp(a,12))
        }
        root.addView(View(a).apply { background=shape(a,AppTheme.line(a),2) },LinearLayout.LayoutParams(dp(a,36),dp(a,4)).apply {
            gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(a,20)
        })
        root.addView(text(a,title,20f,true).apply { setPadding(0,0,0,dp(a,8));isAccessibilityHeadingCompat() })
        root.addView(text(a,"Find people by their username or Rabta ID.",14f,color=AppTheme.sub(a)).apply { setPadding(0,0,0,dp(a,16)) })
        val field=EditText(a).apply {
            this.hint=hint;textSize=16f;setTextColor(AppTheme.text(a));setHintTextColor(AppTheme.sub(a));setSingleLine(true)
            inputType=android.text.InputType.TYPE_CLASS_TEXT
            background=shape(a,AppTheme.bg(a),16,AppTheme.line(a));setPadding(dp(a,16),dp(a,14),dp(a,16),dp(a,14))
            minHeight=dp(a,52);contentDescription=hint
        }
        root.addView(field,LinearLayout.LayoutParams(-1,-2))
        val status=text(a,"",14f,color=AppTheme.sub(a)).apply { minHeight=dp(a,32);gravity=Gravity.CENTER_VERTICAL;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        val actions=LinearLayout(a).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.END or Gravity.CENTER_VERTICAL }
        fun action(label:String,primary:Boolean,run:()->Unit)=text(a,label,15f,true,if(primary) AppTheme.onPrimary(a) else AppTheme.text(a)).apply {
            gravity=Gravity.CENTER;minHeight=dp(a,48);minWidth=dp(a,TOUCH);setPadding(dp(a,16),dp(a,10),dp(a,16),dp(a,10))
            background=ripple(a,if(primary) AppTheme.primary(a) else Color.TRANSPARENT,16);isFocusable=true;setOnClickListener { run() }
        }
        actions.addView(action("Cancel",false) { dialog.dismiss() })
        actions.addView(action(submitLabel,true) { onSubmit(field.text.toString().trim(),status,dialog) })
        root.addView(actions,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(a,8) })
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent);addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.5f);setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(this,false)
        }
        dialog.show()
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM);setLayout(-1,-2);navigationBarColor=AppTheme.surface(a)
            WindowInsetsControllerCompat(this,decorView).isAppearanceLightNavigationBars=!AppTheme.dark(a)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v,wi ->
            val bars=wi.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(a,20)+bars.left,dp(a,12),dp(a,20)+bars.right,dp(a,12)+bars.bottom);wi
        }
        ViewCompat.requestApplyInsets(root);enter(root)
    }

    fun edgeToEdge(a: Activity) {
        WindowCompat.setDecorFitsSystemWindows(a.window,false)
        a.window.statusBarColor=Color.TRANSPARENT; a.window.navigationBarColor=Color.TRANSPARENT
        a.window.setBackgroundDrawable(shape(a,AppTheme.bg(a),0))
        if(Build.VERSION.SDK_INT>=29) { a.window.isStatusBarContrastEnforced=false; a.window.isNavigationBarContrastEnforced=false }
        systemBars(a,AppTheme.dark(a))
    }

    fun systemBars(a:Activity,dark:Boolean) {
        a.window.statusBarColor=Color.TRANSPARENT;a.window.navigationBarColor=Color.TRANSPARENT
        WindowInsetsControllerCompat(a.window,a.window.decorView).apply {
            isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
        }
    }

    fun insets(root: View, imeChanged: (Boolean) -> Unit = {}) {
        val left=root.paddingLeft;val top=root.paddingTop;val right=root.paddingRight;val bottom=root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, wi ->
            val bars=wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime=wi.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(left+bars.left,top+bars.top,right+bars.right,bottom+maxOf(bars.bottom,ime.bottom))
            imeChanged(wi.isVisible(WindowInsetsCompat.Type.ime()))
            wi
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun section(c:Context, title:String) = text(c,title,13f,true,AppTheme.sub(c)).apply {
        setPadding(0,dp(c,16),0,dp(c,8));ViewCompat.setAccessibilityHeading(this,true)
    }

    /** Compact real actions, with a separately styled supporting line and one 48dp touch target. */
    fun row(c:Context,title:String,detail:String?=null,icon:String?=null,action:()->Unit):LinearLayout = LinearLayout(c).apply {
        gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(c,56)
        setPadding(dp(c,16),dp(c,12),dp(c,12),dp(c,12))
        background=ripple(c,AppTheme.surface(c),16);isFocusable=true
        setOnClickListener { action() }
        if(icon!=null)addView(TextView(c).apply {
            UiIcons.set(this,icon,AppTheme.accent(c),20,title);gravity=Gravity.CENTER
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(c,24),dp(c,24)).apply {marginEnd=dp(c,12)})
        val labels=LinearLayout(c).apply {orientation=LinearLayout.VERTICAL}
        labels.addView(text(c,title,15f,true).apply {setOnClickListener {action()}})
        if(!detail.isNullOrBlank())labels.addView(text(c,detail,12f,color=AppTheme.sub(c)).apply {setPadding(0,dp(c,4),0,0)})
        addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        addView(text(c,"›",20f,color=AppTheme.sub(c)).apply {importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO})
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(c,8)}
    }

    private fun TextView.isAccessibilityHeadingCompat() { ViewCompat.setAccessibilityHeading(this,true) }
}
