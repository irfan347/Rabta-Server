package com.irfan.rabta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts [MessageService] after the phone restarts or Rabta is updated, and shortly after Rabta is swiped away on
 * phones that end it then, so messages arrive without opening Rabta first.
 */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // No action: Rabta's own restart alarm (see MessageService.onTaskRemoved).
        if (intent.action !in setOf(null, MessageService.KEEP_ALIVE, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        Session.init(context)
        if (!Session.loggedIn) return
        if (intent.action == MessageService.KEEP_ALIVE) MessageService.keepAlive(context) else MessageService.start(context)
    }
}
