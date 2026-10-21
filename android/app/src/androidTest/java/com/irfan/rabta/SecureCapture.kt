package com.irfan.rabta

import android.app.Instrumentation
import android.graphics.Bitmap
import android.view.Window
import android.view.WindowManager
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/**
 * Rabta keeps its screens out of screenshots (FLAG_SECURE), so Android hands tests no picture of them. Design captures
 * lift the flag on the screens on show for the moment of the capture, then put it back.
 */
fun Instrumentation.captureScreen(): Bitmap? {
    val lifted = mutableListOf<Window>()
    runOnMainSync {
        for (a in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) { a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); lifted += a.window }
    }
    waitForIdleSync()
    if (lifted.isNotEmpty()) Thread.sleep(300)
    try { return uiAutomation.takeScreenshot() }
    finally { runOnMainSync { lifted.forEach { it.addFlags(WindowManager.LayoutParams.FLAG_SECURE) } } }
}
