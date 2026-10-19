package com.irfan.rabta

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * New group in two steps: choose the people (search, most contacted first, round ticks, chosen faces on top), then
 * name the group while its avatar previews the name. The group is created on the server and its chat opens.
 */
class NewGroupActivity : ThemedActivity() {
    private lateinit var picker: PeoplePicker
    private lateinit var stepTitle: TextView
    private lateinit var stepSubtitle: TextView
    private lateinit var content: FrameLayout
    private lateinit var fab: ImageView
    private var naming = false
    private var creating = false
    private var nameField: EditText? = null
    private fun dp(n: Int) = RabtaUi.dp(this, n)
    private val backStep = object : androidx.activity.OnBackPressedCallback(false) { override fun handleOnBackPressed() { showMembers() } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!Session.loggedIn) { finish(); return }
        val root = FrameLayout(this).apply { setBackgroundColor(AppTheme.bg(this@NewGroupActivity)); isFocusableInTouchMode = true }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(8), dp(16), dp(4)) }
        head.addView(UiIcons.image(this, "back", AppTheme.text(this), 24, L.t(this, "Back")).apply {
            isFocusable = true; background = RabtaUi.ripple(this@NewGroupActivity, Color.TRANSPARENT, 999); setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        stepTitle = RabtaUi.text(this, "New group", 20f, true).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }
        stepSubtitle = RabtaUi.text(this, "", 13f, color = AppTheme.sub(this)).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        titles.addView(stepTitle); titles.addView(stepSubtitle)
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(head)
        content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        fab = UiIcons.image(this, "forward", AppTheme.onPrimary(this), 26, L.t(this, "Next")).apply {
            isFocusable = true; elevation = dp(6).toFloat(); background = RabtaUi.ripple(this@NewGroupActivity, AppTheme.primary(this@NewGroupActivity), 999)
            setOnClickListener { if (naming) create() else showName() }
        }
        root.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(24)) })
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, backStep)
        // A group holds up to 50 people, you included.
        picker = PeoplePicker(this, max = 49, limitNote = "A group can have up to 50 people, you included") { n -> membersChanged(n) }
        showMembers()
        picker.load()
    }

    private fun membersChanged(n: Int) {
        if (naming) return
        stepSubtitle.text = if (n == 0) L.t(this, "Add members") else "$n ${L.t(this, "of")} 49 ${L.t(this, "selected")}"
        fab.alpha = if (n == 0) 0.45f else 1f
        fab.contentDescription = if (n == 0) L.t(this, "Choose at least one person") else L.t(this, "Next")
    }

    private fun showMembers() {
        naming = false; backStep.isEnabled = false
        stepTitle.text = L.t(this, "New group")
        content.removeAllViews(); (picker.view.parent as? android.view.ViewGroup)?.removeView(picker.view)
        content.addView(picker.view, FrameLayout.LayoutParams(-1, -1))
        UiIcons.drawable(this, "forward", AppTheme.onPrimary(this), 26).let { fab.setImageDrawable(it) }
        membersChanged(picker.selected.size)
    }

    private fun showName() {
        if (picker.selected.isEmpty()) { Toast.makeText(this, "Choose at least one person", Toast.LENGTH_SHORT).show(); return }
        naming = true; backStep.isEnabled = true
        hideKeyboard()
        stepTitle.text = L.t(this, "Name your group")
        stepSubtitle.text = "${picker.selected.size + 1} ${L.t(this, "members, you included")}"
        fab.setImageDrawable(UiIcons.drawable(this, "check", AppTheme.onPrimary(this), 28)); fab.contentDescription = L.t(this, "Create group"); fab.alpha = 1f
        content.removeAllViews()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        // The group's face previews its name as you type.
        val preview = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 40f; setTextColor(AppTheme.onPrimary(this@NewGroupActivity)); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = RabtaUi.shape(this@NewGroupActivity, AppTheme.primary(this@NewGroupActivity), 32); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        UiIcons.set(preview, "people", AppTheme.onPrimary(this), 44, "")
        body.addView(preview, LinearLayout.LayoutParams(dp(104), dp(104)))
        val field = EditText(this).apply {
            hint = L.t(this@NewGroupActivity, "Group name"); textSize = 18f; setSingleLine(true); contentDescription = hint
            setTextColor(AppTheme.text(this@NewGroupActivity)); setHintTextColor(AppTheme.sub(this@NewGroupActivity))
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = RabtaUi.shape(this@NewGroupActivity, AppTheme.surface(this@NewGroupActivity), 18, AppTheme.line(this@NewGroupActivity))
            setPadding(dp(16), dp(14), dp(16), dp(14)); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ -> if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { create(); true } else false }
        }
        val counter = RabtaUi.text(this, "0/40", 12f, color = AppTheme.sub(this)).apply { gravity = Gravity.END; setPadding(0, dp(6), dp(6), 0) }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                val name = s?.toString().orEmpty().trim(); counter.text = "${s?.length ?: 0}/40"
                val first = name.filter { it.isLetterOrDigit() }.take(1).uppercase()
                if (first.isEmpty()) UiIcons.set(preview, "people", AppTheme.onPrimary(this@NewGroupActivity), 44, "") else preview.text = first
            }
        })
        nameField = field
        body.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        body.addView(counter, LinearLayout.LayoutParams(-1, -2))
        body.addView(RabtaUi.section(this, "Members").apply { setPadding(0, dp(18), 0, dp(8)) }, LinearLayout.LayoutParams(-1, -2))
        val faces = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        (listOf(Session.id to People.selfName(this)) + picker.selected.toList()).forEach { (id, name) ->
            faces.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), 0, dp(2), 0)
                addView(People.avatar(this@NewGroupActivity, id, name, dp(48)), LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(RabtaUi.text(this@NewGroupActivity, name.substringBefore(' ').take(12), 12f).apply { gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(dp(64), -2))
            })
        }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(faces) }, LinearLayout.LayoutParams(-1, -2))
        content.addView(android.widget.ScrollView(this).apply { addView(body) }, FrameLayout.LayoutParams(-1, -1))
        field.requestFocus()
        androidx.core.view.WindowCompat.getInsetsController(window, field).show(androidx.core.view.WindowInsetsCompat.Type.ime())
    }

    private fun hideKeyboard() {
        currentFocus?.clearFocus()
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
    }

    /** The server creates the group (it keeps members who have Rabta accounts); its chat then opens. */
    private fun create() {
        if (creating) return
        val name = nameField?.text?.toString().orEmpty().trim()
        if (name.isEmpty()) { nameField?.error = L.t(this, "Give the group a name"); return }
        if (!WsClient.connected) { Toast.makeText(this, "Creating a group needs internet", Toast.LENGTH_SHORT).show(); return }
        creating = true; fab.alpha = 0.45f; stepSubtitle.text = L.t(this, "Creating group…")
        val members = listOf(Session.id) + picker.selected.keys
        WsClient.createGroup(Session.serverUrl, Session.token, name, members) { ok, msg, data ->
            if (isDestroyed) return@createGroup
            val id = data?.optString("id").orEmpty()
            if (!ok || id.isEmpty()) {
                creating = false; fab.alpha = 1f; stepSubtitle.text = "${picker.selected.size + 1} ${L.t(this, "members, you included")}"
                Toast.makeText(this, msg.ifBlank { "Group could not be created. Try again." }, Toast.LENGTH_LONG).show(); return@createGroup
            }
            val stored = data?.optJSONArray("members")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: members
            Thread {
                runCatching { Db.get(this).run { saveGroup(id, name, stored); upsertConversation(id, name, "New group!", System.currentTimeMillis(), false, true) } }
                runOnUiThread { People.openChat(this, id, name, true); finish() }
            }.start()
        }
    }
}
