package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View

object AppTheme {
    fun mode(c: Context) = c.getSharedPreferences("appearance",0).getString("mode","System") ?: "System"
    fun dark(c: Context) = when(mode(c)) { "Dark" -> true; "Light" -> false; else -> c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
    fun bg(c: Context) = if(dark(c)) 0xFF0B0F19.toInt() else 0xFFF6F7FB.toInt()
    fun surface(c: Context) = if(dark(c)) 0xFF151B29.toInt() else Color.WHITE
    fun raised(c: Context) = if(dark(c)) 0xFF1D2636.toInt() else 0xFFEEF0F7.toInt()
    fun text(c: Context) = if(dark(c)) 0xFFF3F4FA.toInt() else 0xFF171C2A.toInt()
    fun sub(c: Context) = if(dark(c)) 0xFFAAB5CC.toInt() else 0xFF596579.toInt()
    fun line(c: Context) = if(dark(c)) 0xFF2B3549.toInt() else 0xFFDDE2ED.toInt()
    fun sent(c: Context) = if(dark(c)) 0xFF30264D.toInt() else 0xFFECE5FC.toInt()
    fun accent(c: Context) = if(dark(c)) 0xFFB5A0FF.toInt() else ACCENT
    fun teal(c: Context) = if(dark(c)) 0xFF43D7C5.toInt() else 0xFF096F65.toInt()
    fun error(c: Context) = if(dark(c)) 0xFFFFA6B0.toInt() else 0xFFB4233B.toInt()
    // Deeper than the logo violet so white button labels retain AA contrast.
    const val ACCENT = 0xFF6D46D9.toInt()
    fun choose(a: Activity) {
        val modes=arrayOf("System","Light","Dark")
        RabtaUi.sheet(a,"Appearance",modes.map { value ->
            RabtaUi.SheetAction(value, if(value==mode(a)) "Selected" else null) {
                a.getSharedPreferences("appearance",0).edit().putString("mode",value).apply()
                a.recreate()
            }
        })
    }
}

open class ThemedActivity : androidx.fragment.app.FragmentActivity() {
    private var appliedSettings=""
    private var unlocking=false
    override fun attachBaseContext(base:Context) { super.attachBaseContext(L.context(base)) }
    override fun onPause() { super.onPause();AppLock.left() }
    private var appliedDark=false
    override fun onCreate(state: Bundle?) {
        appliedSettings=L.signature(this); appliedDark=AppTheme.dark(this)
        setTheme(if(appliedDark) R.style.Theme_Rabta_Dark else R.style.Theme_Rabta_Light)
        super.onCreate(state)
        Session.init(this)
        RabtaUi.edgeToEdge(this)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        RabtaUi.insets(view) { onKeyboardVisibilityChanged(it) }
    }
    protected open fun onKeyboardVisibilityChanged(visible: Boolean) {}
    override fun onResume() {
        super.onResume()
        if(appliedDark!=AppTheme.dark(this)||appliedSettings!=L.signature(this)) { recreate();return }
        if(Privacy.prefs(this).getBoolean("lock",false)) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        if(AppLock.needed(this)&&!unlocking) {
            unlocking=true;window.decorView.alpha=0f
            AppLock.authenticate(this) { unlocking=false;window.decorView.alpha=1f }
        }
    }
}
