package com.irfan.rabta

import android.app.NotificationManager
import android.content.Context
import java.io.File

/**
 * Delete account (Settings), which Google Play asks apps with sign-up to offer. The server deletes the account and
 * what it left there (POST /api/me/delete, with the PIN); then this phone signs out and deletes that account's chats,
 * files and settings, as the person chose. Other accounts on the phone, and the phone's own identity key, stay.
 */
object AccountDeletion {
    private val FILE_KEY = Regex("[0-9a-fA-F-]{36}")

    /** After the server deleted [account]. Off the main thread. */
    fun wipe(c: Context, account: String) {
        val app = c.applicationContext
        // The files this account's chats point to, found before its history goes.
        val files = runCatching { Db.get(app).attachmentKeys() }.getOrDefault(emptySet()) +
            app.getSharedPreferences("downloads_$account", 0).all.values.mapNotNull { it as? String }
        if (VoiceCall.active) runCatching { VoiceCall.end() }
        if (VideoCall.active) runCatching { VideoCall.hangUp() }
        MessageService.stop(app); WsClient.disconnect(); Push.forget(app)
        Session.logout()
        Db.delete(app, account)
        for (name in listOf("mesh_v2_$account.db", "drop_$account.db")) app.deleteDatabase(name)
        for (name in listOf("drafts", "privacy", "radar", "instant_connect", "mesh_keys", "status_prefs", "uploads", "downloads")) app.deleteSharedPreferences("${name}_$account")
        app.getSharedPreferences("status_cache", 0).edit().remove(account).apply()
        runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("rabta_mesh_v2_$account") } }
        val dir = File(app.filesDir, "attachments")
        for (key in files) if (FILE_KEY.matches(key)) File(dir, key).delete()
        runCatching { app.getSystemService(NotificationManager::class.java).cancelAll() }
    }
}
