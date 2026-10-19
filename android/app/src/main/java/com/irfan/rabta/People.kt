package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** People helpers shared by the chats list, Select contact, New group and Contact info. */
object People {
    private fun dp(c: Context, n: Int) = RabtaUi.dp(c, n)

    /** The chat with yourself ("Message yourself") is the chat whose ID is your own. */
    fun isSelf(peerId: String) = peerId.isNotEmpty() && peerId == Session.id
    fun selfName(c: Context) = "${Session.name.ifBlank { "You" }} (${L.t(c, "You")})"

    /** Initials in one of four calm colours; the same person always gets the same colour. */
    fun initials(c: Context, name: String, size: Int): TextView {
        val colours = listOf(0xFF4B3F8F, 0xFF325E71, 0xFF3D6B2A, 0xFF286F68)
        return TextView(c).apply {
            text = name.filter { it.isLetterOrDigit() }.take(1).uppercase().ifEmpty { "?" }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * 0.38f); includeFontPadding = false; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = RabtaUi.shape(c, colours[(name.hashCode() and 0x7FFFFFFF) % colours.size].toInt(), 999)
        }
    }

    /** Initials at once, the real profile photo when it arrives; yours from this phone. [size] is in pixels. */
    fun avatar(a: Activity, peerId: String, name: String, size: Int): FrameLayout {
        val frame = FrameLayout(a).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        frame.addView(initials(a, name, size), FrameLayout.LayoutParams(size, size))
        fun show(bmp: android.graphics.Bitmap) = frame.addView(ImageView(a).apply {
            setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = RabtaUi.shape(a, AppTheme.raised(a), 999); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(size, size))
        if (isSelf(peerId)) Session.profilePic.takeIf { it.isNotEmpty() }?.let { MediaHelper.base64ToBitmap(it) }?.let { show(it) }
        else if (peerId.isNotBlank()) ProfileHelper.loadIfNeeded(peerId, Session.serverUrl, Session.token) { bmp ->
            if (bmp != null) frame.post { if (!a.isDestroyed && frame.childCount == 1) show(bmp) }
        }
        return frame
    }

    fun openChat(a: Activity, peerId: String, peerName: String, isGroup: Boolean = false) {
        a.startActivity(Intent(a, ChatActivity::class.java).putExtra("peerId", peerId).putExtra("peerName", peerName).putExtra("isGroup", isGroup))
    }

    /** A voice or video call goes through the chat, which owns the call screens. */
    fun call(a: Activity, peerId: String, peerName: String, video: Boolean) {
        if (Privacy.blocked(a, peerId)) { Toast.makeText(a, "Unblock this contact before calling", Toast.LENGTH_SHORT).show(); return }
        if (!WsClient.connected || WsClient.protocol < 2) { ConnectionHelp.show(a); return }
        a.startActivity(Intent(a, ChatActivity::class.java).putExtra("peerId", peerId).putExtra("peerName", peerName)
            .putExtra("requestedCallKind", if (video) "video" else "voice"))
    }

    /** Finds someone by @username or Rabta ID; found once, they are saved as a contact. */
    fun find(a: Activity, title: String, prefill: String = "", found: (id: String, name: String, username: String) -> Unit) {
        RabtaUi.inputSheet(a, title, "@username or Rabta ID", "Find") { raw, status, dlg ->
            lookup(a, raw.ifEmpty { prefill }, { status.text = it }, { status.setTextColor(it) }) { id, name, username -> dlg.dismiss(); found(id, name, username) }
        }
    }

    /** Looks [raw] up on the server (a 12-character Rabta ID needs no lookup) and saves the person as a contact. */
    fun lookup(a: Activity, raw: String, say: (String) -> Unit, tint: (Int) -> Unit, found: (id: String, name: String, username: String) -> Unit) {
        val query = raw.trim().removePrefix("@").lowercase()
        if (query.isEmpty()) { say(L.t(a, "Enter a username or Rabta ID")); tint(AppTheme.error(a)); return }
        fun save(id: String, name: String, username: String) {
            if (!isSelf(id)) Thread { runCatching { Db.get(a).saveContact(id, name, username) } }.start()
            found(id, name, username)
        }
        if (Regex("[0-9a-f]{12}").matches(query)) { save(query.uppercase(), query.uppercase(), ""); return }
        say(L.t(a, "Searching for account…")); tint(AppTheme.sub(a))
        WsClient.findUser(Session.serverUrl, query, Session.token) { ok, msg, data ->
            if (a.isDestroyed) return@findUser
            val id = data?.optString("id").orEmpty()
            when {
                ok && id.isNotEmpty() -> save(id, data!!.optString("name").ifEmpty { data.optString("username") }, data.optString("username"))
                ok -> { say(L.t(a, "No account was found")); tint(AppTheme.error(a)) }
                else -> { say(msg); tint(AppTheme.error(a)) }
            }
        }
    }

    /** Your own name for someone: kept on this phone only; they never see it. Blank removes it. */
    fun editName(a: Activity, peerId: String, theirName: String, done: () -> Unit = {}) {
        val current = Names.nickname(a, peerId)
        val field = EditText(a).apply {
            setText(current); setSelection(text.length); hint = theirName.ifBlank { L.t(a, "Name") }; textSize = 17f; setSingleLine(true)
            setTextColor(AppTheme.text(a)); setHintTextColor(AppTheme.sub(a)); filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            background = RabtaUi.shape(a, AppTheme.bg(a), 16, AppTheme.line(a)); setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14))
            contentDescription = L.t(a, "Your name for this contact")
        }
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(a, 24), dp(a, 8), dp(a, 24), 0)
            addView(field, LinearLayout.LayoutParams(-1, -2))
            addView(RabtaUi.text(a, if (theirName.isBlank()) "Only you see this name." else "Only you see this name. On Rabta they are “$theirName”.", 13f, color = AppTheme.sub(a)).apply {
                setPadding(dp(a, 4), dp(a, 10), dp(a, 4), 0)
            })
        }
        fun save(value: String) = Thread {
            runCatching { Names.set(a, peerId, value, theirName) }
            a.runOnUiThread { if (!a.isDestroyed) done() }
        }.start()
        val builder = AlertDialog.Builder(a).setTitle(if (current.isBlank()) "Add your name for them" else "Edit name").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> save(field.text.toString()) }
        if (current.isNotBlank()) builder.setNeutralButton("Remove") { _, _ -> save("") }
        builder.show()
        field.requestFocus()
    }
}
