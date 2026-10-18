package com.irfan.rabta

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * WhatsApp-style text update: big centred words on a colour, with the colour (palette) and font (Aa) to choose,
 * emoji, and who can see it. Returns the update to StatusActivity, which shares it.
 */
class TextStatusActivity : ThemedActivity() {
    private lateinit var root: LinearLayout
    private lateinit var field: EditText
    private lateinit var audiencePill: TextView
    private lateinit var emoji: EmojiField
    private var colour = 0
    private var font = 0
    private var audience: List<String> = emptyList()
    private fun dp(n: Int) = RabtaUi.dp(this, n)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        RabtaUi.systemBars(this, true)
        colour = state?.getInt("colour") ?: StatusLook.COLOURS.indices.random()
        font = state?.getInt("font") ?: 0
        audience = StatusPost.audience(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Top: close · font · colour.
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(4)) }
        top.addView(round(UiIcons.image(this, "close", Color.WHITE, 22, "Discard")) { finish() })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(round(RabtaUi.text(this, "Aa", 17f, true, Color.WHITE).apply { gravity = Gravity.CENTER; includeFontPadding = false; contentDescription = "Change font" }) {
            font = (font + 1) % StatusLook.FONTS.size; look()
        })
        top.addView(round(UiIcons.image(this, "palette", Color.WHITE, 22, "Change colour")) { colour = (colour + 1) % StatusLook.COLOURS.size; look() })
        root.addView(top)

        field = EditText(this).apply {
            hint = "Type a status"; gravity = Gravity.CENTER; background = null; setTextColor(Color.WHITE); setHintTextColor(0x80FFFFFF.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(700)); setPadding(dp(24), dp(16), dp(24), dp(16)); contentDescription = "Status text"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { size() }
            })
        }
        root.addView(field, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bottom: emoji · who can see · send.
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(12), dp(12)) }
        val emojiButton = UiIcons.image(this, "emoji", Color.WHITE, 24, "Emoji").apply { background = RabtaUi.ripple(this@TextStatusActivity, 0x33000000, 999) }
        bottom.addView(emojiButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        audiencePill = RabtaUi.text(this, "", 14f, true, Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL; minHeight = dp(44); setPadding(dp(14), 0, dp(16), 0); isFocusable = true; compoundDrawablePadding = dp(8)
            setCompoundDrawablesRelative(UiIcons.drawable(this@TextStatusActivity, "people", Color.WHITE, 18), null, null, null)
            background = RabtaUi.ripple(this@TextStatusActivity, 0x33000000, 999)
            setOnClickListener { StatusPost.chooseAudience(this@TextStatusActivity, audience) { audience = it; showAudience() } }
        }
        bottom.addView(audiencePill, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(8) })
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(UiIcons.image(this, "send", AppTheme.onPrimary(this), 26, "Send status").apply {
            isFocusable = true; background = RabtaUi.ripple(this@TextStatusActivity, AppTheme.primary(this@TextStatusActivity), 999); setOnClickListener { send() }
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        root.addView(bottom)
        emoji = EmojiField(this, field, root, emojiButton, Color.WHITE)
        setContentView(root)
        look(); showAudience()
        field.requestFocus(); field.post { WindowCompat.getInsetsController(window, field).show(WindowInsetsCompat.Type.ime()) }
    }

    private fun <T : View> round(view: T, run: () -> Unit) = view.apply {
        isFocusable = true; background = RabtaUi.ripple(this@TextStatusActivity, 0x33000000, 999); setOnClickListener { run() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) }
    }

    private fun look() { root.setBackgroundColor(StatusLook.COLOURS[colour]); field.typeface = StatusLook.typeface(StatusLook.FONTS[font]); size() }
    /** Short updates are big, long ones smaller, so they still fit the screen. */
    private fun size() { field.textSize = when (field.text.length) { in 0..39 -> 34f; in 40..119 -> 28f; in 120..299 -> 22f; else -> 18f } }
    private fun showAudience() { audiencePill.text = StatusPost.audienceLabel(audience); audiencePill.contentDescription = "${audiencePill.text}. Change who can see" }

    private fun send() {
        val text = field.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show(); return }
        if (audience.isEmpty()) { StatusPost.chooseAudience(this, audience) { audience = it; showAudience(); if (it.isNotEmpty()) send() }; return }
        setResult(RESULT_OK, Intent().putExtra("kind", "text").putExtra("body", text).putExtra("caption", "").putExtra("audience", audience.toTypedArray())
            .putExtra("bg", StatusLook.hex(StatusLook.COLOURS[colour])).putExtra("font", StatusLook.FONTS[font]))
        finish()
    }

    override fun onSaveInstanceState(out: Bundle) { out.putInt("colour", colour); out.putInt("font", font); super.onSaveInstanceState(out) }
    override fun onKeyboardVisibilityChanged(visible: Boolean) { if (visible && ::emoji.isInitialized) emoji.keyboardShown() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (::emoji.isInitialized && emoji.open) emoji.hide() else super.onBackPressed() }
}
