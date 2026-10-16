package com.irfan.rabta

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

/** Settings are grouped by purpose; every row leads to an existing control or states its limit. */
class SettingsActivity : ThemedActivity() {
    private lateinit var content: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        content = FeatureUi.page(this, "Settings")
        content.addView(FeatureUi.text(this, "Your Rabta preferences and account tools."))

        section("ACCOUNT") {
            row("Profile", "${Session.name} · @${Session.username}", "profile") {
                startActivity(Intent(this, ProfileActivity::class.java))
            }
            row("Encrypted backup", "Save a password protected copy of local chats", "backup") {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "Rabta-backup.rbt"), 41)
            }
            row("Restore backup", "Merge a backup into this account", "restore") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE), 42)
            }
            row("Sign out", "This phone's local chats remain stored", "account") { confirmLogout() }
        }

        section("PRIVACY & SECURITY") {
            toggle("Hide notification previews", "App lock also hides previews", Privacy.prefs(this).getBoolean("hide_previews", false)) { enabled ->
                Privacy.prefs(this).edit().putBoolean("hide_previews", enabled).apply()
            }
            row("App lock", if (Privacy.prefs(this).getBoolean("lock", false)) "Enabled · device screen lock" else "Use your device unlock") {
                toggleAppLock()
            }
            row("Blocked contacts", "${blockedContacts().size} blocked", "privacy") { showBlockedContacts() }
        }

        section("APPEARANCE") {
            row("Theme", AppTheme.mode(this), "appearance") { AppTheme.choose(this) }
            row("Language", if (L.prefs(this).getString("language", "en") == "ur") "اردو" else "English", "language") {
                RabtaUi.sheet(this, "Language", listOf("English", "اردو").mapIndexed { i, label ->
                    RabtaUi.SheetAction(label, if ((i == 1) == (L.prefs(this).getString("language", "en") == "ur")) "Selected" else null) {
                        L.prefs(this).edit().putString("language", if (i == 0) "en" else "ur").apply(); recreate()
                    }
                })
            }
            row("Text size", "${(L.prefs(this).getFloat("scale", 1f) * 100).toInt()}%", "text") { chooseTextSize() }
        }

        section("NOTIFICATIONS") {
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val enabled = if (android.os.Build.VERSION.SDK_INT >= 24) manager.areNotificationsEnabled() else true
            row("App notifications", if (enabled) "Enabled in Android settings" else "Disabled in Android settings", "notifications") {
                openAppNotificationSettings()
            }
            row("Message notification settings", "Sound, vibration and importance", "sound") {
                openChannelSettings(MessageService.MSG_CHANNEL)
            }
            row("Call ringtone settings", "Ringtone and vibration controls", "phone") {
                openChannelSettings(MessageService.CALL_CHANNEL)
            }
            row("All notification settings", "Manage Rabta notifications in Android", "notifications") {
                openAppNotificationSettings()
            }
        }

        section("CONNECTIONS") {
            row("Nearby mesh", "${MeshBridge.peersConnected} connected · ${MeshBridge.status}", "nearby") {
                startActivity(Intent(this, NearbyActivity::class.java))
            }
            row("Connection details", "Internet and Nearby diagnostics", "connection") { ConnectionHelp.show(this) }
            row("Video relay", "Check whether this server offers relay support", "video") {
                FeatureApi.run(this, { FeatureApi.request("/api/ice").optBoolean("relayConfigured") }) { configured ->
                    themedInfo("Video relay", if (configured) "Relay is configured. Calls may use it when direct connections fail." else "No relay is configured. Some networks may prevent video calls.")
                }
            }
        }
        section("HELP") {
            row("About Rabta", "Version ${packageManager.getPackageInfo(packageName, 0).versionName}", "info") {
                themedInfo("About Rabta", "Rabta keeps your account identity and local chat history on this phone. Nearby messaging requires compatible devices, Google Play services, radios and permissions.")
            }
        }
    }

    private fun section(title: String, build: LinearLayout.() -> Unit) {
        content.addView(RabtaUi.text(this, title, 12f, true, AppTheme.accent(this)).apply {
            setPadding(RabtaUi.dp(this@SettingsActivity, 4), RabtaUi.dp(this@SettingsActivity, 22), 0, RabtaUi.dp(this@SettingsActivity, 8))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = RabtaUi.shape(this@SettingsActivity, AppTheme.surface(this@SettingsActivity), 20, AppTheme.line(this@SettingsActivity))
            clipToOutline = true
        }
        card.build()
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = RabtaUi.dp(this@SettingsActivity, 4) })
    }

    private fun LinearLayout.row(title: String, detail: String, icon: String? = null, action: () -> Unit) {
        val row = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = RabtaUi.dp(this@SettingsActivity, 68)
            setPadding(RabtaUi.dp(this@SettingsActivity, 16), RabtaUi.dp(this@SettingsActivity, 10), RabtaUi.dp(this@SettingsActivity, 16), RabtaUi.dp(this@SettingsActivity, 10))
            background = RabtaUi.ripple(this@SettingsActivity, AppTheme.surface(this@SettingsActivity), 0)
            isFocusable = true; isClickable = true; setOnClickListener { action() }
        }
        if (icon != null) row.addView(TextView(this@SettingsActivity).apply {
            UiIcons.set(this, icon, AppTheme.accent(this@SettingsActivity), 19, title)
            gravity = Gravity.CENTER; background = RabtaUi.shape(this@SettingsActivity, AppTheme.raised(this@SettingsActivity), 13)
        }, LinearLayout.LayoutParams(RabtaUi.dp(this@SettingsActivity, 40), RabtaUi.dp(this@SettingsActivity, 40)).apply { marginEnd = RabtaUi.dp(this@SettingsActivity, 14) })
        val labels = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(RabtaUi.text(this@SettingsActivity, title, 15f, true))
        labels.addView(RabtaUi.text(this@SettingsActivity, detail, 13f, color = AppTheme.sub(this@SettingsActivity)).apply { setPadding(0, RabtaUi.dp(this@SettingsActivity, 3), 0, 0); maxLines = 2 })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(RabtaUi.text(this@SettingsActivity, "›", 24f, color = AppTheme.sub(this@SettingsActivity)))
        addView(row)
        if (childCount > 1) addView(View(this@SettingsActivity).apply { setBackgroundColor(AppTheme.line(this@SettingsActivity)) }, LinearLayout.LayoutParams(-1, RabtaUi.dp(this@SettingsActivity, 1)).apply { marginStart = RabtaUi.dp(this@SettingsActivity, 70) })
    }

    private fun LinearLayout.toggle(title: String, detail: String, checked: Boolean, changed: (Boolean) -> Unit) {
        val row = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = RabtaUi.dp(this@SettingsActivity, 68)
            setPadding(RabtaUi.dp(this@SettingsActivity, 16), RabtaUi.dp(this@SettingsActivity, 8), RabtaUi.dp(this@SettingsActivity, 12), RabtaUi.dp(this@SettingsActivity, 8))
        }
        val labels = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(RabtaUi.text(this@SettingsActivity, title, 15f, true))
        labels.addView(RabtaUi.text(this@SettingsActivity, detail, 13f, color = AppTheme.sub(this@SettingsActivity)).apply { setPadding(0, RabtaUi.dp(this@SettingsActivity, 3), 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Switch(this@SettingsActivity).apply {
            isChecked = checked; contentDescription = title
            setOnCheckedChangeListener { _, value -> changed(value) }
            buttonTintList = android.content.res.ColorStateList.valueOf(AppTheme.accent(this@SettingsActivity))
        })
        addView(row)
    }

    private fun blockedContacts() = Db.get(this).conversations().filter { Privacy.blocked(this, it.peerId) }
    private fun showBlockedContacts() {
        val blocked = blockedContacts()
        if (blocked.isEmpty()) { themedInfo("Blocked contacts", "You have no blocked contacts."); return }
        RabtaUi.sheet(this, "Blocked contacts", blocked.map { c ->
            RabtaUi.SheetAction(c.peerName, "${c.peerId} · Tap to unblock") {
                AlertDialog.Builder(this).setTitle("Unblock ${c.peerName}?").setMessage("Messages and calls from this contact will be allowed again.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Unblock") { _, _ -> Privacy.setBlocked(this, c.peerId, false); recreate() }.show()
            }
        })
    }

    private fun toggleAppLock() {
        val enabled = Privacy.prefs(this).getBoolean("lock", false)
        AlertDialog.Builder(this).setTitle("App lock")
            .setMessage(if (enabled) "Turn off device authentication when opening Rabta?" else "Use this phone's fingerprint or screen lock to protect Rabta. It locks after 30 seconds away.")
            .setNegativeButton("Cancel", null).setPositiveButton(if (enabled) "Disable" else "Enable") { _, _ ->
                AppLock.authenticate(this) { Privacy.prefs(this).edit().putBoolean("lock", !enabled).apply(); recreate() }
            }.show()
    }

    private fun chooseTextSize() {
        val values = listOf(0.9f, 1f, 1.15f, 1.3f); val labels = arrayOf("Small", "Default", "Large", "Extra large")
        AlertDialog.Builder(this).setTitle("Text size").setSingleChoiceItems(labels, values.indexOf(L.prefs(this).getFloat("scale", 1f)).coerceAtLeast(0)) { dialog, index ->
            L.prefs(this).edit().putFloat("scale", values[index]).apply(); dialog.dismiss(); recreate()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun openAppNotificationSettings() {
        val intent = if (android.os.Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun openChannelSettings(channelId: String) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId))
        } else openAppNotificationSettings()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this).setTitle("Sign out of Rabta?")
            .setMessage("Your chats and account data remain on this phone. You can sign in again with the same account.")
            .setNegativeButton("Cancel", null).setPositiveButton("Sign out") { _, _ ->
                MessageService.stop(this); Session.logout(); WsClient.disconnect()
                startActivity(Intent(this, AuthActivity::class.java)); finishAffinity()
            }.show()
    }

    private fun themedInfo(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Close", null).show()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || req !in 41..42) return
        val uri = data?.data ?: return
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(RabtaUi.dp(this@SettingsActivity, 24), RabtaUi.dp(this@SettingsActivity, 8), RabtaUi.dp(this@SettingsActivity, 24), 0) }
        val password = EditText(this).apply { hint = "Backup password (10+ characters)"; inputType = 129; setTextColor(AppTheme.text(this@SettingsActivity)); setHintTextColor(AppTheme.sub(this@SettingsActivity)) }
        fields.addView(password)
        val confirm = EditText(this).apply { hint = "Confirm password"; inputType = 129; setTextColor(AppTheme.text(this@SettingsActivity)); setHintTextColor(AppTheme.sub(this@SettingsActivity)) }
        if (req == 41) fields.addView(confirm)
        val dialog = AlertDialog.Builder(this).setTitle(if (req == 41) "Create encrypted backup" else "Restore encrypted backup")
            .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Continue", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pass = password.text.toString()
                if (pass.length < 10 || (req == 41 && pass != confirm.text.toString())) { password.error = "Use 10+ matching characters"; return@setOnClickListener }
                dialog.dismiss(); Toast.makeText(this, "Working…", Toast.LENGTH_SHORT).show()
                FeatureApi.run(this, { if (req == 41) ChatBackup.export(this, uri, pass.toCharArray()) else ChatBackup.restore(this, uri, pass.toCharArray()) }) {
                    Toast.makeText(this, if (req == 41) "Encrypted backup saved" else "Chats restored", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }
}
