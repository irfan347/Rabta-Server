package com.irfan.rabta

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class StatusActivity : ThemedActivity() {
    private lateinit var feed: LinearLayout
    private var posting = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val col = FeatureUi.page(this, "Status")
        val intro = RabtaUi.card(this)
        intro.addView(RabtaUi.text(this, "Share a moment", 20f, true))
        intro.addView(RabtaUi.text(this, "Updates expire after 24 hours. Choose exactly which contacts can view each one.", 14f, color = AppTheme.sub(this)))
        col.addView(intro)

        col.addView(RabtaUi.text(this, "CREATE UPDATE", 12f, true, AppTheme.accent(this)).apply {
            setPadding(RabtaUi.dp(this@StatusActivity, 4), RabtaUi.dp(this@StatusActivity, 12), 0, RabtaUi.dp(this@StatusActivity, 8))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actions.addView(RabtaUi.button(this, "Text status", true) { composeText() })
        actions.addView(RabtaUi.button(this, "Share a photo") { pick("image/*", 31) })
        actions.addView(RabtaUi.button(this, "Share a video") { pick("video/*", 32) })
        col.addView(actions)

        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, RabtaUi.dp(this@StatusActivity, 16), 0, RabtaUi.dp(this@StatusActivity, 8)) }
        heading.addView(RabtaUi.text(this, "RECENT UPDATES", 12f, true, AppTheme.accent(this)).apply { setPadding(0, 0, 0, 0); androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(RabtaUi.iconButton(this, "connection", "Refresh status updates") { refresh() })
        col.addView(heading)
        feed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(feed)
        refresh()
    }

    private fun composeText() {
        val input = EditText(this).apply {
            hint = "What would you like to share?"; minLines = 3; maxLines = 7; gravity = Gravity.TOP
            filters = arrayOf(android.text.InputFilter.LengthFilter(700))
            setTextColor(AppTheme.text(this@StatusActivity)); setHintTextColor(AppTheme.sub(this@StatusActivity))
            background = RabtaUi.shape(this@StatusActivity, AppTheme.raised(this@StatusActivity), 16)
            setPadding(RabtaUi.dp(this@StatusActivity, 14), RabtaUi.dp(this@StatusActivity, 12), RabtaUi.dp(this@StatusActivity, 14), RabtaUi.dp(this@StatusActivity, 12))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(RabtaUi.dp(this@StatusActivity, 24), 0, RabtaUi.dp(this@StatusActivity, 24), 0); addView(input) }
        AlertDialog.Builder(this).setTitle("Text status").setView(content).setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val text = input.text.toString().trim()
                        if (text.isEmpty()) { input.error = "Write something first"; return@setOnClickListener }
                        dialog.dismiss(); audience("text", text)
                    }
                }
                dialog.show()
            }
    }

    private fun pick(mime: String, code: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE), code)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || requestCode !in 31..32) return
        val uri = data?.data ?: return
        FeatureApi.run(this, { Attachments.pick(this, uri, false) }) { body -> audience(if (requestCode == 31) "image" else "video", body) }
    }

    private fun audience(kind: String, body: String) {
        val contacts = Db.get(this).conversations().filter { !it.isGroup && !Privacy.blocked(this, it.peerId) }
        if (contacts.isEmpty()) { themedMessage("No available contacts", "Add a contact before sharing a status. Blocked contacts cannot be selected."); return }
        val selected = BooleanArray(contacts.size)
        AlertDialog.Builder(this).setTitle("Choose viewers")
            .setMessage("Only selected contacts can see this update.")
            .setMultiChoiceItems(contacts.map { it.peerName }.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
            .setNegativeButton("Cancel", null).setPositiveButton("Share") { _, _ ->
                if (posting) return@setPositiveButton
                val ids = contacts.filterIndexed { index, _ -> selected[index] }.map { it.peerId }
                if (ids.isEmpty()) { themedMessage("Choose at least one viewer", "A status is only shared with contacts you select."); return@setPositiveButton }
                posting = true
                feed.addView(RabtaUi.loading(this, "Sharing status…"), 0)
                Thread {
                    val result = runCatching {
                        val remote = if (kind == "text") body else Transfers.upload(this, body, "status") {}
                        FeatureApi.request("/api/status", "POST", JSONObject().put("kind", kind).put("body", remote).put("audience", JSONArray(ids)))
                    }
                    runOnUiThread {
                        posting = false
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        refresh()
                        result.onFailure { themedMessage("Status not shared", it.message ?: "Check your connection and try again.") }
                    }
                }.start()
            }.show()
    }

    private fun refresh() {
        feed.removeAllViews(); feed.addView(RabtaUi.loading(this, "Loading updates…"))
        Thread {
            val result = runCatching { FeatureApi.request("/api/status").getJSONArray("statuses") }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.onSuccess(::render).onFailure {
                    feed.removeAllViews()
                    feed.addView(RabtaUi.empty(this, "status", "Could not load updates", "Check your connection and try again.", "Retry") { refresh() })
                }
            }
        }.start()
    }

    private fun render(items: JSONArray) {
        feed.removeAllViews()
        if (items.length() == 0) {
            feed.addView(RabtaUi.empty(this, "status", "No updates yet", "Your contacts’ updates and your own posts will appear here."))
            return
        }
        for (index in 0 until items.length()) {
            val status = items.getJSONObject(index)
            val own = status.optString("owner") == Session.id
            val name = status.optString("name").ifBlank { "Rabta contact" }
            val kind = status.optString("kind").replaceFirstChar { it.uppercase() }
            val expires = status.optLong("expires")
            val remaining = ((expires - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
            val created = status.optLong("created")
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = RabtaUi.shape(this@StatusActivity, AppTheme.surface(this@StatusActivity), 20, AppTheme.line(this@StatusActivity))
                setPadding(RabtaUi.dp(this@StatusActivity, 16), RabtaUi.dp(this@StatusActivity, 14), RabtaUi.dp(this@StatusActivity, 16), RabtaUi.dp(this@StatusActivity, 12))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = RabtaUi.dp(this@StatusActivity, 10) }
            }
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val badge = TextView(this).apply {
                text = name.take(1).uppercase(); gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE)
                textSize = 16f; typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
                background = RabtaUi.shape(this@StatusActivity, if (own) AppTheme.ACCENT else AppTheme.teal(this@StatusActivity), 999)
            }
            top.addView(badge, LinearLayout.LayoutParams(RabtaUi.dp(this, 44), RabtaUi.dp(this, 44)))
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(RabtaUi.dp(this@StatusActivity, 12), 0, 0, 0) }
            labels.addView(RabtaUi.text(this, if (own) "Your status" else name, 16f, true).apply { setPadding(0, 0, 0, 2) })
            labels.addView(RabtaUi.text(this, "$kind · ${if (created > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(created)) else "Recently shared"}", 12f, color = AppTheme.sub(this)).apply { setPadding(0, 0, 0, 0) })
            top.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(RabtaUi.text(this, "${remaining / 60}h ${remaining % 60}m", 12f, color = AppTheme.sub(this)).apply { contentDescription = "Expires in ${remaining / 60} hours and ${remaining % 60} minutes" })
            card.addView(top)
            card.addView(RabtaUi.text(this, if (status.optString("kind") == "text") status.optString("body") else "Tap to view ${kind.lowercase()}", 14f, color = AppTheme.text(this)).apply {
                maxLines = 3; setPadding(RabtaUi.dp(this@StatusActivity, 56), RabtaUi.dp(this@StatusActivity, 10), 0, RabtaUi.dp(this@StatusActivity, 4))
            })
            card.isClickable = true; card.isFocusable = true; card.background = RabtaUi.ripple(this, AppTheme.surface(this), 20)
            card.contentDescription = "$name, $kind status. Expires in ${remaining / 60} hours. Open status."
            card.setOnClickListener { view(status) }
            feed.addView(card)
            if (own) {
                val viewers = status.optJSONArray("viewers") ?: JSONArray()
                feed.addView(RabtaUi.button(this, "${viewers.length()} views · Manage") {
                    manage(status, viewers)
                }.apply { setPadding(RabtaUi.dp(this@StatusActivity, 16), RabtaUi.dp(this@StatusActivity, 6), RabtaUi.dp(this@StatusActivity, 16), RabtaUi.dp(this@StatusActivity, 6)) })
            }
        }
    }

    private fun manage(status: JSONObject, viewers: JSONArray) {
        val names = (0 until viewers.length()).map { i ->
            val id = viewers.optString(i)
            Db.get(this).conversations().find { it.peerId == id }?.peerName ?: id
        }
        AlertDialog.Builder(this).setTitle("Status activity")
            .setMessage(if (names.isEmpty()) "No one has viewed this update yet." else "Viewed by\n${names.joinToString("\n")}")
            .setNegativeButton("Close", null).setPositiveButton("Delete status", null).create().also { dialog ->
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    AlertDialog.Builder(this).setTitle("Delete this status?").setMessage("Viewers will no longer be able to open it.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                            FeatureApi.run(this, { FeatureApi.request("/api/status/${status.getString("id")}", "DELETE") }) { refresh() }
                        }.show(); dialog.dismiss()
                } }
                dialog.show()
            }
    }

    private fun view(status: JSONObject) {
        if (status.optLong("expires") <= System.currentTimeMillis()) { refresh(); return }
        FeatureApi.run(this, { FeatureApi.request("/api/status/${status.getString("id")}/view", "POST") }) {
            if (status.optString("kind") == "text") {
                AlertDialog.Builder(this).setTitle(status.optString("name", "Status update"))
                    .setMessage(status.optString("body"))
                    .setPositiveButton("Close", null).show()
            } else Attachments.open(this, status.getString("body"))
        }
    }

    private fun themedMessage(title: String, message: String) =
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Close", null).show()
}
