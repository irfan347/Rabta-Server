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

    /**
     * Who sees your updates, like WhatsApp: all your contacts, all except some, or only some. The choice and both lists
     * are kept per account; the first update still asks, so nothing is shared before you chose.
     */
    enum class Audience { ALL, EXCEPT, ONLY }
    private const val MODE = "audience_mode"
    private const val EXCEPT_IDS = "audience_except"
    private const val ONLY_IDS = "audience_only"
    private fun ids(c: Context, key: String): Set<String> = runCatching { JSONArray(prefs(c).getString(key, "[]")) }.getOrDefault(JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }

    /** The saved choice; a list picked before these choices existed counts as "Only share with". Null until chosen. */
    fun mode(c: Context): Audience? = prefs(c).getString(MODE, null)?.let { runCatching { Audience.valueOf(it) }.getOrNull() }
        ?: if (prefs(c).contains("audience")) Audience.ONLY else null
    fun excluded(c: Context) = ids(c, EXCEPT_IDS)
    fun included(c: Context) = if (prefs(c).contains(ONLY_IDS)) ids(c, ONLY_IDS) else ids(c, "audience")

    /** The people who see an update, from the choice and your current, unblocked contacts. */
    fun resolve(mode: Audience, contacts: List<String>, except: Set<String>, only: Set<String>): List<String> = when (mode) {
        Audience.ALL -> contacts
        Audience.EXCEPT -> contacts.filter { it !in except }
        Audience.ONLY -> contacts.filter { it in only }
    }

    /** The viewers for the saved choice. Empty until the user picks. */
    fun audience(c: Context): List<String> {
        val mode = mode(c) ?: return emptyList()
        return resolve(mode, contacts(c).map { it.peerId }, excluded(c), included(c))
    }
    fun saveAudience(c: Context, mode: Audience, except: Collection<String>, only: Collection<String>) =
        prefs(c).edit().putString(MODE, mode.name).putString(EXCEPT_IDS, JSONArray(except.toList()).toString()).putString(ONLY_IDS, JSONArray(only.toList()).toString()).apply()

    fun audienceLabel(c: Context, ids: List<String>): String = when (mode(c)) {
        null -> "Choose who can see"
        Audience.ALL -> "My contacts"
        Audience.EXCEPT -> excluded(c).size.let { if (it == 0) "My contacts" else "My contacts except $it" }
        Audience.ONLY -> "Only ${ids.size} contact${if (ids.size == 1) "" else "s"}"
    }

    /**
     * Choose audience: My contacts, My contacts except…, Only share with… — each list edited in a searchable picker.
     * [confirm] is "Done" when only changing the choice, "Share" when the update goes out next.
     */
    fun chooseAudience(a: Activity, current: List<String>, confirm: String = "Done", done: (List<String>) -> Unit) {
        val contacts = contacts(a)
        if (contacts.isEmpty()) {
            AlertDialog.Builder(a).setTitle("No available contacts").setMessage("Add a contact before sharing a status. Blocked contacts cannot be selected.").setPositiveButton("Close", null).show(); return
        }
        val all = contacts.map { it.peerId }
        var mode = mode(a) ?: Audience.ALL
        val except = excluded(a).filter { it in all }.toMutableSet()
        val only = included(a).filter { it in all }.toMutableSet()
        fun dp(n: Int) = RabtaUi.dp(a, n)
        val dialog = android.app.Dialog(a)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(20))
            background = RabtaUi.shape(a, AppTheme.surface(a), 28).apply { val r = dp(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        root.addView(View(a).apply { background = RabtaUi.shape(a, AppTheme.line(a), 2) }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })
        root.addView(RabtaUi.text(a, "Who can see your status", 20f, true).apply { setPadding(dp(8), 0, 0, dp(12)); androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
        val rows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rows)
        val note = RabtaUi.text(a, "", 13f, color = AppTheme.error(a)).apply { setPadding(dp(8), dp(6), dp(8), 0); visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(note)
        lateinit var render: () -> Unit
        fun edit(kind: Audience) {
            val title = if (kind == Audience.EXCEPT) "Hide status from…" else "Share status with…"
            PeoplePicker.dialog(a, title, if (kind == Audience.EXCEPT) except else only) { chosen ->
                val target = if (kind == Audience.EXCEPT) except else only
                target.clear(); target.addAll(chosen); mode = kind; render()
            }
        }
        render = {
            rows.removeAllViews(); note.visibility = View.GONE
            fun option(kind: Audience, icon: String, title: String, detail: String, editable: Boolean) {
                val on = mode == kind
                rows.addView(LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(72); setPadding(dp(8), dp(10), dp(8), dp(10)); isFocusable = true
                    background = RabtaUi.ripple(a, if (on) AppTheme.raised(a) else android.graphics.Color.TRANSPARENT, 18)
                    contentDescription = "$title. $detail. ${if (on) "Selected" else "Not selected"}"
                    setOnClickListener { mode = kind; if (editable && (if (kind == Audience.EXCEPT) except else only).isEmpty()) edit(kind) else render() }
                    addView(UiIcons.image(a, icon, AppTheme.accent(a), 22, "").apply { background = RabtaUi.shape(a, AppTheme.bg(a), 999); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                        LinearLayout.LayoutParams(dp(48), dp(48)))
                    val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
                    texts.addView(RabtaUi.text(a, title, 16f, true))
                    texts.addView(RabtaUi.text(a, detail, 13f, editable, if (editable) AppTheme.accent(a) else AppTheme.sub(a)).apply {
                        if (editable) { isFocusable = true; setPadding(0, dp(4), dp(8), dp(4)); setOnClickListener { edit(kind) } }
                    })
                    addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                    // A radio dot: filled ring when chosen.
                    addView(View(a).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        background = android.graphics.drawable.LayerDrawable(arrayOf(
                            android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(2), if (on) AppTheme.accent(a) else AppTheme.sub(a)) },
                            android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(if (on) AppTheme.accent(a) else android.graphics.Color.TRANSPARENT) }
                        )).apply { setLayerInset(1, dp(5), dp(5), dp(5), dp(5)) }
                    }, LinearLayout.LayoutParams(dp(22), dp(22)))
                })
            }
            option(Audience.ALL, "people", "My contacts", "All ${all.size} contact${if (all.size == 1) "" else "s"}", false)
            option(Audience.EXCEPT, "eye_off", "My contacts except…", "${except.size} excluded · Edit ›", true)
            option(Audience.ONLY, "check", "Only share with…", "${only.size} included · Edit ›", true)
        }
        render()
        root.addView(RabtaUi.button(a, confirm, true) {
            val chosen = resolve(mode, all, except, only)
            if (chosen.isEmpty()) {
                note.text = L.t(a, if (mode == Audience.ONLY) "Choose at least one person to share with." else "With everyone excluded, no one would see it.")
                note.visibility = View.VISIBLE; return@button
            }
            saveAudience(a, mode, except, only); dialog.dismiss(); done(chosen)
        }.apply { gravity = Gravity.CENTER; contentDescription = confirm }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        dialog.setContentView(root)
        dialog.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); setGravity(Gravity.BOTTOM); setDimAmount(0.5f) }
        dialog.show()
        dialog.window?.setLayout(-1, -2)
    }

    /** Uploads the media (resumable), publishes the update, and keeps the local copy as this phone's preview. Blocking. */
    fun publish(c: Context, kind: String, body: String, caption: String, ids: List<String>, look: JSONObject? = null, changed: () -> Unit = {}): JSONObject {
        val remote = if (kind == "text") body else Transfers.upload(c, body, UPLOAD, changed)
        val request = JSONObject().put("kind", kind).put("body", remote).put("audience", JSONArray(ids))
        // This phone keeps its own copy (mapped below), so the server may let go of the media once the whole audience has it.
        if (kind != "text") request.put("ownCopy", true)
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
