package com.irfan.rabta
import android.content.Intent
import android.os.Bundle
class StarredActivity:ThemedActivity() {
    override fun onCreate(state:Bundle?) { super.onCreate(state);val col=FeatureUi.page(this,"Starred messages");val db=Db.get(this);val messages=db.starred();if(messages.isEmpty())col.addView(FeatureUi.text(this,"Long press a message in a chat to star it."));messages.forEach { m -> val conv=db.conversations().find { it.peerId==m.convId };col.addView(FeatureUi.button(this,(conv?.peerName?:m.convId)+"\n"+MessageActions.preview(m)) { startActivity(Intent(this,ChatActivity::class.java).putExtra("peerId",m.convId).putExtra("peerName",conv?.peerName?:m.convId).putExtra("isGroup",conv?.isGroup?:false)) }) } }
}
