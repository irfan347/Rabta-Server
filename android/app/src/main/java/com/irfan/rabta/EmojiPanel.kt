package com.irfan.rabta

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/**
 * WhatsApp-style panel that takes the keyboard's place: Emoji (recents, every category, search by name),
 * GIF (not available yet) and Stickers. Emoji come from the phone's own font; ones it cannot draw are left out.
 */
class EmojiPanel(
    private val a: Activity,
    private val insert: (String) -> Unit,
    private val backspace: () -> Unit,
    private val sendSticker: (String) -> Unit,
    private val importSticker: () -> Unit,
    private val searchChanged: (Boolean) -> Unit,
) : LinearLayout(a) {
    private sealed class Row
    private class Header(val title: String, val category: Int) : Row()
    private class Line(val emoji: List<Pair<String, String>>, val category: Int) : Row()

    private fun dp(n: Int) = RabtaUi.dp(a, n)
    private val sub = AppTheme.sub(a)
    private val rows = mutableListOf<Row>()
    private val list = ListView(a)
    private val adapter = Adapter()
    private val categoryBar = LinearLayout(a)
    private val tabs = LinearLayout(a)
    private val pages = FrameLayout(a)
    private val emojiPage = LinearLayout(a)
    private val stickerPage = ScrollView(a)
    private val gifPage = LinearLayout(a)
    private val top = LinearLayout(a)
    private val searchRow = LinearLayout(a)
    private val searchField = EditText(a)
    private val results = LinearLayout(a)
    private var columns = 8
    private var tab = 0
    var searching = false
        private set

    init {
        orientation = VERTICAL; setBackgroundColor(AppTheme.surface(a)); isClickable = true
        // Top: search · Emoji | GIF | Sticker · backspace
        top.gravity = Gravity.CENTER_VERTICAL; top.setPadding(dp(4), dp(6), dp(4), dp(4))
        top.addView(icon("search", "Search emoji") { startSearch() }, LayoutParams(dp(48), dp(44)))
        tabs.background = RabtaUi.shape(a, AppTheme.raised(a), 999); tabs.setPadding(dp(3), dp(3), dp(3), dp(3))
        listOf("emoji" to "Emoji", "gif" to "GIF", "sticker" to "Stickers").forEachIndexed { i, (name, label) ->
            tabs.addView(UiIcons.image(a, name, sub, 22, label).apply { isFocusable = true; setOnClickListener { select(i) } }, LayoutParams(0, dp(36), 1f))
        }
        top.addView(tabs, LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        top.addView(icon("backspace", "Delete") { backspace() }, LayoutParams(dp(48), dp(44)))
        addView(top, LayoutParams(-1, -2))
        // Search: a field, then one row of matches above the keyboard.
        searchRow.gravity = Gravity.CENTER_VERTICAL; searchRow.visibility = View.GONE; searchRow.setPadding(dp(4), dp(6), dp(8), dp(4))
        searchRow.addView(icon("back", "Close search") { stopSearch() }, LayoutParams(dp(48), dp(44)))
        searchField.apply {
            hint = "Search emoji"; textSize = 15f; setTextColor(AppTheme.text(a)); setHintTextColor(sub); setSingleLine(true)
            background = RabtaUi.shape(a, AppTheme.raised(a), 999); setPadding(dp(14), dp(8), dp(14), dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { showResults(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchRow.addView(searchField, LayoutParams(0, -2, 1f))
        addView(searchRow, LayoutParams(-1, -2))
        results.visibility = View.GONE
        addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(results) }, LayoutParams(-1, -2))

        // Emoji page: one scrolling list with category headers, and the category bar under it.
        emojiPage.orientation = VERTICAL
        list.adapter = adapter; list.divider = null; list.dividerHeight = 0; list.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        list.isVerticalScrollBarEnabled = false
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, state: Int) {}
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) { rows.getOrNull(first)?.let { markCategory(category(it)) } }
        })
        emojiPage.addView(list, LayoutParams(-1, 0, 1f))
        categoryBar.gravity = Gravity.CENTER_VERTICAL; categoryBar.setPadding(dp(4), dp(2), dp(4), dp(4))
        emojiPage.addView(categoryBar, LayoutParams(-1, dp(44)))
        pages.addView(emojiPage, FrameLayout.LayoutParams(-1, -1))
        gifPage.apply {
            orientation = VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            addView(UiIcons.image(a, "gif", AppTheme.accent(a), 40, "GIF"), LayoutParams(dp(56), dp(56)))
            addView(RabtaUi.text(a, "GIFs are coming soon", 16f, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) })
            addView(RabtaUi.text(a, "Searching GIFs needs an online GIF service that Rabta doesn't have yet.", 13f, color = sub).apply { gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0) })
        }
        pages.addView(gifPage, FrameLayout.LayoutParams(-1, -1))
        stickerPage.visibility = View.GONE; stickerPage.isVerticalScrollBarEnabled = false
        pages.addView(stickerPage, FrameLayout.LayoutParams(-1, -1))
        addView(pages, LayoutParams(-1, 0, 1f))
        select(0)
        load()
    }

    private fun icon(name: String, label: String, run: () -> Unit) = UiIcons.image(a, name, sub, 24, label).apply {
        isFocusable = true; background = RabtaUi.ripple(a, Color.TRANSPARENT, 999); setOnClickListener { run() }
    }

    private fun select(index: Int) {
        tab = index
        for (i in 0 until tabs.childCount) tabs.getChildAt(i).let { v ->
            v.background = if (i == index) RabtaUi.shape(a, AppTheme.surface(a), 999) else null
            (v as ImageView).setImageDrawable(UiIcons.drawable(a, listOf("emoji", "gif", "sticker")[i], if (i == index) AppTheme.accent(a) else sub, 22))
            v.isSelected = i == index
        }
        emojiPage.visibility = if (index == 0) View.VISIBLE else View.GONE
        gifPage.visibility = if (index == 1) View.VISIBLE else View.GONE
        stickerPage.visibility = if (index == 2) View.VISIBLE else View.GONE
        if (index == 2) renderStickers()
    }

    // ── Emoji ──
    private var available: List<EmojiData.Category> = emptyList()
    private var supported: List<List<Pair<String, String>>> = emptyList()

    /** Checks once, off the main thread, which emoji this phone's font can actually draw. */
    private fun load() {
        cache?.let { available = EmojiData.categories; supported = it; rebuild(); return }
        Thread {
            val paint = Paint()
            val kept = EmojiData.categories.map { c -> c.entries.filter { (e, _) -> runCatching { paint.hasGlyph(e) }.getOrDefault(false) } }
            post { cache = kept; available = EmojiData.categories; supported = kept; rebuild() }
        }.start()
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val next = (w / dp(44)).coerceIn(7, 10)
        if (next != columns) { columns = next; post { rebuild() } }
    }
    private fun rebuild() {
        rows.clear()
        val recent = recents()
        if (recent.isNotEmpty()) { rows += Header("Recents", 0); recent.chunked(columns).forEach { rows += Line(it.map { e -> e to e }, 0) } }
        supported.forEachIndexed { i, entries ->
            if (entries.isEmpty()) return@forEachIndexed
            rows += Header(available[i].title, i + 1)
            entries.chunked(columns).forEach { rows += Line(it, i + 1) }
        }
        adapter.notifyDataSetChanged()
        categoryBar.removeAllViews()
        (listOf("clock" to "Recents") + available.map { it.icon to it.title }).forEachIndexed { i, (name, label) ->
            categoryBar.addView(UiIcons.image(a, name, sub, 20, label).apply {
                isFocusable = true; tag = i; background = RabtaUi.ripple(a, Color.TRANSPARENT, 999)
                setOnClickListener { rows.indexOfFirst { it is Header && it.category == i }.takeIf { it >= 0 }?.let { list.setSelection(it) } }
            }, LayoutParams(0, dp(40), 1f))
        }
        markCategory(0)
    }
    private fun category(row: Row) = when (row) { is Header -> row.category; is Line -> row.category }
    private fun markCategory(index: Int) {
        for (i in 0 until categoryBar.childCount) (categoryBar.getChildAt(i) as ImageView).let { v ->
            val on = v.tag == index
            v.setColorFilter(if (on) AppTheme.accent(a) else sub); v.background = if (on) RabtaUi.shape(a, AppTheme.raised(a), 999) else RabtaUi.ripple(a, Color.TRANSPARENT, 999)
        }
    }

    private fun prefs() = a.getSharedPreferences("emoji_recents", 0)
    private fun recents(): List<String> = runCatching { JSONArray(prefs().getString("list", "[]")).let { j -> (0 until j.length()).map { j.getString(it) } } }.getOrDefault(emptyList())
    private fun use(emoji: String) {
        insert(emoji)
        prefs().edit().putString("list", JSONArray((listOf(emoji) + recents()).distinct().take(columns * 3)).toString()).apply()
    }

    private fun cell(emoji: String, label: String) = TextView(a).apply {
        text = emoji; textSize = 26f; gravity = Gravity.CENTER; isFocusable = true; contentDescription = label
        background = RabtaUi.ripple(a, Color.TRANSPARENT, 12); setOnClickListener { use(emoji) }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Header) 0 else 1
        override fun isEnabled(position: Int) = false
        override fun getView(position: Int, convert: View?, parent: ViewGroup): View = when (val row = rows[position]) {
            is Header -> ((convert as? TextView) ?: RabtaUi.text(a, "", 14f, true, sub).apply { setPadding(dp(14), dp(14), dp(14), dp(6)) }).apply {
                text = row.title; androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            }
            is Line -> ((convert as? LinearLayout)?.takeIf { it.childCount == columns } ?: LinearLayout(a).apply {
                setPadding(dp(4), 0, dp(4), 0); repeat(columns) { addView(cell("", ""), LayoutParams(0, dp(48), 1f)) }
            }).apply {
                for (i in 0 until columns) (getChildAt(i) as TextView).let { v ->
                    val item = row.emoji.getOrNull(i)
                    v.text = item?.first.orEmpty(); v.contentDescription = item?.second; v.isEnabled = item != null
                    v.setOnClickListener { item?.let { use(it.first) } }
                }
            }
        }
    }

    // ── Search by name, shown as a row above the keyboard ──
    private fun startSearch() {
        searching = true; top.visibility = View.GONE; pages.visibility = View.GONE; searchRow.visibility = View.VISIBLE; results.visibility = View.VISIBLE
        searchChanged(true); showResults("")
        searchField.requestFocus()
        androidx.core.view.WindowCompat.getInsetsController(a.window, searchField).show(androidx.core.view.WindowInsetsCompat.Type.ime())
    }
    fun stopSearch() {
        if (!searching) return
        searching = false; searchField.setText(""); searchRow.visibility = View.GONE; results.visibility = View.GONE
        top.visibility = View.VISIBLE; pages.visibility = View.VISIBLE; searchChanged(false)
    }
    private fun showResults(query: String) {
        results.removeAllViews()
        val q = query.trim().lowercase()
        val found = if (q.isEmpty()) recents().map { it to it } else supported.flatten().filter { it.second.contains(q) }.take(60)
        if (found.isEmpty()) results.addView(RabtaUi.text(a, if (q.isEmpty()) "Type to search emoji" else "No emoji found", 13f, color = sub).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })
        found.forEach { (e, name) -> results.addView(cell(e, name).apply { setOnClickListener { use(e) } }, LayoutParams(dp(46), dp(48))) }
    }

    // ── Stickers: real recents, favourites, Rabta's own and your imported ones ──
    private fun renderStickers() {
        val col = LinearLayout(a).apply { orientation = VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(12)) }
        fun section(title: String, items: List<String>, withImport: Boolean = false) {
            if (items.isEmpty() && !withImport) return
            col.addView(RabtaUi.text(a, title, 14f, true, sub).apply { setPadding(dp(6), dp(10), 0, dp(6)) })
            val grid = GridLayout(a).apply { columnCount = 4 }
            val size = (width.takeIf { it > 0 } ?: a.resources.displayMetrics.widthPixels - dp(16)) / 4
            items.forEach { body ->
                grid.addView(ImageView(a).apply {
                    contentDescription = Stickers.label(body); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); isFocusable = true
                    background = RabtaUi.ripple(a, Color.TRANSPARENT, 16)
                    if (Stickers.valid(body)) setImageDrawable(Stickers.drawable(body)) else runCatching { setImageURI(android.net.Uri.fromFile(Attachments.file(a, body))) }
                    setOnClickListener { Stickers.remember(a, body); sendSticker(body) }
                }, GridLayout.LayoutParams().apply { width = size; height = size })
            }
            if (withImport) grid.addView(UiIcons.image(a, "plus", AppTheme.accent(a), 28, "Import sticker").apply {
                isFocusable = true; background = RabtaUi.ripple(a, AppTheme.raised(a), 16); setOnClickListener { importSticker() }
            }, GridLayout.LayoutParams().apply { width = size - dp(16); height = size - dp(16); setMargins(dp(8), dp(8), dp(8), dp(8)) })
            col.addView(grid)
        }
        section("Recent", Stickers.items(a, "recent"))
        section("Favorites", Stickers.items(a, "favorites"))
        section("Rabta", Stickers.bundled(), withImport = true)
        stickerPage.removeAllViews(); stickerPage.addView(col)
    }

    companion object { private var cache: List<List<Pair<String, String>>>? = null }
}
