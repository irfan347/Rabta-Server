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
    fun bg(c: Context) = if(dark(c)) 0xFF10131F.toInt() else 0xFFF5F6FC.toInt()
    fun surface(c: Context) = if(dark(c)) 0xFF1B2032.toInt() else Color.WHITE
    fun text(c: Context) = if(dark(c)) 0xFFF2F3FF.toInt() else 0xFF20243A.toInt()
    fun sub(c: Context) = if(dark(c)) 0xFFA4AEC5.toInt() else 0xFF68738B.toInt()
    fun line(c: Context) = if(dark(c)) 0xFF2B3249.toInt() else 0xFFE6E9F3.toInt()
    fun sent(c: Context) = if(dark(c)) 0xFF38335C.toInt() else 0xFFE8E3FF.toInt()
    const val ACCENT = 0xFF7357DD.toInt()
    fun choose(a: Activity) {
        val modes=arrayOf("System","Light","Dark")
        AlertDialog.Builder(a).setTitle("Appearance").setSingleChoiceItems(modes,modes.indexOf(mode(a))) { dialog,index ->
            a.getSharedPreferences("appearance",0).edit().putString("mode",modes[index]).apply()
            dialog.dismiss(); a.recreate()
        }.setNegativeButton("Cancel",null).show()
    }
}

open class ThemedActivity : Activity() {
    private var appliedDark=false
    override fun onCreate(state: Bundle?) {
        appliedDark=AppTheme.dark(this)
        setTheme(if(appliedDark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(state)
        window.statusBarColor=AppTheme.bg(this); window.navigationBarColor=AppTheme.bg(this)
        window.decorView.systemUiVisibility=if(appliedDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    override fun onResume() { super.onResume(); if(appliedDark!=AppTheme.dark(this)) recreate() }
}
