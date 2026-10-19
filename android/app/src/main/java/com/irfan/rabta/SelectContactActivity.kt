package com.irfan.rabta

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The chats screen's "+": everyone you can write to, plus New group, New contact and a chat with yourself. Search is
 * always open. People you talk to most come first; tap writes to them, the round buttons call, holding offers more.
 */
class SelectContactActivity : ThemedActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var db: Db
    private lateinit var list: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var search: EditText
    private var contacts: List<Db.Contact> = emptyList()
    private var lastChat: Map<String, Long> = emptyMap()
    private var query = ""
    private var version = 0
    private val render = Runnable { render() }
    private fun dp(n: Int) = RabtaUi.dp(this, n)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!Session.loggedIn) { finish(); return }
        db = Db.get(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(AppTheme.bg(this@SelectContactActivity)); isFocusableInTouchMode = true }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(8), dp(8), dp(4)) }
        head.addView(UiIcons.image(this, "back", AppTheme.text(this), 24, L.t(this, "Back")).apply {
            isFocusable = true; background = RabtaUi.ripple(this@SelectContactActivity, Color.TRANSPARENT, 999); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        titles.addView(RabtaUi.text(this, "Select contact", 20f, true).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
        subtitle = RabtaUi.text(this, "", 13f, color = AppTheme.sub(this))
        titles.addView(subtitle)
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(UiIcons.image(this, "qr", AppTheme.text(this), 22, L.t(this, "QR Connect")).apply {
            isFocusable = true; background = RabtaUi.ripple(this@SelectContactActivity, Color.TRANSPARENT, 999)
            setOnClickListener { startActivity(Intent(this@SelectContactActivity, InstantConnectActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(head)

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(4), 0); background = RabtaUi.shape(this@SelectContactActivity, AppTheme.raised(this@SelectContactActivity), 24) }
        bar.addView(UiIcons.image(this, "search", AppTheme.sub(this), 20, "Search"), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
        search = EditText(this).apply {
            hint = L.t(this@SelectContactActivity, "Search name, @username or Rabta ID"); textSize = 15f; setSingleLine(true); background = null
            setTextColor(AppTheme.text(this@SelectContactActivity)); setHintTextColor(AppTheme.sub(this@SelectContactActivity)); minHeight = dp(48)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH; contentDescription = hint
        }
        bar.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = UiIcons.image(this, "close", AppTheme.sub(this), 18, L.t(this, "Clear search")).apply {
            visibility = View.GONE; isFocusable = true; background = RabtaUi.ripple(this@SelectContactActivity, Color.TRANSPARENT, 999); setOnClickListener { search.setText("") }
        }
        bar.addView(clear, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                query = s?.toString().orEmpty().trim(); clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                handler.removeCallbacks(render); handler.postDelayed(render, 120)
            }
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(24)) }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); if (::db.isInitialized) load() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun load() {
        val v = ++version
        Thread {
            val result = runCatching {
                val all = db.contacts().filter { !People.isSelf(it.peerId) && !Privacy.blocked(this, it.peerId) }
                all to db.conversations().filter { !it.isGroup }.associate { it.peerId to it.lastTime }
            }
            runOnUiThread {
                if (isDestroyed || v != version) return@runOnUiThread
                result.onSuccess { (all, recent) -> contacts = all; lastChat = recent }
                subtitle.text = if (contacts.size == 1) "1 ${L.t(this, "contact")}" else "${contacts.size} ${L.t(this, "contacts")}"
                render()
            }
        }.start()
    }

    private fun render() {
        list.removeAllViews()
        val q = query.removePrefix("@")
        if (q.isEmpty()) {
            list.addView(action("group_add", "New group", "Choose people, then name the group") { startActivity(Intent(this, NewGroupActivity::class.java)) })
            list.addView(action("person_add", "New contact", "Find someone by @username or Rabta ID", trailing = "qr") { newContact() })
            list.addView(self())
        }
        val hits = if (q.isEmpty()) contacts else contacts.filter { it.shownName.contains(q, true) || it.name.contains(q, true) || it.username.contains(q, true) || it.peerId.contains(q, true) }
        if (q.isNotEmpty() && Regex("[A-Za-z0-9_]{3,20}").matches(q) && hits.none { it.username.equals(q, true) || it.peerId.equals(q, true) })
            list.addView(action("person_add", "Find @$q on Rabta", "Look up this username or Rabta ID") { newContact(q) })
        if (contacts.isEmpty() && q.isEmpty()) {
            list.addView(RabtaUi.empty(this, "people", "No contacts yet", "People you connect with by username, QR code or Nearby are saved here."))
            return
        }
        if (hits.isEmpty()) { list.addView(RabtaUi.text(this, "No contacts match “$query”.", 14f, color = AppTheme.sub(this)).apply { setPadding(dp(12), dp(16), dp(12), dp(16)) }); return }
        val frequent = if (q.isEmpty()) hits.filter { (lastChat[it.peerId] ?: 0) > 0 }.sortedByDescending { lastChat[it.peerId] }.take(5) else emptyList()
        if (frequent.size >= 2 && hits.size > 5) {
            list.addView(RabtaUi.section(this, "Frequently contacted").apply { setPadding(dp(12), dp(14), 0, dp(6)) })
            frequent.forEach { list.addView(contactRow(it)) }
        }
        list.addView(RabtaUi.section(this, if (q.isEmpty()) "Contacts on Rabta" else "Results").apply { setPadding(dp(12), dp(14), 0, dp(6)) })
        hits.forEach { list.addView(contactRow(it)) }
    }

    /** Typed into search: looked up at once (the answer shows as a short note); otherwise the find sheet opens. */
    private fun newContact(prefill: String = "") {
        if (prefill.isEmpty()) { People.find(this, "New contact") { id, name, _ -> openChat(id, name) }; return }
        People.lookup(this, prefill, { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_SHORT).show() }, {}) { id, name, _ -> openChat(id, name) }
    }

    private fun openChat(id: String, name: String) { People.openChat(this, id, name); finish() }

    /** A round accent icon, a title and a quiet line: New group, New contact. */
    private fun action(icon: String, title: String, detail: String, trailing: String? = null, run: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(68); setPadding(dp(10), dp(8), dp(4), dp(8)); isFocusable = true
        background = RabtaUi.ripple(this@SelectContactActivity, Color.TRANSPARENT, 16); setOnClickListener { run() }; contentDescription = "${L.t(this@SelectContactActivity, title)}. ${L.t(this@SelectContactActivity, detail)}"
        addView(UiIcons.image(this@SelectContactActivity, icon, AppTheme.onPrimary(this@SelectContactActivity), 24, "").apply {
            background = RabtaUi.shape(this@SelectContactActivity, AppTheme.primary(this@SelectContactActivity), 999); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val texts = LinearLayout(this@SelectContactActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        texts.addView(RabtaUi.text(this@SelectContactActivity, title, 16f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        texts.addView(RabtaUi.text(this@SelectContactActivity, detail, 13f, color = AppTheme.sub(this@SelectContactActivity)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing == "qr") addView(UiIcons.image(this@SelectContactActivity, "qr", AppTheme.accent(this@SelectContactActivity), 22, L.t(this@SelectContactActivity, "Add by QR code")).apply {
            isFocusable = true; background = RabtaUi.ripple(this@SelectContactActivity, Color.TRANSPARENT, 999)
            setOnClickListener { startActivity(Intent(this@SelectContactActivity, InstantConnectActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    /** "Message yourself": notes, photos and links to keep, saved on this phone. */
    private fun self(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(68); setPadding(dp(10), dp(8), dp(12), dp(8)); isFocusable = true
        background = RabtaUi.ripple(this@SelectContactActivity, Color.TRANSPARENT, 16)
        val name = People.selfName(this@SelectContactActivity)
        contentDescription = "$name. ${L.t(this@SelectContactActivity, "Message yourself")}"
        setOnClickListener { openChat(Session.id, Session.name) }
        addView(People.avatar(this@SelectContactActivity, Session.id, Session.name, dp(48)), LinearLayout.LayoutParams(dp(48), dp(48)))
        val texts = LinearLayout(this@SelectContactActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        texts.addView(RabtaUi.text(this@SelectContactActivity, name, 16f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        texts.addView(RabtaUi.text(this@SelectContactActivity, "Message yourself", 13f, color = AppTheme.sub(this@SelectContactActivity)))
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun contactRow(c: Db.Contact): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(66); setPadding(dp(10), dp(8), dp(2), dp(8)); isFocusable = true
        background = RabtaUi.ripple(this@SelectContactActivity, Color.TRANSPARENT, 16)
        val name = c.shownName
        contentDescription = "$name${if (c.username.isNotBlank()) ", @${c.username}" else ""}. ${L.t(this@SelectContactActivity, "Tap to message, hold for more")}"
        setOnClickListener { openChat(c.peerId, c.name) }
        setOnLongClickListener { more(c); true }
        addView(People.avatar(this@SelectContactActivity, c.peerId, name, dp(48)), LinearLayout.LayoutParams(dp(48), dp(48)))
        val texts = LinearLayout(this@SelectContactActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(4), 0) }
        texts.addView(RabtaUi.text(this@SelectContactActivity, name, 16f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        // Your name for them first; their own Rabta name stays visible underneath.
        val line = listOfNotNull(
            c.name.takeIf { c.nickname.isNotBlank() && it != c.nickname }?.let { "~ $it" },
            c.username.takeIf { it.isNotBlank() }?.let { "@$it" },
            if (MeshBridge.snapshot().peers.any { it.keyMatched && it.id == c.peerId }) L.t(this@SelectContactActivity, "Nearby now") else null
        ).joinToString(" · ").ifBlank { L.t(this@SelectContactActivity, "Rabta contact") }
        texts.addView(RabtaUi.text(this@SelectContactActivity, line, 13f, color = AppTheme.sub(this@SelectContactActivity)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        addView(round("phone", "${L.t(this@SelectContactActivity, "Voice call")} $name") { People.call(this@SelectContactActivity, c.peerId, c.name, false) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(round("video", "${L.t(this@SelectContactActivity, "Video call")} $name") { People.call(this@SelectContactActivity, c.peerId, c.name, true) }, LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    private fun round(icon: String, label: String, run: () -> Unit) = UiIcons.image(this, icon, AppTheme.accent(this), 22, label).apply {
        isFocusable = true; background = RabtaUi.ripple(this@SelectContactActivity, Color.TRANSPARENT, 999); setOnClickListener { run() }
    }

    private fun more(c: Db.Contact) = RabtaUi.sheet(this, c.shownName, listOf(
        RabtaUi.SheetAction("Message") { openChat(c.peerId, c.name) },
        RabtaUi.SheetAction("Voice call") { People.call(this, c.peerId, c.name, false) },
        RabtaUi.SheetAction("Video call") { People.call(this, c.peerId, c.name, true) },
        RabtaUi.SheetAction(if (c.nickname.isBlank()) "Add your name for them" else "Edit name", "Only you see it") { People.editName(this, c.peerId, c.name) { load() } },
        RabtaUi.SheetAction("Contact info") { startActivity(Intent(this, ContactProfileActivity::class.java).putExtra("peerId", c.peerId).putExtra("peerName", c.name)) },
        RabtaUi.SheetAction("Delete contact", "Your chat with ${c.shownName}, if any, stays") {
            android.app.AlertDialog.Builder(this).setTitle("Delete ${c.shownName}?").setMessage("${c.shownName} is deleted from your contacts. Your chat with them, if any, stays in Chats.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> Thread { runCatching { db.removeContact(c.peerId) }; runOnUiThread { load() } }.start() }.show()
        },
    ))
}
