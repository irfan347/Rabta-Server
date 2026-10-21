package com.irfan.rabta

import android.content.Context
import android.app.AlertDialog

/** Local presentation preferences; developer mode never changes server permissions. */
object Experience {
    val modes = arrayOf("Easy", "Standard", "Developer")
    val descriptions = arrayOf("Larger text and a simpler message composer", "All everyday chat features", "Extra appearance, animation and connection controls")
    fun mode(c: Context) = L.prefs(c).getString("experience", "Standard") ?: "Standard"
    fun easy(c: Context) = mode(c) == "Easy"
    fun choose(a: ThemedActivity) {
        AlertDialog.Builder(a).setTitle("Choose your experience")
            .setSingleChoiceItems(modes.mapIndexed { i, m -> "$m — ${descriptions[i]}" }.toTypedArray(), modes.indexOf(mode(a))) { d, i ->
                set(a, modes[i]); d.dismiss(); a.recreate()
            }.setNegativeButton("Cancel", null).show()
    }
    fun set(c: Context, value: String) { if(value in modes) L.prefs(c).edit().putString("experience", value).apply() }
    fun animate(c: Context) = L.prefs(c).getBoolean("animate_emoji", true)
}
