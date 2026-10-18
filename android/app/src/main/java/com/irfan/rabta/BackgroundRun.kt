package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Rabta has no push service: messages and calls arrive while [MessageService] runs. Android's battery optimisation,
 * and on some phones the maker's auto-start control, stop it once Rabta is closed, so the user is asked to allow it.
 */
object BackgroundRun {
    private fun prefs(c: Context) = c.getSharedPreferences("background_run", 0)

    fun allowed(c: Context) = c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true

    fun summary(c: Context) = if (allowed(c)) "Allowed · Android keeps Rabta running for new messages" else "Not allowed · messages may stop when Rabta is closed"

    /** Android's own "let this app always run in the background?" question. */
    fun request(a: Activity) {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))
        if (runCatching { a.startActivity(ask) }.isFailure) openAppSettings(a)
    }

    private fun openAppSettings(a: Activity) {
        runCatching { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))) }
    }

    /** Phone makers whose own auto-start control can stop Rabta even when Android allows it. */
    private val makers = setOf("xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "iqoo", "huawei", "honor", "infinix", "tecno", "itel", "asus")
    private val autoStartScreens = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.transsion.phonemaster" to "com.cyin.himgr.autostart.AutoStartActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
    )
    private fun needsAutoStart() = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase().let { phone -> makers.any { it in phone } }

    /** Opens the maker's auto-start list, or Rabta's app settings where those phones keep the same switch. */
    private fun openAutoStart(a: Activity) {
        if (autoStartScreens.none { (pkg, screen) -> runCatching { a.startActivity(Intent().setClassName(pkg, screen)) }.isSuccess }) openAppSettings(a)
    }

    /** Settings entry: every action opens a real Android or phone-maker control. */
    fun open(a: Activity) = RabtaUi.sheet(a, "Background activity", listOfNotNull(
        RabtaUi.SheetAction("Allow background activity", if (allowed(a)) "Allowed" else "Needed so new messages and calls reach you") {
            if (allowed(a)) runCatching { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } else request(a)
        },
        if (needsAutoStart()) RabtaUi.SheetAction("Auto-start", "This phone also needs Rabta allowed to start by itself") { openAutoStart(a) } else null,
        RabtaUi.SheetAction("Rabta app settings", "Battery and background controls for Rabta") { openAppSettings(a) },
    ))

    /** The chats screen offers this until it is allowed or dismissed; Settings keeps the same controls. */
    fun shouldOffer(c: Context) = !allowed(c) && !prefs(c).getBoolean("dismissed", false)
    fun dismiss(c: Context) { prefs(c).edit().putBoolean("dismissed", true).apply() }

    /** Android's question first; on phones with their own auto-start control, [followUp] then opens that too. */
    fun allow(a: Activity) {
        prefs(a).edit().putBoolean("auto_start_pending", needsAutoStart()).apply()
        request(a)
    }

    /** Chats screen resume: on phones with their own auto-start control, that step follows Android's question. */
    fun followUp(a: Activity) {
        if (!prefs(a).getBoolean("auto_start_pending", false)) return
        prefs(a).edit().putBoolean("auto_start_pending", false).apply()
        AlertDialog.Builder(a).setTitle("One more step on this phone")
            .setMessage("This phone can also stop Rabta on its own. Turn on Auto-start for Rabta on the next screen.")
            .setNegativeButton("Not now", null).setPositiveButton("Open") { _, _ -> openAutoStart(a) }.show()
    }
}
