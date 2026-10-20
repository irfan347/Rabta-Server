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

    /** One mode's surfaces; text, secondary text, teal and error stay shared so every theme keeps AA contrast. */
    class Tokens(val bg: Int, val surface: Int, val raised: Int, val line: Int, val sent: Int, val accent: Int)
    class Palette(val key: String, val name: String, val dark: Tokens, val light: Tokens, val primary: Int, val onPrimary: Int)
    /** Custom accents: a bright colour for dark mode and a deeper one that reads on light surfaces. */
    class Accent(val key: String, val name: String, val dark: Int, val light: Int)
    private fun argb(v: Long) = v.toInt()
    // Rabta NOVA: near-black navy surfaces, NOVA Lime as the signature accent, Orbit Violet and Signal Teal as supports.
    val NOVA = Palette("nova", "NOVA",
        Tokens(argb(0xFF080D14), argb(0xFF111B27), argb(0xFF172230), argb(0xFF223040), argb(0xFF1E2A1E), argb(0xFFBFFF76)),
        Tokens(argb(0xFFF5F7F4), Color.WHITE, argb(0xFFECF0EC), argb(0xFFDCE3DD), argb(0xFFE6F5D6), argb(0xFF3A6E12)), argb(0xFFBFFF76), argb(0xFF080D14))
    val palettes = listOf(NOVA,
        Palette("ocean", "Ocean",
            Tokens(argb(0xFF061018), argb(0xFF0C1A26), argb(0xFF122434), argb(0xFF1C3346), argb(0xFF0F2E3D), argb(0xFF5FD3F3)),
            Tokens(argb(0xFFF2F7FA), Color.WHITE, argb(0xFFE6EFF5), argb(0xFFD2E1EA), argb(0xFFDDF1F8), argb(0xFF0A6A86)), argb(0xFF5FD3F3), argb(0xFF061018)),
        Palette("royal", "Royal",
            Tokens(argb(0xFF0C0A15), argb(0xFF161229), argb(0xFF1F1937), argb(0xFF2D2548), argb(0xFF2A2148), argb(0xFFC2A8FF)),
            Tokens(argb(0xFFF6F4FB), Color.WHITE, argb(0xFFEEE9F8), argb(0xFFDED6F0), argb(0xFFEAE2FC), argb(0xFF5B3FC4)), argb(0xFFC2A8FF), argb(0xFF120C22)),
        Palette("emerald", "Emerald",
            Tokens(argb(0xFF0B1411), argb(0xFF111E1A), argb(0xFF172A24), argb(0xFF21392F), argb(0xFF0E3B30), argb(0xFF3DDC97)),
            Tokens(argb(0xFFF1F4F1), Color.WHITE, argb(0xFFE7EEEA), argb(0xFFD5E1DA), argb(0xFFDCF5E3), argb(0xFF0B6E4F)), argb(0xFF3DDC97), argb(0xFF05261A)))
    val accents = listOf(
        Accent("lime", "Lime", argb(0xFFBFFF76), argb(0xFF3A6E12)), Accent("sky", "Sky", argb(0xFF6CC4FF), argb(0xFF0B6AA8)),
        Accent("coral", "Coral", argb(0xFFFF8A80), argb(0xFFB23A2E)), Accent("amber", "Amber", argb(0xFFFFC857), argb(0xFF8A5A00)),
        Accent("rose", "Rose", argb(0xFFFF8FC8), argb(0xFFA8326E)), Accent("mint", "Mint", argb(0xFF5EEAD4), argb(0xFF0C6B5E)),
        Accent("lavender", "Lavender", argb(0xFFB9A6FF), argb(0xFF5A42C2)), Accent("orange", "Orange", argb(0xFFFFA65C), argb(0xFF9C4A00)))
    private fun blend(a: Int, b: Int, t: Float) = Color.rgb((Color.red(a) * t + Color.red(b) * (1 - t)).toInt(), (Color.green(a) * t + Color.green(b) * (1 - t)).toInt(), (Color.blue(a) * t + Color.blue(b) * (1 - t)).toInt())
    /** Graphite surfaces with the chosen accent; bubbles carry a hint of it. */
    fun custom(a: Accent) = Palette("custom", "Custom",
        Tokens(argb(0xFF0E0F12), argb(0xFF17191D), argb(0xFF202328), argb(0xFF2C3036), blend(a.dark, argb(0xFF17191D), 0.13f), a.dark),
        Tokens(argb(0xFFF5F5F7), Color.WHITE, argb(0xFFECEDF0), argb(0xFFDADCE0), blend(a.light, Color.WHITE, 0.12f), a.light), a.dark, argb(0xFF0E0F12))

    fun themeKey(c: Context) = c.getSharedPreferences("appearance",0).getString("theme","nova") ?: "nova"
    fun accentKey(c: Context) = c.getSharedPreferences("appearance",0).getString("accent","lime") ?: "lime"
    @Volatile private var cached: Pair<String, Palette>? = null
    fun palette(c: Context): Palette {
        val key = themeKey(c) + ":" + accentKey(c)
        cached?.let { if (it.first == key) return it.second }
        val p = if (themeKey(c) == "custom") custom(accents.firstOrNull { it.key == accentKey(c) } ?: accents[0]) else palettes.firstOrNull { it.key == themeKey(c) } ?: NOVA
        cached = key to p; return p
    }
    fun setTheme(c: Context, key: String, accent: String? = null) {
        c.getSharedPreferences("appearance",0).edit().putString("theme", key).apply { if (accent != null) putString("accent", accent) }.apply()
    }
    /** Changes when anything about the look changes, so open screens redraw on return. */
    fun signature(c: Context) = mode(c) + ":" + themeKey(c) + ":" + accentKey(c)
    private fun tokens(c: Context) = palette(c).let { if (dark(c)) it.dark else it.light }

    fun bg(c: Context) = tokens(c).bg
    fun surface(c: Context) = tokens(c).surface
    fun raised(c: Context) = tokens(c).raised
    fun text(c: Context) = if(dark(c)) 0xFFF3F6F5.toInt() else 0xFF101820.toInt()
    fun sub(c: Context) = if(dark(c)) 0xFF94A5B5.toInt() else 0xFF4F5E6B.toInt()
    fun line(c: Context) = tokens(c).line
    fun sent(c: Context) = tokens(c).sent
    fun accent(c: Context) = tokens(c).accent
    fun teal(c: Context) = if(dark(c)) 0xFF54DDC5.toInt() else 0xFF0A6B5F.toInt()
    fun violet(c: Context) = if(dark(c)) 0xFFA997FF.toInt() else 0xFF5B45C9.toInt()
    fun error(c: Context) = if(dark(c)) 0xFFFFA6B0.toInt() else 0xFFB4233B.toInt()
    /** WhatsApp's blue read ticks. */
    fun readTick(c: Context) = if(dark(c)) 0xFF53BDEB.toInt() else 0xFF0B7FC4.toInt()
    /** NOVA Lime stays the brand colour (logo, splash) whatever theme is chosen. */
    const val LIME = 0xFFBFFF76.toInt()
    const val PRIMARY = LIME
    /** Buttons: the theme's bright colour in both modes, always with dark labels. */
    fun primary(c: Context) = palette(c).primary
    fun onPrimary(c: Context) = palette(c).onPrimary
    // Deep NOVA green: the light-mode accent, and a fill that keeps white labels at AA contrast.
    const val ACCENT = 0xFF3A6E12.toInt()
    /** The activity style matching the theme, so dialogs and system controls use its colours too. */
    fun style(c: Context): Int {
        val dark = dark(c)
        return when (themeKey(c)) {
            "ocean" -> if (dark) R.style.Theme_Rabta_Dark_Ocean else R.style.Theme_Rabta_Light_Ocean
            "royal" -> if (dark) R.style.Theme_Rabta_Dark_Royal else R.style.Theme_Rabta_Light_Royal
            "emerald" -> if (dark) R.style.Theme_Rabta_Dark_Emerald else R.style.Theme_Rabta_Light_Emerald
            "custom" -> if (dark) R.style.Theme_Rabta_Dark_Custom else R.style.Theme_Rabta_Light_Custom
            else -> if (dark) R.style.Theme_Rabta_Dark else R.style.Theme_Rabta_Light
        }
    }
    fun choose(a: Activity) { a.startActivity(android.content.Intent(a, ThemeActivity::class.java)) }
}

open class ThemedActivity : androidx.fragment.app.FragmentActivity() {
    private var appliedSettings=""
    private var unlocking=false
    override fun attachBaseContext(base:Context) { super.attachBaseContext(L.context(base)) }
    /**
     * What back does on this screen; [handled] false closes it as usual. Android 16 no longer calls onBackPressed in
     * apps that target it, so a screen says it here.
     */
    protected fun onBack(handled:()->Boolean) {
        onBackPressedDispatcher.addCallback(this,object:androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(!handled()) { isEnabled=false;onBackPressedDispatcher.onBackPressed();isEnabled=true } }
        })
    }
    override fun onPause() { super.onPause();AppLock.left() }
    private var appliedDark=false
    override fun onCreate(state: Bundle?) {
        appliedSettings=L.signature(this)+AppTheme.signature(this); appliedDark=AppTheme.dark(this)
        setTheme(AppTheme.style(this))
        super.onCreate(state)
        Session.init(this)
        RabtaUi.edgeToEdge(this)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        if (view != null) RabtaUi.insets(view) { onKeyboardVisibilityChanged(it) }
    }
    protected open fun onKeyboardVisibilityChanged(visible: Boolean) {}
    override fun onResume() {
        super.onResume()
        if(appliedDark!=AppTheme.dark(this)||appliedSettings!=L.signature(this)+AppTheme.signature(this)) { recreate();return }
        // Back on screen: connected at once, not after the reconnect timer that stood still while the phone slept.
        if(Session.loggedIn) WsClient.wake(onScreen=true)
        if(Privacy.prefs(this).getBoolean("lock",false)) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        if(AppLock.needed(this)&&!unlocking) {
            unlocking=true;window.decorView.alpha=0f
            AppLock.authenticate(this) { unlocking=false;window.decorView.alpha=1f }
        }
    }
}
