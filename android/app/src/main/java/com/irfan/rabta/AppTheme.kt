package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View

object AppTheme {
    fun mode(c: Context) = c.getSharedPreferences("appearance",0).getString("mode","Dark") ?: "Dark"
    fun dark(c: Context) = when(mode(c)) { "Dark" -> true; "Light" -> false; else -> c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
    // Rabta NOVA: near-black navy surfaces, NOVA Lime as the signature accent, Orbit Violet and Signal Teal as supports.
    fun bg(c: Context) = if(dark(c)) 0xFF080D14.toInt() else 0xFFF5F7F4.toInt()
    fun surface(c: Context) = if(dark(c)) 0xFF111B27.toInt() else Color.WHITE
    fun raised(c: Context) = if(dark(c)) 0xFF172230.toInt() else 0xFFECF0EC.toInt()
    fun text(c: Context) = if(dark(c)) 0xFFF3F6F5.toInt() else 0xFF101820.toInt()
    fun sub(c: Context) = if(dark(c)) 0xFF94A5B5.toInt() else 0xFF4F5E6B.toInt()
    fun line(c: Context) = if(dark(c)) 0xFF223040.toInt() else 0xFFDCE3DD.toInt()
    fun sent(c: Context) = if(dark(c)) 0xFF1E2A1E.toInt() else 0xFFE6F5D6.toInt()
    fun accent(c: Context) = if(dark(c)) LIME else ACCENT
    fun teal(c: Context) = if(dark(c)) 0xFF54DDC5.toInt() else 0xFF0A6B5F.toInt()
    fun violet(c: Context) = if(dark(c)) 0xFFA997FF.toInt() else 0xFF5B45C9.toInt()
    fun error(c: Context) = if(dark(c)) 0xFFFFA6B0.toInt() else 0xFFB4233B.toInt()
    const val LIME = 0xFFBFFF76.toInt()
    const val PRIMARY = LIME
    /** Lime buttons in both modes, always with dark labels. */
    fun primary(c: Context) = PRIMARY
    fun onPrimary(c: Context) = 0xFF080D14.toInt()
    // Deep NOVA green: the light-mode accent, and a fill that keeps white labels at AA contrast.
    const val ACCENT = 0xFF3A6E12.toInt()
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
