package com.irfan.rabta

import android.content.Context

/**
 * Your own names for people (a nickname you give a contact). They live only on this phone, in the signed-in
 * account's database; the other person never sees them and their own Rabta name stays as it is. Wherever a person
 * is shown, their nickname comes first.
 */
object Names {
    @Volatile private var cache: Pair<String, Map<String, String>>? = null

    private fun all(c: Context): Map<String, String> {
        val account = runCatching { Session.id }.getOrDefault("")
        cache?.takeIf { it.first == account }?.let { return it.second }
        val loaded = runCatching { Db.get(c).nicknames() }.getOrDefault(emptyMap())
        cache = account to loaded
        return loaded
    }

    /** Your nickname for [peerId], or [fallback] (their own name) when you gave none. */
    fun of(c: Context, peerId: String, fallback: String): String =
        all(c)[peerId]?.takeIf { it.isNotBlank() } ?: fallback.ifBlank { peerId }

    fun nickname(c: Context, peerId: String): String = all(c)[peerId].orEmpty()

    /** Saves (or, when blank, removes) your nickname for [peerId]. Call off the main thread. */
    fun set(c: Context, peerId: String, nickname: String, theirName: String = "") {
        Db.get(c).setNickname(peerId, nickname, theirName)
        cache = null
        RabtaEvents.postHistoryChanged(peerId)
    }
}
