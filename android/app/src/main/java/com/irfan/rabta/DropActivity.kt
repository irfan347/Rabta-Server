package com.irfan.rabta

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.irfan.rabta.DropEngine.State
import com.irfan.rabta.DropEngine.Transfer

/** Rabta Drop: send files to a verified nearby contact, approve incoming files and follow every transfer. */
class DropActivity : ThemedActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var linkStatus: TextView
    private lateinit var incoming: LinearLayout
    private lateinit var transfers: LinearLayout
    private var visible = false
    private var owner = ""
    private val refresher: () -> Unit = { if (visible) render() }
    private val tick = object : Runnable { override fun run() { if (visible) { render(); handler.postDelayed(this, 1000) } } }
    private fun dp(n: Int) = RabtaUi.dp(this, n)
    private fun text(value: String, size: Float = 14f, strong: Boolean = false, color: Int = AppTheme.text(this)) = RabtaUi.text(this, value, size, strong, color)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!Session.loggedIn) { finish(); return }
        owner = Session.id
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(AppTheme.bg(this@DropActivity)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(20), dp(4)) }
        head.addView(RabtaUi.iconButton(this, "back", "Back") { onBackPressedDispatcher.onBackPressed() }.apply { UiIcons.set(this, "back", AppTheme.text(this@DropActivity), label = "Back") })
        head.addView(text("Rabta Drop", 24f, true), LinearLayout.LayoutParams(0, -2, 1f)); root.addView(head)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(24)) }
        body.addView(text("Share photos, videos and documents with a contact next to you — no internet needed.", 14f, color = AppTheme.sub(this)).apply { setPadding(0, 0, 0, dp(12)) })
        linkStatus = text("", 13f, color = AppTheme.sub(this)).apply { tag = "drop-link"; setPadding(0, 0, 0, dp(8)) }
        body.addView(linkStatus)

        incoming = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body.addView(incoming)

        val send = RabtaUi.card(this)
        send.addView(text("Send a file", 17f, true))
        send.addView(text("Choose what to share, then pick a nearby contact whose security key is verified.", 13f, color = AppTheme.sub(this)).apply { setPadding(0, dp(4), 0, dp(12)) })
        val kinds = listOf(
            Triple("Photo", "status", arrayOf("image/*")), Triple("Video", "video", arrayOf("video/*")),
            Triple("PDF", "text", arrayOf("application/pdf")), Triple("Document", "backup", DropProtocol.pickerTypes.filter { !it.startsWith("image/") && !it.startsWith("video/") && it != "application/pdf" }.toTypedArray()))
        kinds.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEachIndexed { i, (label, icon, types) -> row.addView(kindButton(label, icon) { pick(types) }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) marginEnd = dp(8) }) }
            send.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        send.addView(text("End-to-end encrypted between both phones. Files are checked with SHA-256 before they are saved.", 12f, color = AppTheme.sub(this)).apply { setPadding(0, dp(4), 0, 0) })
        body.addView(send)

        body.addView(RabtaUi.section(this, "Transfers"))
        transfers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(transfers)
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        RabtaUi.insets(root)
    }

    override fun onResume() { super.onResume(); visible = true; RabtaDrop.listen(refresher); getSystemService(android.app.NotificationManager::class.java).cancel(RabtaDrop.NOTIFICATION); handler.post(tick) }
    override fun onPause() { visible = false; RabtaDrop.unlisten(refresher); handler.removeCallbacks(tick); super.onPause() }

    private fun kindButton(label: String, icon: String, action: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56); setPadding(dp(14), dp(12), dp(14), dp(12))
        background = RabtaUi.ripple(this@DropActivity, AppTheme.raised(this@DropActivity), 16); isFocusable = true; contentDescription = "Send $label"
        addView(TextView(this@DropActivity).apply { UiIcons.set(this, icon, AppTheme.accent(this@DropActivity), 20, label); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
        addView(text(label, 15f, true))
        setOnClickListener { action() }
    }

    private fun verifiedPeers() = MeshBridge.snapshot().peers.filter { it.keyMatched && it.id.isNotEmpty() }.distinctBy { it.id }

    private fun pick(types: Array<String>) {
        if (RabtaDrop.engine == null) { needNearby(); return }
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (types.size == 1) types[0] else "*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, types).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), PICK)
        } catch (_: ActivityNotFoundException) { Toast.makeText(this, "No file picker is available on this phone", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Uses the platform document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // Lets Retry reopen the same document after Rabta restarts, without broad storage permission.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val peers = verifiedPeers()
        when {
            peers.isEmpty() -> needNearby()
            else -> RabtaUi.sheet(this, "Send to", peers.map { peer ->
                RabtaUi.SheetAction(peer.name.ifBlank { peer.id }, "Verified nearby contact") { RabtaDrop.engine?.offer(peer.id, uri.toString()) ?: needNearby() }
            })
        }
    }

    private fun needNearby() = android.app.AlertDialog.Builder(this).setTitle("No verified contact nearby")
        .setMessage("Rabta Drop sends only to a contact connected in Nearby whose security key has been confirmed. Open Nearby, connect, then try again.")
        .setPositiveButton("Open Nearby") { _, _ -> startActivity(Intent(this, NearbyActivity::class.java)) }
        .setNegativeButton("Cancel", null).show()

    private fun render() {
        if (Session.id != owner) { finish(); return }
        val engine = RabtaDrop.engine
        val peers = verifiedPeers()
        linkStatus.text = when {
            engine == null -> "Nearby is off. Turn on Radar in Nearby to send or receive files."
            peers.isEmpty() -> "No verified contact connected nearby yet."
            else -> "Ready · ${peers.size} verified contact${if (peers.size == 1) "" else "s"} nearby: ${peers.joinToString { it.name.ifBlank { it.id } }}"
        }
        val all = RabtaDrop.history()
        incoming.removeAllViews()
        all.filter { !it.outgoing && it.state == State.INCOMING }.forEach { incoming.addView(offerCard(it)) }
        transfers.removeAllViews()
        val shown = all.filter { it.outgoing || it.state != State.INCOMING }
        if (shown.isEmpty()) transfers.addView(RabtaUi.empty(this, "nearby", "No transfers yet", "Files you send or receive nearby appear here with their verified status."))
        shown.forEach { transfers.addView(transferCard(it)) }
    }

    private fun offerCard(t: Transfer) = RabtaUi.card(this).apply {
        tag = "drop-offer:${t.id}"
        background = RabtaUi.shape(this@DropActivity, AppTheme.sent(this@DropActivity), RabtaUi.CORNER, AppTheme.accent(this@DropActivity))
        addView(text("${t.peerName} wants to send you", 13f, true, AppTheme.accent(this@DropActivity)))
        addView(text(t.name, 17f, true).apply { setPadding(0, dp(6), 0, 0); maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE })
        addView(text("${DropProtocol.kind(t.mime)} · ${RabtaDrop.size(t.size)} · checksum ${t.sha256.take(8)}", 13f, color = AppTheme.sub(this@DropActivity)).apply { setPadding(0, dp(4), 0, 0) })
        addView(text("From a verified nearby contact. Nothing is saved until you accept.", 12f, color = AppTheme.sub(this@DropActivity)).apply { setPadding(0, dp(8), 0, dp(12)) })
        val actions = LinearLayout(this@DropActivity)
        actions.addView(RabtaUi.button(this@DropActivity, "Decline") { RabtaDrop.engine?.decline(t.id) }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        actions.addView(RabtaUi.button(this@DropActivity, "Accept", true) { RabtaDrop.engine?.accept(t.id) }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(actions)
    }

    private fun transferCard(t: Transfer) = RabtaUi.card(this).apply {
        tag = "drop:${t.id}"
        val top = LinearLayout(this@DropActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this@DropActivity).apply {
            UiIcons.set(this, when (DropProtocol.kind(t.mime)) { "Photo" -> "status"; "Video" -> "video"; "PDF" -> "text"; else -> "backup" }, AppTheme.accent(this@DropActivity), 20, DropProtocol.kind(t.mime))
            gravity = Gravity.CENTER; background = RabtaUi.shape(this@DropActivity, AppTheme.raised(this@DropActivity), 12); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val labels = LinearLayout(this@DropActivity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(t.name, 15f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
        labels.addView(text("${if (t.outgoing) "To" else "From"} ${t.peerName} · ${RabtaDrop.size(t.size)}", 12f, color = AppTheme.sub(this@DropActivity)))
        top.addView(labels, LinearLayout.LayoutParams(0, -2, 1f)); addView(top)
        if (t.active && t.size > 0) addView(ProgressBar(this@DropActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = (t.bytes * 1000 / t.size).toInt(); isIndeterminate = t.state == State.PREPARING || t.state == State.WAITING
            progressTintList = ColorStateList.valueOf(AppTheme.accent(this@DropActivity)); contentDescription = "Transfer progress"
        }, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(12) })
        val color = when (t.state) { State.COMPLETED -> AppTheme.teal(this@DropActivity); State.FAILED -> AppTheme.error(this@DropActivity); else -> AppTheme.sub(this@DropActivity) }
        addView(text(label(t), 13f, t.state == State.COMPLETED || t.state == State.FAILED, color).apply { tag = "drop-status:${t.id}"; setPadding(0, dp(10), 0, 0) })
        if (t.active && t.speed in 1..256 * 1024 && t.size - t.bytes > 20L * 1024 * 1024)
            addView(text("Slow link — keep both phones close. Nearby switches to Wi-Fi by itself when both phones support it.", 12f, color = AppTheme.sub(this@DropActivity)).apply { setPadding(0, dp(4), 0, 0) })
        val actions = LinearLayout(this@DropActivity).apply { gravity = Gravity.END }
        fun action(label: String, run: () -> Unit) = actions.addView(RabtaUi.button(this@DropActivity, label, action = run).apply {
            setTextColor(AppTheme.accent(this@DropActivity)); setPadding(dp(14), dp(8), dp(14), dp(8)); layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
        })
        if (t.active) action("Cancel") { RabtaDrop.engine?.cancel(t.id) }
        if (t.retryable) action("Retry") { if (RabtaDrop.engine == null || verifiedPeers().none { it.id == t.peer }) needNearby() else RabtaDrop.engine?.retry(t.id) }
        if (!t.outgoing && t.state == State.COMPLETED && t.saved.isNotEmpty()) action("Open") { open(t) }
        if (actions.childCount > 0) addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun label(t: Transfer): String {
        val percent = if (t.size > 0) (t.bytes * 100 / t.size).toInt() else 0
        fun moving(): String {
            if (t.speed <= 0) return "$percent%"
            val left = (t.size - t.bytes) / t.speed
            return "$percent% · ${RabtaDrop.size(t.speed)}/s · ${if (left >= 60) "${left / 60} min" else "$left s"} left"
        }
        return when (t.state) {
            State.PREPARING -> "Preparing · checking the file"
            State.WAITING -> "Waiting for ${t.peerName} to accept"
            State.INCOMING -> "Waiting for your approval"
            State.SENDING -> "Sending · ${moving()}"
            State.RECEIVING -> "Receiving · ${moving()}"
            State.VERIFYING -> if (t.outgoing) "Sent · waiting for ${t.peerName} to verify" else "Verifying checksum…"
            State.COMPLETED -> if (t.outgoing) "Delivered · verified by ${t.peerName}" else "Saved to Downloads/Rabta · verified"
            State.FAILED -> "Failed · ${t.error.ifBlank { "Transfer stopped" }}"
            State.DECLINED -> if (t.outgoing) "Declined by ${t.peerName}" else "Declined"
            State.CANCELED -> "Cancelled${if (t.error.isNotBlank()) " · ${t.error}" else ""}"
        }
    }

    private fun open(t: Transfer) {
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(t.saved), t.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "No app on this phone can open ${DropProtocol.kind(t.mime).lowercase()} files", Toast.LENGTH_LONG).show() }
    }

    companion object { private const val PICK = 41 }
}
