package com.irfan.rabta

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * WhatsApp-style "Contact info" for one person: big photo, name, @username, Rabta ID, call/video/search,
 * the media actually shared in this chat, offline security, block and report. Everything shown is real.
 */
class ContactProfileActivity : ThemedActivity() {
    private lateinit var peerId: String
    private lateinit var peerName: String
    private lateinit var avatar: FrameLayout
    private lateinit var username: TextView
    private lateinit var presence: TextView
    private lateinit var mediaBox: LinearLayout
    private lateinit var safety: LinearLayout
    private var showAll = false
    private fun dp(n: Int) = RabtaUi.dp(this, n)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        peerId = intent.getStringExtra("peerId").orEmpty(); peerName = intent.getStringExtra("peerName").orEmpty().ifBlank { "Rabta contact" }
        if (peerId.isBlank()) { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(AppTheme.bg(this@ContactProfileActivity)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), 0) }
        head.addView(RabtaUi.iconButton(this, "back", "Back") { finish() }.apply { UiIcons.set(this, "back", AppTheme.text(this@ContactProfileActivity), 24, "Back") })
        head.addView(RabtaUi.text(this, "Contact info", 18f, true).apply { setPadding(dp(4), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(head)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(4), dp(16), dp(24)) }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)

        // Photo in a lime ring; tap to see it full screen.
        avatar = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(3), AppTheme.accent(this@ContactProfileActivity)) }
            setPadding(dp(5), dp(5), dp(5), dp(5)); isFocusable = true; contentDescription = "$peerName profile photo"
            addView(RabtaUi.text(this@ContactProfileActivity, peerName.filter { it.isLetterOrDigit() }.take(1).uppercase().ifEmpty { "R" }, 48f, true, Color.WHITE).apply {
                gravity = Gravity.CENTER; includeFontPadding = false; background = RabtaUi.shape(this@ContactProfileActivity, 0xFF325E71.toInt(), 999)
            }, FrameLayout.LayoutParams(-1, -1))
        }
        body.addView(avatar, LinearLayout.LayoutParams(dp(140), dp(140)).apply { topMargin = dp(8) })
        ProfileHelper.loadIfNeeded(peerId, Session.serverUrl, Session.token) { bmp ->
            if (bmp == null || isDestroyed) return@loadIfNeeded
            avatar.addView(ImageView(this).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = RabtaUi.shape(this@ContactProfileActivity, AppTheme.raised(this@ContactProfileActivity), 999); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-1, -1))
            avatar.setOnClickListener { startActivity(Intent(this, ProfilePhotoActivity::class.java).putExtra("peerId", peerId).putExtra("peerName", peerName)) }
        }
        body.addView(RabtaUi.text(this, peerName, 26f, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0); maxLines = 2 })
        username = RabtaUi.text(this, "", 15f, color = AppTheme.sub(this)).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        body.addView(username)
        presence = RabtaUi.text(this, "", 13f, color = AppTheme.sub(this)).apply { gravity = Gravity.CENTER; visibility = View.GONE; setPadding(0, dp(2), 0, 0) }
        body.addView(presence)
        body.addView(RabtaUi.text(this, "Rabta ID · $peerId", 13f, color = AppTheme.sub(this)).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)); isFocusable = true
            background = RabtaUi.ripple(this@ContactProfileActivity, Color.TRANSPARENT, 16); contentDescription = "Rabta ID $peerId. Tap to copy"
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rabta ID", peerId))
                Toast.makeText(this@ContactProfileActivity, "Rabta ID copied", Toast.LENGTH_SHORT).show()
            }
        })

        // Voice · Video · Search, as tiles.
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) }
        actions.addView(tile("phone", "Voice") { act("voice") }, LinearLayout.LayoutParams(0, dp(80), 1f).apply { marginEnd = dp(10) })
        actions.addView(tile("video", "Video") { act("video") }, LinearLayout.LayoutParams(0, dp(80), 1f).apply { marginEnd = dp(10) })
        actions.addView(tile("search", "Search") { act("search") }, LinearLayout.LayoutParams(0, dp(80), 1f))
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))

        mediaBox = group(); body.addView(mediaBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        safety = group(); body.addView(safety, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val danger = group(); body.addView(danger, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val blockRow = row(danger, "block", AppTheme.error(this), "", null, AppTheme.error(this)) { MessageActions.toggleBlock(this, peerId) { renderBlock(danger) } }
        blockRow.tag = "block"
        divider(danger)
        row(danger, "report", AppTheme.error(this), "Report $peerName", "Send a report to the server administrator", AppTheme.error(this)) { MessageActions.report(this, peerId) }
        renderBlock(danger)
        loadMedia(); loadSafety(); loadAccount()
    }

    /** Calls and search happen in the chat: hand the request back (or open the chat when started elsewhere). */
    private fun act(action: String) {
        if (callingActivity != null) { setResult(RESULT_OK, Intent().putExtra("action", action)); finish(); return }
        val open = Intent(this, ChatActivity::class.java).putExtra("peerId", peerId).putExtra("peerName", peerName)
        if (action != "search") open.putExtra("requestedCallKind", action)
        startActivity(open); finish()
    }

    private fun group() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = RabtaUi.shape(this@ContactProfileActivity, AppTheme.surface(this@ContactProfileActivity), 20); clipToOutline = true
    }
    private fun divider(parent: LinearLayout) = parent.addView(View(this).apply { setBackgroundColor(AppTheme.line(this@ContactProfileActivity)) },
        LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(64) })
    private fun tile(icon: String, label: String, run: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; contentDescription = label
        background = RabtaUi.ripple(this@ContactProfileActivity, AppTheme.surface(this@ContactProfileActivity), 18); setOnClickListener { run() }
        addView(UiIcons.image(this@ContactProfileActivity, icon, AppTheme.accent(this@ContactProfileActivity), 26, label).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(-2, dp(30)))
        addView(RabtaUi.text(this@ContactProfileActivity, label, 13f, true).apply { setPadding(0, dp(6), 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
    }
    private fun row(parent: LinearLayout, icon: String, iconColor: Int, title: String, detail: String?, titleColor: Int = AppTheme.text(this), run: () -> Unit): LinearLayout {
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60); setPadding(dp(18), dp(10), dp(14), dp(10)); isFocusable = true
            background = RabtaUi.ripple(this@ContactProfileActivity, AppTheme.surface(this@ContactProfileActivity), 0); setOnClickListener { run() }
        }
        r.addView(UiIcons.image(this, icon, iconColor, 22, title).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(18) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(RabtaUi.text(this, title, 16f, true, titleColor))
        texts.addView(RabtaUi.text(this, detail.orEmpty(), 12f, color = AppTheme.sub(this)).apply { visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE; maxLines = 2 })
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(r)
        return r
    }
    private fun renderBlock(parent: LinearLayout) {
        val r = (0 until parent.childCount).map { parent.getChildAt(it) }.firstOrNull { it.tag == "block" } as? LinearLayout ?: return
        val blocked = Privacy.blocked(this, peerId)
        val texts = r.getChildAt(1) as LinearLayout
        (texts.getChildAt(0) as TextView).text = if (blocked) "Unblock $peerName" else "Block $peerName"
        (texts.getChildAt(1) as TextView).apply { text = if (blocked) "You won't get messages, calls or status from this contact" else "Stop messages, calls and status from this contact"; visibility = View.VISIBLE }
        r.contentDescription = if (blocked) "Unblock $peerName" else "Block $peerName"
    }

    /** Photos, videos and files actually saved in this chat. */
    private fun loadMedia() {
        mediaBox.removeAllViews()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(14), dp(14), dp(10)); isFocusable = true }
        val title = RabtaUi.text(this, "Media, links and docs", 15f, true)
        val count = RabtaUi.text(this, "", 14f, color = AppTheme.sub(this))
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); header.addView(count)
        header.addView(RabtaUi.text(this, "›", 20f, color = AppTheme.sub(this)).apply { setPadding(dp(8), 0, 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        mediaBox.addView(header)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(14)) }
        mediaBox.addView(content)
        content.addView(RabtaUi.loading(this, "Loading media…"))
        Thread {
            val result = runCatching { Db.get(this).mediaCount(peerId) to Db.get(this).media(peerId).map { it to thumb(it) } }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                content.removeAllViews()
                result.onFailure { content.addView(RabtaUi.text(this, "Media could not be loaded", 13f, color = AppTheme.sub(this))) }
                    .onSuccess { (total, items) ->
                        count.text = total.toString()
                        if (items.isEmpty()) { content.addView(RabtaUi.text(this, "Photos, videos and files you share here will appear here.", 13f, color = AppTheme.sub(this))); return@onSuccess }
                        header.setOnClickListener { showAll = !showAll; renderMedia(content, items) }
                        header.contentDescription = "Media, links and docs, $total. ${if (showAll) "Show fewer" else "Show all"}"
                        renderMedia(content, items)
                    }
            }
        }.start()
    }
    private fun renderMedia(content: LinearLayout, items: List<Pair<Db.Message, Bitmap?>>) {
        content.removeAllViews()
        val size = dp(76)
        if (!showAll) {
            val strip = LinearLayout(this)
            items.take(12).forEach { strip.addView(mediaTile(it, size), LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }) }
            content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        } else {
            val grid = GridLayout(this).apply { columnCount = 4 }
            val cell = (resources.displayMetrics.widthPixels - dp(32 + 28) - dp(6) * 3) / 4
            items.forEach { grid.addView(mediaTile(it, cell), GridLayout.LayoutParams().apply { width = cell; height = cell; setMargins(0, 0, dp(6), dp(6)) }) }
            content.addView(grid)
        }
    }
    private fun mediaTile(item: Pair<Db.Message, Bitmap?>, size: Int): View {
        val (m, bmp) = item
        return FrameLayout(this).apply {
            background = RabtaUi.shape(this@ContactProfileActivity, AppTheme.raised(this@ContactProfileActivity), 12); clipToOutline = true; isFocusable = true
            contentDescription = when (m.msgType) { "image" -> "Photo"; "video" -> "Video ${Attachments.name(m.body)}"; else -> "File ${Attachments.name(m.body)}" }
            if (bmp != null) addView(ImageView(this@ContactProfileActivity).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
            else {
                val col = LinearLayout(this@ContactProfileActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)) }
                col.addView(UiIcons.image(this@ContactProfileActivity, if (m.msgType == "video") "video" else "attach", AppTheme.accent(this@ContactProfileActivity), 24), LinearLayout.LayoutParams(-2, dp(28)))
                col.addView(RabtaUi.text(this@ContactProfileActivity, Attachments.name(m.body), 10f, color = AppTheme.sub(this@ContactProfileActivity)).apply { maxLines = 2; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(col, FrameLayout.LayoutParams(-1, -1))
            }
            setOnClickListener { if (m.msgType == "image") PhotoViewer.show(this@ContactProfileActivity, MediaHelper.base64ToBitmap(m.body), if (m.mine) "You" else peerName) else Attachments.open(this@ContactProfileActivity, m.body) }
        }
    }
    private fun thumb(m: Db.Message): Bitmap? = if (m.msgType != "image") null else runCatching {
        val bytes = Base64.decode(m.body, Base64.DEFAULT)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        o.inSampleSize = 1; while (maxOf(o.outWidth, o.outHeight) / o.inSampleSize > 320) o.inSampleSize *= 2
        o.inJustDecodeBounds = false; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    }.getOrNull()

    private fun loadSafety() {
        safety.removeAllViews()
        val r = row(safety, "privacy", AppTheme.teal(this), "Offline security", "Checking this contact's key…") { MeshSecurity.showContact(this, peerId) }
        Thread {
            val key = runCatching { MeshSecurity(this, Session.id).key(peerId) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                ((r.getChildAt(1) as LinearLayout).getChildAt(1) as TextView).text = key?.let { "Code " + MeshCrypto.fingerprint(it).take(19) + "… · Tap to verify" } ?: "No key yet · Messages wait safely until one is available"
            }
        }.start()
    }

    /** @username and presence come from the server; offline the screen still works without them. */
    private fun loadAccount() {
        Thread {
            val result = runCatching { FeatureApi.request("/api/user/id/$peerId") }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { o ->
                    o.optString("username").takeIf { it.isNotBlank() }?.let { username.text = "@$it"; username.visibility = View.VISIBLE }
                    presence.visibility = View.VISIBLE
                    if (o.optBoolean("online")) { presence.text = "Online"; presence.setTextColor(AppTheme.teal(this)) }
                    else presence.text = o.optLong("lastSeen").takeIf { it > 0 }?.let { "Last seen " + android.text.format.DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), 60000) } ?: "Offline"
                }
            }
        }.start()
    }
}
