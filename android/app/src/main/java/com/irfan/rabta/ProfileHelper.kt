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

    private fun key(userId:String)=Session.id+":"+Session.serverUrl+":"+userId

    fun set(userId: String, bmp: Bitmap?) { cache[key(userId)] = Entry(bmp, "", later(RECHECK_MS)) }

    fun get(userId: String): Bitmap? = cache[key(userId)]?.bmp

    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /** Main thread only. Several views asking for the same person share one request; [onLoaded] may run again when a stale photo is refreshed. */
    fun loadIfNeeded(userId: String, serverUrl: String, token: String, onLoaded: (Bitmap?) -> Unit) {
        val accountKey=key(userId)
        val held = cache[accountKey]
        if (held != null && clock() < held.next) { onLoaded(held.bmp); return }
        // A photo we already have stays on screen while the check runs; without one, wait for the answer.
        if (held?.bmp != null) onLoaded(held.bmp)
        waiting[accountKey]?.let { it.add(onLoaded); return }
        waiting[accountKey] = mutableListOf(onLoaded)
        WsClient.fetchPhoto(serverUrl, token, userId, held?.tag.orEmpty()) { answer ->
            val entry = when {
                answer == null -> Entry(held?.bmp, held?.tag.orEmpty(), later(RETRY_MS))
                answer.unchanged -> Entry(held?.bmp, answer.tag, later(RECHECK_MS))
                answer.photo.isEmpty() -> Entry(null, "", later(RETRY_MS))
                else -> Entry(MediaHelper.base64ToBitmap(answer.photo), answer.tag, later(RECHECK_MS))
            }
            cache[accountKey] = entry
            waiting.remove(accountKey)?.forEach { if(accountKey==key(userId)) it(entry.bmp) }
        }
    }

    fun clear() { cache.clear() }
}
