package com.irfan.rabta

import android.content.Context
import android.graphics.Bitmap

/** Cache of profile pictures. Fetch once per session. */
object ProfileHelper {
    private val cache = HashMap<String, Bitmap?>()

    fun set(userId: String, bmp: Bitmap?) { cache[userId] = bmp }

    fun get(userId: String): Bitmap? = cache[userId]

    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /** Main thread only. Several views asking for the same person share one request. */
    fun loadIfNeeded(userId: String, serverUrl: String, token: String, onLoaded: (Bitmap?) -> Unit) {
        if (cache.containsKey(userId)) { onLoaded(cache[userId]); return }
        waiting[userId]?.let { it.add(onLoaded); return }
        waiting[userId] = mutableListOf(onLoaded)
        WsClient.fetchPhoto(serverUrl, token, userId) { b64 ->
            val bmp = if (!b64.isNullOrEmpty()) MediaHelper.base64ToBitmap(b64) else null
            cache[userId] = bmp
            waiting.remove(userId)?.forEach { it(bmp) }
        }
    }

    fun clear() { cache.clear() }
}
