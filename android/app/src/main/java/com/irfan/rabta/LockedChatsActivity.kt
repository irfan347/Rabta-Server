package com.irfan.rabta

import android.content.Intent
import android.os.Bundle
import android.view.View

/** Never render identities until device authentication succeeds; hide them again on leaving. */
class LockedChatsActivity : ThemedActivity() {
    private var authenticating = false
    override fun ownsPrivacyAuthentication() = true
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(RabtaUi.text(this, "Locked chats", 20f))
        window.decorView.visibility = View.INVISIBLE
    }
    override fun onResume() {
        super.onResume()
        if (authenticating) return
        authenticating = true
        AppLock.authenticate(this, failed = { authenticating = false; finish() }) {
            authenticating = false
            if (!isFinishing && !isDestroyed) {
                render()
                window.decorView.visibility = View.VISIBLE
            }
        }
    }
    override fun onPause() {
        window.decorView.visibility = View.INVISIBLE
        super.onPause()
    }
    private fun render() {
        val page = FeatureUi.page(this, "Locked chats")
        val chats = Db.get(this).conversations().filter { Privacy.chatLocked(this, it.peerId) }
        if (chats.isEmpty()) page.addView(FeatureUi.text(this, "No locked chats. Open a chat’s menu and choose Lock chat."))
        chats.forEach { chat ->
            page.addView(FeatureUi.button(this, chat.shownName) {
                startActivity(Intent(this, ChatActivity::class.java)
                    .putExtra("peerId", chat.peerId).putExtra("peerName", chat.peerName)
                    .putExtra("isGroup", chat.isGroup))
            })
        }
    }
}
