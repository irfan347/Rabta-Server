package com.irfan.rabta

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Emoji in the keyboard's place for one text field, like the chat composer: [button] swaps between the emoji panel
 * and the keyboard. The panel is added at the end of [host], a vertical layout at the bottom of the screen.
 */
class EmojiField(private val a: Activity, private val field: EditText, private val host: LinearLayout, private val button: ImageView, private val tint: Int) {
    private var panel: EmojiPanel? = null
    val open get() = panel?.visibility == View.VISIBLE

    init { button.isFocusable = true; button.setOnClickListener { if (open) { hide(); keyboard() } else show() }; icon() }

    private fun height() = a.getSharedPreferences("appearance", 0).getInt("keyboard_height", 0).takeIf { it > RabtaUi.dp(a, 180) } ?: RabtaUi.dp(a, 300)

    private fun show() {
        val p = panel ?: EmojiPanel(a,
            insert = { e -> field.text.insert(field.selectionStart.coerceAtLeast(0), e) },
            backspace = { field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)); field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)) },
            sendSticker = {}, importSticker = {},
            searchChanged = { searching -> panel?.layoutParams = LinearLayout.LayoutParams(-1, if (searching) -2 else height()) },
            emojiOnly = true,
        ).also { panel = it; host.addView(it, LinearLayout.LayoutParams(-1, height())) }
        a.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
        p.layoutParams = LinearLayout.LayoutParams(-1, height()); p.visibility = View.VISIBLE; icon()
    }

    fun hide() { panel?.let { it.stopSearch(); it.visibility = View.GONE }; icon() }

    private fun keyboard() { field.requestFocus(); WindowCompat.getInsetsController(a.window, field).show(WindowInsetsCompat.Type.ime()) }

    private fun icon() {
        button.setImageDrawable(UiIcons.drawable(a, if (open) "keyboard" else "emoji", tint, 24))
        button.contentDescription = if (open) "Keyboard" else "Emoji"
    }

    /** For onKeyboardVisibilityChanged: the keyboard coming up closes the panel, unless the panel's own search is typing. */
    fun keyboardShown() { if (open && panel?.searching != true) hide() }
}
