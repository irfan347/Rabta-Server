package com.irfan.rabta

import android.graphics.Bitmap
import android.os.SystemClock

/**
 * Cache of profile pictures. A known photo shows at once; after a few minutes the next view also asks the server
 * whether it changed (a tiny reply when it did not). "No photo" and failed lookups are asked again after a minute,
 * so a photo someone adds or changes shows up without restarting the app.
 */
object ProfileHelper {
    private const val RECHECK_MS = 5 * 60_000L
    private const val RETRY_MS = 60_000L

    private class Entry(val bmp: Bitmap?, val tag: String, val next: Long)
    private val cache = HashMap<String, Entry>()

    /** Time source; device tests move it forward instead of waiting minutes. */
    var clock: () -> Long = SystemClock::elapsedRealtime
    private fun later(ms: Long) = clock() + ms

    fun set(userId: String, bmp: Bitmap?) { cache[userId] = Entry(bmp, "", later(RECHECK_MS)) }

    fun get(userId: String): Bitmap? = cache[userId]?.bmp

    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /** Main thread only. Several views asking for the same person share one request; [onLoaded] runs once. */
    fun loadIfNeeded(userId: String, serverUrl: String, token: String, onLoaded: (Bitmap?) -> Unit) {
        val held = cache[userId]
        if (held != null && clock() < held.next) { onLoaded(held.bmp); return }
        // A photo we already have stays on screen while the check runs; without one, wait for the answer.
        if (held?.bmp != null) onLoaded(held.bmp)
        waiting[userId]?.let { if (held?.bmp == null) it.add(onLoaded); return }
        waiting[userId] = if (held?.bmp == null) mutableListOf(onLoaded) else mutableListOf()
        WsClient.fetchPhoto(serverUrl, token, userId, held?.tag.orEmpty()) { answer ->
            val entry = when {
                answer == null -> Entry(held?.bmp, held?.tag.orEmpty(), later(RETRY_MS))
                answer.unchanged -> Entry(held?.bmp, answer.tag, later(RECHECK_MS))
                answer.photo.isEmpty() -> Entry(null, "", later(RETRY_MS))
                else -> Entry(MediaHelper.base64ToBitmap(answer.photo), answer.tag, later(RECHECK_MS))
            }
            cache[userId] = entry
            waiting.remove(userId)?.forEach { it(entry.bmp) }
        }
    }

    fun clear() { cache.clear() }
}
