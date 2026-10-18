package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject

/** Status sharing shared by the Status screen, the editor and the viewer: audience, publishing, seen marks and viewers. */
object StatusPost {
    private fun prefs(c: Context) = c.getSharedPreferences("status_prefs_${Session.id}", 0)

    fun contacts(c: Context) = Db.get(c).contacts().filter { it.peerId != Session.id && !Privacy.blocked(c, it.peerId) }

    /** The viewers chosen last time, still limited to current, unblocked contacts. Empty until the user picks. */
    fun audience(c: Context): List<String> {
        val allowed = contacts(c).map { it.peerId }.toSet()
        val saved = runCatching { JSONArray(prefs(c).getString("audience", "[]")) }.getOrDefault(JSONArray())
        return (0 until saved.length()).map { saved.optString(it) }.filter { it in allowed }
    }
    fun saveAudience(c: Context, ids: List<String>) = prefs(c).edit().putString("audience", JSONArray(ids).toString()).apply()
    fun audienceLabel(ids: List<String>) = if (ids.isEmpty()) "Choose who can see" else "Status (${ids.size} contact${if (ids.size == 1) "" else "s"})"

    fun chooseAudience(a: Activity, current: List<String>, done: (List<String>) -> Unit) {
        val contacts = contacts(a)
        if (contacts.isEmpty()) {
            AlertDialog.Builder(a).setTitle("No available contacts").setMessage("Add a contact before sharing a status. Blocked contacts cannot be selected.").setPositiveButton("Close", null).show(); return
        }
        val selected = BooleanArray(contacts.size) { contacts[it].peerId in current }
        AlertDialog.Builder(a).setTitle("Who can see this update")
            .setMultiChoiceItems(contacts.map { it.peerName }.toTypedArray(), selected) { _, i, checked -> selected[i] = checked }
            .setNeutralButton("All") { _, _ -> val ids = contacts.map { it.peerId }; saveAudience(a, ids); done(ids) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done") { _, _ -> val ids = contacts.filterIndexed { i, _ -> selected[i] }.map { it.peerId }; saveAudience(a, ids); done(ids) }
            .show()
    }

    /** Uploads the media (resumable), publishes the update, and keeps the local copy as this phone's preview. Blocking. */
    fun publish(c: Context, kind: String, body: String, caption: String, ids: List<String>, look: JSONObject? = null, changed: () -> Unit = {}): JSONObject {
        val remote = if (kind == "text") body else Transfers.upload(c, body, UPLOAD, changed)
        val request = JSONObject().put("kind", kind).put("body", remote).put("audience", JSONArray(ids))
        if (caption.isNotBlank() && kind != "text") request.put("caption", caption.trim())
        // A text update's colour and font; servers before this field simply ignore it.
        if (kind == "text" && look != null) request.put("bg", look.optString("bg")).put("font", look.optString("font"))
        val result = FeatureApi.request("/api/status", "POST", request)
        // Your own update opens from the file already on this phone instead of downloading it again.
        if (kind != "text") runCatching { c.getSharedPreferences("downloads_${Session.id}", 0).edit().putString(JSONObject(remote).getString("remote"), body).apply() }
        return result
    }
    const val UPLOAD = "status"

    fun seen(c: Context): Set<String> = prefs(c).getStringSet("seen", emptySet())!!
    fun markSeen(c: Context, id: String) { val s = seen(c); if (id !in s) prefs(c).edit().putStringSet("seen", s + id).apply() }
    /** Forgets seen marks of updates that have expired. */
    fun pruneSeen(c: Context, live: Collection<String>) { val s = seen(c); if (!live.containsAll(s)) prefs(c).edit().putStringSet("seen", s.filter { it in live }.toSet()).apply() }

    fun stamp(c: Context, time: Long): String {
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val clock = android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(time))
        return when (day.format(java.util.Date(time))) {
            day.format(java.util.Date()) -> "Today, $clock"
            day.format(java.util.Date(System.currentTimeMillis() - 86_400_000)) -> "Yesterday, $clock"
            else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(time)) + ", " + clock
        }
    }

    /** Who viewed your update and when, newest first (bottom sheet). */
    fun viewers(a: Activity, status: JSONObject, onDelete: (() -> Unit)? = null, onClose: () -> Unit = {}) {
        val views = status.optJSONArray("views")
        val list = if (views != null) (0 until views.length()).map { views.getJSONObject(it) }.sortedByDescending { it.optLong("at") }
            else status.optJSONArray("viewers")?.let { v -> (0 until v.length()).map { JSONObject().put("id", v.optString(it)) } }.orEmpty()
        val names = Db.get(a).contacts().associate { it.peerId to it.name }
        val dialog = android.app.Dialog(a)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(RabtaUi.dp(a, 20), RabtaUi.dp(a, 12), RabtaUi.dp(a, 20), RabtaUi.dp(a, 20))
            background = RabtaUi.shape(a, AppTheme.surface(a), 28).apply { val r = RabtaUi.dp(a, 28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        root.addView(View(a).apply { background = RabtaUi.shape(a, AppTheme.line(a), 2) }, LinearLayout.LayoutParams(RabtaUi.dp(a, 36), RabtaUi.dp(a, 4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = RabtaUi.dp(a, 16) })
        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(UiIcons.image(a, "eye", AppTheme.accent(a), 22, "Views"), LinearLayout.LayoutParams(RabtaUi.dp(a, 28), RabtaUi.dp(a, 28)).apply { marginEnd = RabtaUi.dp(a, 10) })
        head.addView(RabtaUi.text(a, if (list.size == 1) "Viewed by 1" else "Viewed by ${list.size}", 19f, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (onDelete != null) head.addView(UiIcons.image(a, "trash", AppTheme.error(a), 22, "Delete status").apply {
            isFocusable = true; background = RabtaUi.ripple(a, android.graphics.Color.TRANSPARENT, 999)
            setOnClickListener {
                AlertDialog.Builder(a).setTitle("Delete this status?").setMessage("Viewers will no longer be able to open it.").setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ -> dialog.dismiss(); onDelete() }.show()
            }
        }, LinearLayout.LayoutParams(RabtaUi.dp(a, 48), RabtaUi.dp(a, 48)))
        root.addView(head)
        val items = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, RabtaUi.dp(a, 8), 0, 0) }
        if (list.isEmpty()) items.addView(RabtaUi.text(a, "No views yet. When your contacts open this update, they appear here.", 14f, color = AppTheme.sub(a)).apply { setPadding(0, RabtaUi.dp(a, 12), 0, RabtaUi.dp(a, 12)) })
        list.forEach { v ->
            val id = v.optString("id"); val name = names[id] ?: v.optString("name").ifBlank { id }
            val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, RabtaUi.dp(a, 10), 0, RabtaUi.dp(a, 10)) }
            row.addView(avatar(a, id, name, 44), LinearLayout.LayoutParams(RabtaUi.dp(a, 44), RabtaUi.dp(a, 44)).apply { marginEnd = RabtaUi.dp(a, 14) })
            val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(RabtaUi.text(a, name, 16f, true))
            v.optLong("at").takeIf { it > 0 }?.let { texts.addView(RabtaUi.text(a, stamp(a, it), 13f, color = AppTheme.sub(a))) }
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            items.addView(row)
        }
        root.addView(ScrollView(a).apply { addView(items) }, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(root); dialog.setOnDismissListener { onClose() }
        dialog.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); setGravity(Gravity.BOTTOM) }
        dialog.show()
        dialog.window?.setLayout(-1, -2)
    }

    /** Real profile photo when one exists, otherwise initials. */
    fun avatar(c: Context, id: String, name: String, sizeDp: Int): FrameLayout = FrameLayout(c).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(RabtaUi.text(c, name.filter { it.isLetterOrDigit() }.take(1).uppercase().ifEmpty { "R" }, sizeDp * 0.4f, true, android.graphics.Color.WHITE).apply {
            gravity = Gravity.CENTER; includeFontPadding = false; background = RabtaUi.shape(c, 0xFF325E71.toInt(), 999)
        }, FrameLayout.LayoutParams(-1, -1))
        val photo = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = RabtaUi.shape(c, AppTheme.raised(c), 999); visibility = View.GONE }
        addView(photo, FrameLayout.LayoutParams(-1, -1))
        if (id == Session.id) ProfilePhoto.current()?.let { photo.setImageBitmap(it); photo.visibility = View.VISIBLE }
        else if (id.isNotBlank()) ProfileHelper.loadIfNeeded(id, Session.serverUrl, Session.token) { b -> if (b != null) { photo.setImageBitmap(b); photo.visibility = View.VISIBLE } }
    }
}

/** WhatsApp's status ring: one arc per live update, lime while unseen and grey once seen. */
class StatusRingView(c: Context) : FrameLayout(c) {
    var total = 1
        set(value) { field = value; invalidate() }
    var seen = 0
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val box = RectF()
    init { setWillNotDraw(false); val p = RabtaUi.dp(c, 4); setPadding(p, p, p, p) }
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val d = resources.displayMetrics.density; paint.strokeWidth = 2.2f * d
        box.set(paint.strokeWidth, paint.strokeWidth, width - paint.strokeWidth, height - paint.strokeWidth)
        val n = total.coerceIn(1, 30); val gap = if (n == 1) 0f else 8f; val sweep = 360f / n
        for (i in 0 until n) {
            paint.color = if (i < seen) AppTheme.sub(context) else AppTheme.accent(context)
            canvas.drawArc(box, -90f + i * sweep + gap / 2, sweep - gap, false, paint)
        }
    }
}

/** The thin bars across the top of the viewer: done, current (partly filled) and upcoming updates. */
class StoryBarsView(c: Context) : View(c) {
    var count = 1
        set(value) { field = value; invalidate() }
    var index = 0
        set(value) { field = value; invalidate() }
    var fraction = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density; val gap = 4 * d; val n = count.coerceAtLeast(1)
        val w = (width - gap * (n - 1)) / n; val y = height / 2f; paint.strokeWidth = 2.6f * d
        for (i in 0 until n) {
            val x = i * (w + gap)
            paint.color = 0x55FFFFFF; canvas.drawLine(x + d, y, x + w - d, y, paint)
            val fill = when { i < index -> 1f; i == index -> fraction; else -> 0f }
            if (fill > 0) { paint.color = 0xFFFFFFFF.toInt(); canvas.drawLine(x + d, y, x + d + (w - 2 * d) * fill, y, paint) }
        }
    }
}
