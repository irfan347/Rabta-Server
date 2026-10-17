package com.irfan.rabta

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*

class ProfileActivity : ThemedActivity() {
    private val pickCode = 101
    private lateinit var avatar: ImageView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        val d = { n: Int -> RabtaUi.dp(this, n) }
        // NOVA page: back · Profile · settings, then a full-width hero and grouped cards.
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(AppTheme.bg(this@ProfileActivity)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(8), d(8), d(8), d(4)) }
        head.addView(RabtaUi.iconButton(this, "back", "Back") { finish() }.apply { UiIcons.set(this, "back", AppTheme.text(this@ProfileActivity), 24, "Back") })
        head.addView(RabtaUi.text(this, "Profile", 22f, true).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true); setPadding(d(4), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(RabtaUi.iconButton(this, "settings", "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(head)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), 0, d(16), d(24)) }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)

        // Hero: signal waves, avatar with lime ring and camera badge, name with presence dot, username.
        val hero = FrameLayout(this)
        hero.addView(WaveHeroView(this), FrameLayout.LayoutParams(-1, -1))
        val heroCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, d(12), 0, d(16)) }
        avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = RabtaUi.shape(this@ProfileActivity, AppTheme.accent(this@ProfileActivity), 999)
            clipToOutline = true; contentDescription = "Profile photo"
        }
        loadAvatar()
        val ring = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(AppTheme.bg(this@ProfileActivity)); setStroke(d(3), AppTheme.accent(this@ProfileActivity)) }
            setPadding(d(6), d(6), d(6), d(6))
            addView(avatar, FrameLayout.LayoutParams(-1, -1))
        }
        val avatarBox = FrameLayout(this)
        avatarBox.addView(ring, FrameLayout.LayoutParams(d(120), d(120)))
        avatarBox.addView(TextView(this).apply {
            UiIcons.set(this, "photo", AppTheme.onPrimary(this@ProfileActivity), 18, "Change profile photo"); gravity = Gravity.CENTER; isFocusable = true
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(AppTheme.primary(this@ProfileActivity)); setStroke(d(3), AppTheme.bg(this@ProfileActivity)) }
            setOnClickListener { pickPhoto() }
        }, FrameLayout.LayoutParams(d(40), d(40), Gravity.BOTTOM or Gravity.END))
        heroCol.addView(avatarBox, LinearLayout.LayoutParams(d(124), d(124)))
        val nameRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, d(12), 0, 0) }
        nameRow.addView(RabtaUi.text(this, Session.name.ifBlank { Session.username }, 24f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (WsClient.connected) nameRow.addView(View(this).apply {
            background = RabtaUi.shape(this@ProfileActivity, AppTheme.teal(this@ProfileActivity), 999); contentDescription = "Online"
        }, LinearLayout.LayoutParams(d(10), d(10)).apply { marginStart = d(8) })
        heroCol.addView(nameRow)
        heroCol.addView(RabtaUi.text(this, "@${Session.username}", 15f, color = AppTheme.sub(this)).apply { gravity = Gravity.CENTER })
        hero.addView(heroCol, FrameLayout.LayoutParams(-1, -2))
        body.addView(hero, LinearLayout.LayoutParams(-1, -2))

        // Rabta ID card: tap anywhere to copy.
        val idCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(d(16), d(14), d(8), d(14)); isFocusable = true
            background = RabtaUi.ripple(this@ProfileActivity, AppTheme.surface(this@ProfileActivity), 20)
            setOnClickListener { copy("Rabta ID", Session.id) }
        }
        idCard.addView(iconCircle("nearby", AppTheme.accent(this)))
        val idTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, d(8), 0) }
        idTexts.addView(RabtaUi.text(this, "Rabta ID", 12f, color = AppTheme.sub(this)))
        idTexts.addView(RabtaUi.text(this, Session.id, 20f, true).apply { setTextIsSelectable(false); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) })
        idTexts.addView(RabtaUi.text(this, "Tap to copy your account ID", 12f, color = AppTheme.sub(this)))
        idCard.addView(idTexts, LinearLayout.LayoutParams(0, -2, 1f))
        idCard.addView(TextView(this).apply { UiIcons.set(this, "copy", AppTheme.sub(this@ProfileActivity), 22, "Copy"); gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(d(48), d(48)))
        body.addView(idCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(12) })

        // Identity: the real display-name and username editors.
        val identity = group()
        identityRow(identity, "Display name", Session.name, "Change any time · up to 30 characters", "Edit", "profile", AppTheme.accent(this)) { AccountUi.editName(this) { recreate() } }
        divider(identity)
        usernameNote = identityRow(identity, "Username", "@${Session.username}", "Your sign-in username · can change once every 30 days", "Edit", "account", AppTheme.violet(this)) {
            AccountUi.editUsername(this, nextUsernameChangeAt) { recreate() }
        }
        body.addView(identity, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(12) })

        // Settings rows with coloured icon circles.
        val actions = group()
        navRow(actions, "status", AppTheme.accent(this), "Availability", "Show when you're free to chat", soon = true) {}
        navRow(actions, "appearance", AppTheme.violet(this), "Appearance", if (AppTheme.dark(this)) "NOVA Dark · ${AppTheme.mode(this)}" else "NOVA Light · ${AppTheme.mode(this)}") { AppTheme.choose(this) }
        navRow(actions, "privacy", AppTheme.violet(this), "Privacy & Security", "App lock, blocked contacts, language") { startActivity(Intent(this, SettingsActivity::class.java)) }
        navRow(actions, "notifications", AppTheme.teal(this), "Notifications", "Sound, vibration and importance") {
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        }
        navRow(actions, "backup", AppTheme.violet(this), "Data & Storage", "Encrypted backup and restore of local chats") { startActivity(Intent(this, SettingsActivity::class.java)) }
        navRow(actions, "devices", AppTheme.violet(this), "Connected Devices", "Phones on your account and recovery code") { startActivity(Intent(this, DevicesActivity::class.java)) }
        navRow(actions, "star", AppTheme.accent(this), "Starred messages", "Messages you saved") { startActivity(Intent(this, StarredActivity::class.java)) }
        navRow(actions, "qr", AppTheme.teal(this), "My QR invitation", "Pair with a temporary QR code") { startActivity(Intent(this, InstantConnectActivity::class.java)) }
        navRow(actions, "privacy", AppTheme.teal(this), "Offline security code", "Verify your identity with a contact") { MeshSecurity.showIdentity(this) }
        navRow(actions, "connection", AppTheme.teal(this), "Connection", ConnectionStatus.text(this)) { ConnectionHelp.show(this) }
        navRow(actions, "info", AppTheme.violet(this), "About Rabta", "v${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""} (NOVA)", last = true) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        body.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(12) })
        body.addView(RabtaUi.button(this, "Sign out", action = ::confirmLogout).apply {
            setTextColor(AppTheme.error(this@ProfileActivity)); gravity = Gravity.CENTER
            background = RabtaUi.ripple(this@ProfileActivity, AppTheme.surface(this@ProfileActivity), 20)
        })
        body.addView(RabtaUi.text(this, "Your photo and display name are shared with your contacts.", 12f, color = AppTheme.sub(this)).apply { gravity = Gravity.CENTER; setPadding(0, d(8), 0, 0) })
        WsClient.api("GET", "/api/me/account", null) { code, account ->
            if (code != 200 || isFinishing) return@api
            nextUsernameChangeAt = account.optLong("nextUsernameChangeAt")
            if (nextUsernameChangeAt > System.currentTimeMillis()) usernameNote?.text = "Your sign-in username · next change ${AccountUi.date(nextUsernameChangeAt)}"
        }
    }
    private var nextUsernameChangeAt = 0L
    private var usernameNote: TextView? = null

    private fun group() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = RabtaUi.shape(this@ProfileActivity, AppTheme.surface(this@ProfileActivity), 20); clipToOutline = true
    }
    private fun iconCircle(icon: String, color: Int) = TextView(this).apply {
        UiIcons.set(this, icon, color, 20, icon); gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = RabtaUi.shape(this@ProfileActivity, AppTheme.raised(this@ProfileActivity), 999)
        layoutParams = LinearLayout.LayoutParams(RabtaUi.dp(this@ProfileActivity, 40), RabtaUi.dp(this@ProfileActivity, 40))
    }

    private fun identityRow(parent: LinearLayout, label: String, value: String, note: String, actionLabel: String, icon: String, color: Int, action: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(RabtaUi.dp(this@ProfileActivity, 16), RabtaUi.dp(this@ProfileActivity, 12), RabtaUi.dp(this@ProfileActivity, 16), RabtaUi.dp(this@ProfileActivity, 12))
            background = RabtaUi.ripple(this@ProfileActivity, AppTheme.surface(this@ProfileActivity), 0); isClickable = true; isFocusable = true; setOnClickListener { action() }
        }
        row.addView(iconCircle(icon, color))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(RabtaUi.dp(this@ProfileActivity, 14), 0, RabtaUi.dp(this@ProfileActivity, 8), 0) }
        texts.addView(RabtaUi.text(this, label, 12f, color = AppTheme.sub(this)))
        texts.addView(RabtaUi.text(this, value.ifBlank { "Not set" }, 16f, true).apply { setPadding(0, RabtaUi.dp(this@ProfileActivity, 2), 0, 0) })
        val noteView = RabtaUi.text(this, note, 12f, color = AppTheme.sub(this)).apply { setPadding(0, RabtaUi.dp(this@ProfileActivity, 2), 0, 0) }
        texts.addView(noteView)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(RabtaUi.text(this, actionLabel, 13f, true, AppTheme.accent(this)))
        parent.addView(row)
        return noteView
    }

    /** A settings row: coloured icon circle, title and detail, chevron (or a Coming soon badge). */
    private fun navRow(parent: LinearLayout, icon: String, color: Int, title: String, detail: String, soon: Boolean = false, last: Boolean = false, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = RabtaUi.dp(this@ProfileActivity, 64); isFocusable = true
            setPadding(RabtaUi.dp(this@ProfileActivity, 16), RabtaUi.dp(this@ProfileActivity, 10), RabtaUi.dp(this@ProfileActivity, 12), RabtaUi.dp(this@ProfileActivity, 10))
            background = RabtaUi.ripple(this@ProfileActivity, AppTheme.surface(this@ProfileActivity), 0)
            contentDescription = if (soon) "$title. Coming soon" else "$title. $detail"
            setOnClickListener { if (soon) Toast.makeText(this@ProfileActivity, "$title: coming soon", Toast.LENGTH_SHORT).show() else action() }
        }
        row.addView(iconCircle(icon, color))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(RabtaUi.dp(this@ProfileActivity, 14), 0, RabtaUi.dp(this@ProfileActivity, 8), 0) }
        texts.addView(RabtaUi.text(this, title, 15f, true))
        texts.addView(RabtaUi.text(this, detail, 12f, color = AppTheme.sub(this)).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(if (soon) RabtaUi.badge(this, "Coming soon") else RabtaUi.text(this, "›", 22f, color = AppTheme.sub(this)).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        parent.addView(row)
        if (!last) divider(parent)
    }

    private fun divider(parent: LinearLayout) = parent.addView(View(this).apply { setBackgroundColor(AppTheme.line(this@ProfileActivity)) }, LinearLayout.LayoutParams(-1, RabtaUi.dp(this, 1)).apply { marginStart = RabtaUi.dp(this@ProfileActivity, 70) })

    private fun loadAvatar() {
        avatar.setOnClickListener {pickPhoto()}
        avatar.background=RabtaUi.shape(this,AppTheme.sent(this),999)
        avatar.setImageDrawable(null)
        if (Session.profilePic.isNotEmpty()) MediaHelper.base64ToBitmap(Session.profilePic)?.let(avatar::setImageBitmap)
        if (avatar.drawable == null) {
            avatar.background=RabtaUi.shape(this,AppTheme.sent(this),999)
            avatar.setImageDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            avatar.contentDescription = "${Session.name.ifBlank { Session.username }} profile initials"
            avatar.setOnClickListener { pickPhoto() }
            avatar.setImageBitmap(initialsBitmap())
        }
    }

    private fun initialsBitmap(): android.graphics.Bitmap {
        val size = RabtaUi.dp(this, 104)
        return android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = android.graphics.Canvas(bitmap); val paint = android.graphics.Paint(3)
            paint.color = AppTheme.accent(this); paint.textSize = size * .42f; paint.typeface = android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
            val initial = Session.name.trim().split(Regex("\\s+")).mapNotNull { it.firstOrNull() }.take(2).joinToString("").ifBlank { "R" }.uppercase()
            val bounds = android.graphics.Rect(); paint.getTextBounds(initial, 0, initial.length, bounds)
            canvas.drawText(initial, (size - paint.measureText(initial)) / 2, (size - bounds.height()) / 2f - bounds.top, paint)
        }
    }

    private fun pickPhoto() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), pickCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Toast.makeText(this, "Updating photo…", Toast.LENGTH_SHORT).show()
        val owner=Session.id
        Thread {
            // A small avatar keeps the upload well under the server's 256 KB profile photo limit.
            val encoded = MediaHelper.imageToBase64(this, uri, maxPx = 320, quality = 80)
            if (encoded != null && Session.id==owner) {
                Session.profilePic = encoded
                handler.post {
                    if (!isDestroyed) loadAvatar()
                    WsClient.uploadPhoto(Session.serverUrl, Session.token, encoded) { ok, msg ->
                        if (!isDestroyed) Toast.makeText(this, if (ok) "Profile photo updated" else "Photo saved on this phone, but it could not be shared: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            } else handler.post { if (!isDestroyed) Toast.makeText(this, "Could not open that image", Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun copy(label: String, value: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this).setTitle("Sign out of Rabta?").setMessage("Local chats remain on this phone. Sign in again with the same account to reconnect.")
            .setNegativeButton("Cancel", null).setPositiveButton("Sign out") { _, _ ->
                MessageService.stop(this); Session.logout(); WsClient.disconnect(); startActivity(Intent(this, AuthActivity::class.java)); finishAffinity()
            }.show()
    }
}
