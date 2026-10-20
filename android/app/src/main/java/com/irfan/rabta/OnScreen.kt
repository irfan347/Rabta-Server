package com.irfan.rabta

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Whether Rabta is on the phone's screen: while one of its screens shows, and until 2 s after the last one went, so
 * going from one screen to the next is not leaving. Contacts see "online" only then, as in WhatsApp ([WsClient.shown]).
 */
object OnScreen : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var showing = 0
    private val left = Runnable { WsClient.shown(false) }

    @Synchronized fun init(app: Application) { if (registered) return; registered = true; app.registerActivityLifecycleCallbacks(this) }

    override fun onActivityResumed(a: Activity) { showing++; handler.removeCallbacks(left); WsClient.shown(true) }
    override fun onActivityPaused(a: Activity) { showing = (showing - 1).coerceAtLeast(0); if (showing == 0) handler.postDelayed(left, 2000) }
    override fun onActivityCreated(a: Activity, state: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
