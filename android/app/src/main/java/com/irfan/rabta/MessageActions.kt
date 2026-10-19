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
    /** One of the six quick reactions, sent to the chat (reactions need internet). */
    fun react(a:Activity,m:Db.Message,group:Boolean,refresh:()->Unit) {
        if(!WsClient.connected){Toast.makeText(a,"Reactions need internet",Toast.LENGTH_SHORT).show();return}
        val db=Db.get(a);val emojis=arrayOf("👍","❤️","😂","😮","😢","🙏")
        AlertDialog.Builder(a).setItems(emojis) { _,n -> val id=ByteArray(6).also {SecureRandom().nextBytes(it)}.joinToString(""){"%02X".format(it)};val body=JSONObject().put("target",m.id).put("emoji",emojis[n]).toString();val sent=if(group)WsClient.sendGroupMsg(m.convId,id,body,"reaction")else WsClient.send(m.convId,id,body,"reaction");if(sent){db.react(m.id,Session.id,emojis[n]);refresh()} }.show()
    }
    fun copy(a:Activity,messages:List<Db.Message>) {
        (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rabta message",messages.joinToString("\n") { preview(it) }))
        Toast.makeText(a,"Copied",Toast.LENGTH_SHORT).show()
    }
    /** Stars them all, or unstars them when every one is already starred. */
    fun star(a:Activity,messages:List<Db.Message>) {
        val db=Db.get(a);val starred=messages.associateWith { db.meta(it.id).first };val unstar=starred.values.all { it }
        messages.filter { starred[it]==unstar }.forEach { db.star(it.id) }
        Toast.makeText(a,if(unstar) "Unstarred" else "Starred",Toast.LENGTH_SHORT).show()
    }
    fun delete(a:Activity,messages:List<Db.Message>,done:()->Unit) {
        AlertDialog.Builder(a).setMessage(if(messages.size==1) "Delete this message from this phone?" else "Delete ${messages.size} messages from this phone?").setNegativeButton("Cancel",null)
            .setPositiveButton("Delete for me") { _,_ -> val db=Db.get(a);messages.forEach { db.deleteMessage(it.id) };done() }.show()
    }
    private fun deleted(m:Db.Message)=m.msgType=="text"&&m.body=="Message deleted"
    /** What a forward sends: the message itself, or a reply's own words without the quote, as WhatsApp does. */
    private fun forwarded(m:Db.Message)=if(m.msgType=="rich") runCatching { JSONObject(m.body).optString("text") }.getOrDefault("") to "text" else m.body to m.msgType
    /** Forward to: up to five chats, then each message goes to each of them, oldest first. [done] once they are on their way. */
    fun forward(a:Activity,messages:List<Db.Message>,done:()->Unit={}) {
        val list=messages.filter { !deleted(it)&&forwarded(it).first.isNotEmpty() }.sortedBy { it.sentAt }
        if(list.isEmpty()) { Toast.makeText(a,"Deleted messages can't be forwarded",Toast.LENGTH_SHORT).show();return }
        PeoplePicker.pick(a,L.t(a,"Forward to"),groups=true,max=5,limitNote="You can forward to up to 5 chats",icon="send",label="Send") { chosen ->
            if(chosen.isEmpty()) return@pick
            val db=Db.get(a);val targets=chosen.map { (id,name) -> Triple(id,name,db.isGroupConversation(id)) }
            // Nearby carries text and built-in stickers to people; everything else needs the server.
            val nearby=list.all { it.msgType=="text"||it.msgType=="rich"||(it.msgType=="sticker"&&Stickers.valid(it.body)) }&&targets.none { it.third }
            if(!WsClient.connected&&!nearby) { Toast.makeText(a,"This forward needs internet",Toast.LENGTH_SHORT).show();return@pick }
            for((id,name,group) in targets) for(m in list) { val (body,type)=forwarded(m);Outbox.send(id,name,group,body,type) }
            Toast.makeText(a,if(targets.size==1) "Forwarded to ${targets[0].second}" else "Forwarded to ${targets.size} chats",Toast.LENGTH_SHORT).show()
            done()
        }
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
