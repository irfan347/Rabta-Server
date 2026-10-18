package com.irfan.rabta

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The profile-photo crop step, on the same sizes a phone screenshot produces. */
@RunWith(AndroidJUnit4::class)
class CropViewTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun drag(v: View, x0: Float, y0: Float, x1: Float, y1: Float) {
        val t = SystemClock.uptimeMillis()
        for ((at, action, x, y) in listOf(Quad(t, MotionEvent.ACTION_DOWN, x0, y0), Quad(t + 16, MotionEvent.ACTION_MOVE, x1, y1), Quad(t + 32, MotionEvent.ACTION_UP, x1, y1))) {
            val e = MotionEvent.obtain(t, at, action, x, y, 0); v.onTouchEvent(e); e.recycle()
        }
    }
    private data class Quad(val at: Long, val action: Int, val x: Float, val y: Float)

    /**
     * A circle as wide as a portrait photo used to crash on the first drag: rounding left the allowed
     * sideways move "inside out" (minimum above maximum) for roughly four photo sizes in ten.
     */
    @Test fun squareFrameOverPortraitPhotosMovesAndResizesWithoutCrashing() {
        var failure: Throwable? = null
        inst.runOnMainSync {
            try {
                val view = CropView(inst.targetContext)
                view.layout(0, 0, 1080, 1500)
                view.square = true
                val d = inst.targetContext.resources.displayMetrics.density; val pad = 24 * d
                for (w in 300 until 400) {
                    val photo = Bitmap.createBitmap(w, 800, Bitmap.Config.RGB_565)
                    view.source = photo
                    // Where the fitted photo and its full-width circle sit (CropView.reset).
                    val scale = minOf((1080 - 2 * pad) / w, (1500 - 2 * pad) / 800f)
                    val side = w * scale; val left = (1080 - side) / 2; val top = 750 - side / 2
                    drag(view, 540f, 750f, 560f, 820f)                          // move inside the circle
                    drag(view, left, top, left + 120, top + 120)                // shrink from the top-left corner
                    drag(view, left + 120, top + 120, left - 300, top - 300)    // grow past the photo's edge
                    val out = view.result()!!
                    assertEquals("Profile photo stays square for width $w", out.width, out.height)
                    assertTrue("Crop stays inside a ${w}x800 photo", out.width <= w && out.height <= 800)
                    out.recycle(); photo.recycle()
                }
            } catch (t: Throwable) { failure = t }
        }
        failure?.let { throw it }
    }
}
