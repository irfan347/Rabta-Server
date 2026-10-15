package com.irfan.rabta

import android.content.Context
import android.graphics.Bitmap

/** Cache of profile pictures. Fetch once per session. */
object ProfileHelper {
    private val cache = HashMap<String, Bitmap?>()

    fun set(userId: String, bmp: Bitmap?) { cache[userId] = bmp }

    fun get(userId: String): Bitmap? = cache[userId]

    fun loadIfNeeded(userId: String, serverUrl: String, token: String, onLoaded: (Bitmap?) -> Unit) {
        if (cache.containsKey(userId)) { onLoaded(cache[userId]); return }
        WsClient.fetchPhoto(serverUrl, token, userId) { b64 ->
            val bmp = if (!b64.isNullOrEmpty()) MediaHelper.base64ToBitmap(b64) else null
            cache[userId] = bmp
            onLoaded(bmp)
        }
    }

    fun clear() { cache.clear() }
}
