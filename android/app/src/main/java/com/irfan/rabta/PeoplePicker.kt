package com.irfan.rabta

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Choose several people: search by name, @username or Rabta ID (someone who is not a contact yet is found on the
 * server), the people you talk to most first, then everyone A–Z. Chosen people sit as removable chips on top.
 * Used by New group, by the status audience ("except…", "only share with…") and, with [groups], by Forward.
 */
class PeoplePicker(private val a: Activity, preselected: Collection<String> = emptyList(), private val max: Int = Int.MAX_VALUE,
                   private val limitNote: String = "", private val groups: Boolean = false, private val changed: (Int) -> Unit = {}) {
    private data class Person(val id: String, val name: String, val username: String, val recent: Long, val group: Boolean = false)
    private fun dp(n: Int) = RabtaUi.dp(a, n)
    private val handler = Handler(Looper.getMainLooper())
    private var people: List<Person> = emptyList()
    private var query = ""
    private val boxes = mutableMapOf<String, MutableList<ImageView>>()
    /** Chosen IDs in the order they were chosen, with the name shown for each. */
    val selected = LinkedHashMap<String, String>()
    private val preselect = preselected.toSet()

    private val chips = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(10)) }
    private val chipsScroll = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(chips); visibility = View.GONE }
    private val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(104)) }
    val search: EditText = EditText(a).apply {
        hint = L.t(a, "Search name, @username or Rabta ID"); textSize = 15f; setSingleLine(true); background = null
        setTextColor(AppTheme.text(a)); setHintTextColor(AppTheme.sub(a)); minHeight = dp(48); setPadding(0, dp(8), 0, dp(8))
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH; contentDescription = hint
    }
    private val render = Runnable { render() }
    val view: LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        val bar = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(4), 0); background = RabtaUi.shape(a, AppTheme.raised(a), 24) }
        bar.addView(UiIcons.image(a, "search", AppTheme.sub(a), 20, "Search"), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
        bar.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = UiIcons.image(a, "close", AppTheme.sub(a), 18, L.t(a, "Clear search")).apply {
            visibility = View.GONE; isFocusable = true; background = RabtaUi.ripple(a, Color.TRANSPARENT, 999); setOnClickListener { search.setText("") }
        }
        bar.addView(clear, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(bar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), dp(6)) })
        addView(chipsScroll, LinearLayout.LayoutParams(-1, -2))
        addView(ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                query = s?.toString().orEmpty().trim(); clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                handler.removeCallbacks(render); handler.postDelayed(render, 150)
            }
        })
    }

    /** Contacts (and people you chat with one-to-one), most recent first for the top section. */
    fun load() {
        list.removeAllViews(); list.addView(RabtaUi.loading(a, "Loading contacts…"))
        Thread {
            val result = runCatching {
                val db = Db.get(a)
                val chats = db.conversations().filter { !it.isGroup && !People.isSelf(it.peerId) }
                val lastChat = chats.associate { it.peerId to it.lastTime }
                val fromContacts = db.contacts().map { Person(it.peerId, it.shownName, it.username, lastChat[it.peerId] ?: 0L) }
                val known = fromContacts.map { it.id }.toSet()
                val groupChats = if (groups) db.conversations().filter { it.isGroup }.map { Person(it.peerId, it.shownName, "", it.lastTime, true) } else emptyList()
                (fromContacts + chats.filter { it.peerId !in known }.map { Person(it.peerId, it.shownName, "", it.lastTime) })
                    .filter { !People.isSelf(it.id) && !Privacy.blocked(a, it.id) } + groupChats
            }
            a.runOnUiThread {
                if (a.isDestroyed) return@runOnUiThread
                people = result.getOrDefault(emptyList())
                people.filter { it.id in preselect && it.id !in selected }.forEach { selected[it.id] = it.name }
                renderChips(); changed(selected.size); render()
                if (result.isFailure) Toast.makeText(a, "Could not load contacts. Try again.", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun render() {
        list.removeAllViews(); boxes.clear()
        val q = query.removePrefix("@")
        if (q.isNotEmpty()) {
            val hits = people.filter { it.name.contains(q, true) || it.username.contains(q, true) || it.id.contains(q, true) }.sortedBy { it.name.lowercase() }
            if (hits.isNotEmpty()) { section("Results"); hits.forEach { list.addView(row(it)) } }
            val exact = hits.any { it.username.equals(q, true) || it.id.equals(q, true) }
            if (!exact && Regex("[A-Za-z0-9_]{3,20}").matches(q)) list.addView(findRow(q))
            if (hits.isEmpty() && !Regex("[A-Za-z0-9_]{3,20}").matches(q)) list.addView(note("No one matches “$query”."))
            return
        }
        if (people.isEmpty()) {
            list.addView(RabtaUi.empty(a, "people", "No contacts yet", "Search an @username or Rabta ID above to find someone on Rabta."))
            return
        }
        val frequent = people.filter { it.recent > 0 }.sortedByDescending { it.recent }.take(5)
        if (frequent.size >= 2 && people.size > 5) { section("Frequently contacted"); frequent.forEach { list.addView(row(it)) } }
        section(if (frequent.size >= 2 && people.size > 5) "All contacts" else "Contacts")
        people.sortedBy { it.name.lowercase() }.forEach { list.addView(row(it)) }
    }

    private fun section(title: String) = list.addView(RabtaUi.section(a, title).apply { setPadding(dp(12), dp(14), 0, dp(6)) })
    private fun note(text: String) = RabtaUi.text(a, text, 14f, color = AppTheme.sub(a)).apply { setPadding(dp(12), dp(16), dp(12), dp(16)) }

    private fun row(p: Person): View = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64); setPadding(dp(10), dp(8), dp(12), dp(8)); isFocusable = true
        background = RabtaUi.ripple(a, Color.TRANSPARENT, 16)
        addView(People.avatar(a, p.id, p.name, dp(46)), LinearLayout.LayoutParams(dp(46), dp(46)))
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        texts.addView(RabtaUi.text(a, p.name, 16f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        texts.addView(RabtaUi.text(a, when { p.group -> L.t(a, "Group"); p.username.isNotBlank() -> "@${p.username}"; else -> "Rabta contact" }, 13f, color = AppTheme.sub(a)).apply { maxLines = 1 })
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val box = ImageView(a).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; scaleType = ImageView.ScaleType.CENTER }
        boxes.getOrPut(p.id) { mutableListOf() }.add(box)
        paint(box, p.id in selected)
        addView(box, LinearLayout.LayoutParams(dp(26), dp(26)))
        describe(this, p)
        setOnClickListener { toggle(p.id, p.name); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); describe(this, p) }
    }
    private fun describe(row: View, p: Person) { row.contentDescription = "${p.name}, ${if (p.id in selected) L.t(a, "selected") else L.t(a, "not selected")}" }

    /** A round box: an outline when free, filled with a tick when chosen. */
    private fun paint(box: ImageView, on: Boolean) {
        box.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (on) setColor(AppTheme.primary(a)) else { setColor(Color.TRANSPARENT); setStroke(dp(2), AppTheme.sub(a)) }
        }
        box.setImageDrawable(if (on) UiIcons.drawable(a, "check", AppTheme.onPrimary(a), 18) else null)
    }

    private fun findRow(q: String): View = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60); setPadding(dp(10), dp(8), dp(12), dp(8)); isFocusable = true
        background = RabtaUi.ripple(a, Color.TRANSPARENT, 16)
        addView(UiIcons.image(a, "person_add", AppTheme.onPrimary(a), 22, "Find").apply { background = RabtaUi.shape(a, AppTheme.primary(a), 999) }, LinearLayout.LayoutParams(dp(46), dp(46)))
        val status = RabtaUi.text(a, "Find @$q on Rabta", 15f, true, AppTheme.accent(a)).apply { setPadding(dp(14), 0, dp(8), 0) }
        addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener {
            People.lookup(a, q, { status.text = it }, { status.setTextColor(it) }) { id, name, username ->
                if (People.isSelf(id)) { status.text = L.t(a, "That's you"); return@lookup }
                if (people.none { it.id == id }) people = people + Person(id, Names.of(a, id, name), username, 0L)
                if (id !in selected) toggle(id, Names.of(a, id, name))
                search.setText("")
            }
        }
    }

    private fun toggle(id: String, name: String) {
        if (id in selected) selected.remove(id)
        else {
            if (selected.size >= max) { Toast.makeText(a, limitNote.ifBlank { "You can choose up to $max people" }, Toast.LENGTH_SHORT).show(); return }
            selected[id] = name
        }
        boxes[id]?.forEach { paint(it, id in selected) }
        renderChips(); changed(selected.size)
    }

    private fun renderChips() {
        chips.removeAllViews()
        chipsScroll.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        selected.forEach { (id, name) ->
            chips.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true
                contentDescription = "${L.t(a, "Remove")} $name"; background = RabtaUi.ripple(a, Color.TRANSPARENT, 16); setPadding(dp(4), dp(2), dp(4), dp(2))
                val face = FrameLayout(a)
                face.addView(People.avatar(a, id, name, dp(48)), FrameLayout.LayoutParams(dp(48), dp(48)))
                face.addView(UiIcons.image(a, "close", Color.WHITE, 12, "").apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AppTheme.sub(a)); setStroke(dp(2), AppTheme.bg(a)) }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM or Gravity.END))
                addView(face, LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(RabtaUi.text(a, name.substringBefore(' ').take(12), 12f).apply { gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(dp(64), -2))
                setOnClickListener { toggle(id, name) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
        }
        chipsScroll.post { chipsScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    companion object {
        /**
         * Full-screen chooser for people, e.g. who is left out of (or let into) a status. [done] gets the chosen IDs.
         */
        fun dialog(a: Activity, title: String, preselected: Collection<String>, done: (List<String>) -> Unit) =
            pick(a, title, preselected) { done(it.keys.toList()) }

        /** The same chooser, also offering group chats when [groups]; [done] gets the chosen IDs with their names, in order. */
        fun pick(a: Activity, title: String, preselected: Collection<String> = emptyList(), groups: Boolean = false, max: Int = Int.MAX_VALUE,
                 limitNote: String = "", icon: String = "check", label: String = "Done", done: (LinkedHashMap<String, String>) -> Unit) {
            val d = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar)
            fun dp(n: Int) = RabtaUi.dp(a, n)
            val root = FrameLayout(a).apply { setBackgroundColor(AppTheme.bg(a)) }
            val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(8), dp(16), dp(4)) }
            head.addView(UiIcons.image(a, "close", AppTheme.text(a), 24, L.t(a, "Close")).apply {
                isFocusable = true; background = RabtaUi.ripple(a, Color.TRANSPARENT, 999); setOnClickListener { d.dismiss() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            val titles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
            titles.addView(RabtaUi.text(a, title, 20f, true).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
            val count = RabtaUi.text(a, "", 13f, color = AppTheme.sub(a)).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            titles.addView(count)
            head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
            column.addView(head)
            val picker = PeoplePicker(a, preselected, max, limitNote, groups) { n -> count.text = if (n == 0) L.t(a, "No one selected") else "$n ${L.t(a, "selected")}" }
            column.addView(picker.view, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(column, FrameLayout.LayoutParams(-1, -1))
            root.addView(UiIcons.image(a, icon, AppTheme.onPrimary(a), 28, L.t(a, label)).apply {
                isFocusable = true; elevation = dp(6).toFloat(); background = RabtaUi.ripple(a, AppTheme.primary(a), 999)
                setOnClickListener { d.dismiss(); done(LinkedHashMap(picker.selected)) }
            }, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(24)) })
            d.setContentView(root)
            d.window?.apply { setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); setBackgroundDrawable(android.graphics.drawable.ColorDrawable(AppTheme.bg(a))) }
            d.show()
            picker.load()
        }
    }
}
