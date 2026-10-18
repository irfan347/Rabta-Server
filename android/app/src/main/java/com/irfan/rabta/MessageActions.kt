package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject
import java.security.SecureRandom
object MessageActions {
    fun preview(m:Db.Message):String=when(m.msgType){"text"->m.body.take(300);"rich"->runCatching {JSONObject(m.body).optString("text")}.getOrDefault("Message").take(300);"file","video"->Attachments.name(m.body);else->m.msgType.replaceFirstChar {it.uppercase()} }
    fun show(a:Activity,m:Db.Message,group:Boolean,reply:(Db.Message)->Unit,refresh:()->Unit) {
        val db=Db.get(a);val labels=mutableListOf("Reply","React","Forward","Copy","Star / unstar","Delete for me");if(Transfers.progress.containsKey(m.id))labels.add("Cancel transfer")
        AlertDialog.Builder(a).setItems(labels.map {L.t(a,it)}.toTypedArray()) { _,i -> when(i) {
            0->reply(m)
            1->{if(!WsClient.connected){Toast.makeText(a,"Reactions need internet",Toast.LENGTH_SHORT).show();return@setItems};val emojis=arrayOf("👍","❤️","😂","😮","😢","🙏");AlertDialog.Builder(a).setItems(emojis) { _,n -> val id=ByteArray(6).also {SecureRandom().nextBytes(it)}.joinToString(""){"%02X".format(it)};val body=JSONObject().put("target",m.id).put("emoji",emojis[n]).toString();val sent=if(group)WsClient.sendGroupMsg(m.convId,id,body,"reaction")else WsClient.send(m.convId,id,body,"reaction");if(sent){db.react(m.id,Session.id,emojis[n]);refresh()} }.show()}
            2->{val contacts=db.conversations();AlertDialog.Builder(a).setTitle("Forward to").setItems(contacts.map {it.peerName}.toTypedArray()) { _,n ->val c=contacts[n];if(!WsClient.connected&&m.msgType!="text"){Toast.makeText(a,"This forward needs internet",Toast.LENGTH_SHORT).show()}else Outbox.send(c.peerId,c.peerName,c.isGroup,m.body,m.msgType) }.show()}
            3->{(a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rabta message",preview(m)));Toast.makeText(a,"Copied",Toast.LENGTH_SHORT).show()}
            4->{db.star(m.id);refresh()}
            5->AlertDialog.Builder(a).setMessage("Delete this message from this phone?").setNegativeButton("Cancel",null).setPositiveButton("Delete for me") { _,_ ->db.deleteMessage(m.id);refresh() }.show()
            6->Transfers.cancel(m.id)
        } }.show()
    }
    fun contact(a:Activity,peer:String) {
        AlertDialog.Builder(a).setItems(arrayOf("Offline security",L.t(a,"Block / unblock"),L.t(a,"Report contact"))) { _,i ->when(i){
            0->MeshSecurity.showContact(a,peer)
            1->toggleBlock(a,peer) {}
            2->report(a,peer)
        }}.show()
    }
    fun toggleBlock(a:Activity,peer:String,done:()->Unit) {
        val blocked=Privacy.blocked(a,peer)
        AlertDialog.Builder(a).setMessage(if(blocked)"Unblock this contact?" else "Block messages, calls and status from this contact?").setNegativeButton("Cancel",null).setPositiveButton(if(blocked)"Unblock" else "Block") { _,_ ->
            Privacy.setBlocked(a,peer,!blocked);Toast.makeText(a,if(blocked)"Contact unblocked" else "Contact blocked",Toast.LENGTH_SHORT).show();done()
        }.show()
    }
    fun report(a:Activity,peer:String) {
        val reason=EditText(a).apply {hint="Describe the issue (5–1000 characters)";filters=arrayOf(android.text.InputFilter.LengthFilter(1000))}
        AlertDialog.Builder(a).setTitle("Report contact").setView(reason).setNegativeButton("Cancel",null).setPositiveButton("Submit") { _,_ ->
            FeatureApi.run(a,{FeatureApi.request("/api/report","POST",JSONObject().put("target",peer).put("reason",reason.text.toString()))}) {Toast.makeText(a,"Report sent. Thank you.",Toast.LENGTH_LONG).show()}
        }.show()
    }
}
