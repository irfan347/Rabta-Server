package com.irfan.rabta

import android.graphics.Color
import android.graphics.Typeface
import org.json.JSONObject

/**
 * How a text update looks: the background colour and font chosen in [TextStatusActivity] and sent with it.
 * Updates from older apps carry neither and keep the colour their ID gives them.
 */
object StatusLook {
    val COLOURS = longArrayOf(0xFFA62C6E, 0xFF7E57C2, 0xFF00897B, 0xFFE53935, 0xFF3949AB, 0xFFF4511E, 0xFF5D4037, 0xFF43A047,
        0xFF8E24AA, 0xFF1E88E5, 0xFF1E3A2F, 0xFF2B2459, 0xFF5B2C3A, 0xFF2C3E57).map { it.toInt() }
    /** Must match the names features.js accepts. */
    val FONTS = listOf("sans", "serif", "mono", "script", "bold")

    fun typeface(font: String?): Typeface = when (font) {
        "serif" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        "mono" -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        "script" -> Typeface.create("cursive", Typeface.BOLD)
        "bold" -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        else -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun hex(colour: Int) = String.format("#%06X", colour and 0xFFFFFF)

    /** The colour chosen for the update, or for older updates the steady colour its ID gives. */
    fun background(status: JSONObject): Int =
        status.optString("bg").takeIf { Regex("#[0-9A-Fa-f]{6}").matches(it) }?.let { Color.parseColor(it) } ?: StatusViewerActivity.textColor(status.optString("id"))
}
