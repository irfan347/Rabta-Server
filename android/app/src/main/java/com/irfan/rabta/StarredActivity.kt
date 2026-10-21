package com.irfan.rabta
import android.content.Intent
import android.os.Bundle
import android.view.View
class StarredActivity:ThemedActivity() {
    override fun onCreate(state:Bundle?) { super.onCreate(state) }
    override fun onResume() {
        super.onResume()
        if(Privacy.anyChatLocked(this)) {
            window.decorView.visibility=View.INVISIBLE
            AppLock.authenticate(this,failed={finish()}) { render();window.decorView.visibility=View.VISIBLE }
        } else render()
    }
    override fun onPause() { if(Privacy.anyChatLocked(this)) window.decorView.visibility=View.INVISIBLE;super.onPause() }
    private fun render() {
        val peer=intent.getStringExtra("peerId")
        val col=FeatureUi.page(this,if(peer==null) "All starred messages" else "Starred in this chat")
        val db=Db.get(this);val messages=db.starred().filter { peer==null||it.convId==peer }
        if(messages.isEmpty())col.addView(FeatureUi.text(this,"Hold a message in a chat and tap Star to save it here."))
        val conversations=db.conversations().associateBy {it.peerId}
        messages.forEach { m ->
            val conv=conversations[m.convId]
            col.addView(FeatureUi.button(this,(conv?.shownName?:m.convId)+"\n"+MessageActions.preview(m)) {
                startActivity(Intent(this,ChatActivity::class.java).putExtra("peerId",m.convId).putExtra("peerName",conv?.peerName?:m.convId).putExtra("isGroup",conv?.isGroup?:false))
            })
        }
    }
}
