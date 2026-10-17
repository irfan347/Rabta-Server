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
        val body = FeatureUi.page(this, "Profile")

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            background = RabtaUi.shape(this@ProfileActivity, AppTheme.surface(this@ProfileActivity), 20)
            setPadding(RabtaUi.dp(this@ProfileActivity, 16), RabtaUi.dp(this@ProfileActivity, 16), RabtaUi.dp(this@ProfileActivity, 16), RabtaUi.dp(this@ProfileActivity, 16))
        }
        avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = RabtaUi.shape(this@ProfileActivity, AppTheme.accent(this@ProfileActivity), 999)
            clipToOutline = true; contentDescription = "Profile photo"
        }
        loadAvatar()
        val identityHead=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        identityHead.addView(avatar,LinearLayout.LayoutParams(RabtaUi.dp(this,72),RabtaUi.dp(this,72)))
        val labels=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(RabtaUi.dp(this@ProfileActivity,16),0,0,0)}
        labels.addView(RabtaUi.text(this,Session.name.ifBlank {Session.username},20f,true))
        labels.addView(RabtaUi.text(this,"@${Session.username}",13f,color=AppTheme.sub(this)))
        identityHead.addView(labels,LinearLayout.LayoutParams(0,-2,1f));hero.addView(identityHead)
        hero.addView(RabtaUi.button(this, "Change profile photo", action = ::pickPhoto).apply { (layoutParams as LinearLayout.LayoutParams).topMargin=RabtaUi.dp(this@ProfileActivity,16) })
        hero.addView(RabtaUi.text(this, "Photo stays on this phone; profile photo sync is not supported by the current server.", 12f, color = AppTheme.sub(this)).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) })
        body.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = RabtaUi.dp(this@ProfileActivity, 18) })

        body.addView(RabtaUi.text(this, "ACCOUNT ID", 12f, true, AppTheme.accent(this)).apply {
            setPadding(RabtaUi.dp(this@ProfileActivity, 4), RabtaUi.dp(this@ProfileActivity, 14), 0, RabtaUi.dp(this@ProfileActivity, 8))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = RabtaUi.shape(this@ProfileActivity, AppTheme.surface(this@ProfileActivity), 20)
            clipToOutline = true
        }
        identityRow(identity, "Display name", Session.name, "Editing is not supported by this server")
        divider(identity)
        identityRow(identity, "Username", "@${Session.username}", "Your sign-in username · cannot be changed")
        divider(identity)
        identityRow(identity, "Rabta ID", Session.id, "Tap to copy your account ID") { copy("Rabta ID", Session.id) }
        body.addView(identity, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = RabtaUi.dp(this@ProfileActivity, 18) })

        body.addView(RabtaUi.text(this, "YOUR RABTA", 12f, true, AppTheme.accent(this)).apply {
            setPadding(RabtaUi.dp(this@ProfileActivity, 4), RabtaUi.dp(this@ProfileActivity, 8), 0, RabtaUi.dp(this@ProfileActivity, 8))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = RabtaUi.shape(this@ProfileActivity, AppTheme.surface(this@ProfileActivity), 20); setPadding(RabtaUi.dp(this@ProfileActivity, 14), RabtaUi.dp(this@ProfileActivity, 14), RabtaUi.dp(this@ProfileActivity, 14), RabtaUi.dp(this@ProfileActivity, 8))
        }
        actions.addView(RabtaUi.row(this,"Appearance",AppTheme.mode(this),"appearance") {AppTheme.choose(this)})
        actions.addView(RabtaUi.row(this,"Privacy & account settings","Security, notifications and backups","settings") {startActivity(Intent(this,SettingsActivity::class.java))})
        actions.addView(RabtaUi.row(this,"Offline security code","Verify your identity with a contact","privacy") {MeshSecurity.showIdentity(this)})
        actions.addView(RabtaUi.row(this,"Connection",ConnectionStatus.text(this),"connection") {ConnectionHelp.show(this)})
        actions.addView(RabtaUi.button(this,"Sign out",action=::confirmLogout).apply {setTextColor(AppTheme.error(this@ProfileActivity))})
        body.addView(actions)
    }

    private fun identityRow(parent: LinearLayout, label: String, value: String, note: String, action: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(RabtaUi.dp(this@ProfileActivity, 18), RabtaUi.dp(this@ProfileActivity, 13), RabtaUi.dp(this@ProfileActivity, 16), RabtaUi.dp(this@ProfileActivity, 13))
            if (action != null) { background = RabtaUi.ripple(this@ProfileActivity, AppTheme.surface(this@ProfileActivity), 0); isClickable = true; isFocusable = true; setOnClickListener { action() } }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(RabtaUi.text(this, label, 12f, true, AppTheme.accent(this)))
        texts.addView(RabtaUi.text(this, value.ifBlank { "Not set" }, 16f, true).apply { setPadding(0, RabtaUi.dp(this@ProfileActivity, 3), 0, 0) })
        texts.addView(RabtaUi.text(this, note, 12f, color = AppTheme.sub(this)).apply { setPadding(0, RabtaUi.dp(this@ProfileActivity, 3), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) row.addView(RabtaUi.text(this, "Copy", 13f, true, AppTheme.accent(this)))
        parent.addView(row)
    }

    private fun divider(parent: LinearLayout) = parent.addView(View(this).apply { setBackgroundColor(AppTheme.line(this@ProfileActivity)) }, LinearLayout.LayoutParams(-1, RabtaUi.dp(this, 1)).apply { marginStart = RabtaUi.dp(this@ProfileActivity, 18) })

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
            val encoded = MediaHelper.imageToBase64(this, uri)
            if (encoded != null && Session.id==owner) {
                Session.profilePic = encoded
                handler.post { if (!isDestroyed) { loadAvatar(); Toast.makeText(this, "Photo saved on this phone", Toast.LENGTH_LONG).show() } }
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
