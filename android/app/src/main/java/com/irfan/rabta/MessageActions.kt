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
    fun react(a:Activity,m:Db.Message,group:Boolean,anchor:android.view.View,refresh:()->Unit):android.widget.PopupWindow? {
        if(!WsClient.connected){Toast.makeText(a,"Reactions need internet",Toast.LENGTH_SHORT).show();return null}
        val emojis=arrayOf("👍","❤️","😂","😮","😢","🙏")
        val d=a.resources.displayMetrics.density
        val row=android.widget.LinearLayout(a).apply {
            gravity=android.view.Gravity.CENTER;setPadding((8*d).toInt(),(8*d).toInt(),(8*d).toInt(),(8*d).toInt())
            background=RabtaUi.shape(a,AppTheme.surface(a),32)
        }
        val popup=android.widget.PopupWindow(row,android.view.ViewGroup.LayoutParams.WRAP_CONTENT,android.view.ViewGroup.LayoutParams.WRAP_CONTENT,true).apply {
            elevation=12*d;setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));isOutsideTouchable=true
        }
        emojis.forEach { emoji ->
            row.addView(android.widget.TextView(a).apply {
                text=emoji;setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,26f);gravity=android.view.Gravity.CENTER;includeFontPadding=true
                setPadding(0,0,0,0);contentDescription="React $emoji";isFocusable=true
                background=RabtaUi.ripple(a,android.graphics.Color.TRANSPARENT,999)
                setOnClickListener {
                    val id=ByteArray(6).also {SecureRandom().nextBytes(it)}.joinToString(""){"%02X".format(it)}
                    val body=JSONObject().put("target",m.id).put("emoji",emoji).toString()
                    val sent=if(group)WsClient.sendGroupMsg(m.convId,id,body,"reaction")else WsClient.send(m.convId,id,body,"reaction")
                    if(sent){Db.get(a).react(m.id,Session.id,emoji);refresh()}
                    else Toast.makeText(a,"Reaction not sent. Try again when connected.",Toast.LENGTH_SHORT).show()
                    popup.dismiss()
                }
            },android.widget.LinearLayout.LayoutParams((44*d).toInt(),(52*d).toInt()))
        }
        val bounds=android.graphics.Rect();a.window.decorView.getWindowVisibleDisplayFrame(bounds)
        val pos=IntArray(2);anchor.getLocationOnScreen(pos)
        row.measure(android.view.View.MeasureSpec.makeMeasureSpec(bounds.width(),android.view.View.MeasureSpec.AT_MOST),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED))
        val x=(pos[0]+anchor.width/2-row.measuredWidth/2).coerceIn(bounds.left,maxOf(bounds.left,bounds.right-row.measuredWidth))
        val y=maxOf(bounds.top,pos[1]-row.measuredHeight-(8*d).toInt())
        popup.showAtLocation(a.window.decorView,android.view.Gravity.TOP or android.view.Gravity.LEFT,x,y)
        return popup
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
    fun canEdit(m:Db.Message)=m.mine&&!deleted(m)&&m.msgType in listOf("text","rich")&&System.currentTimeMillis()-m.sentAt in 0..15*60*1000L
    fun edit(a:Activity,m:Db.Message,done:()->Unit) {
        val original=if(m.msgType=="rich")JSONObject(m.body).optString("text") else m.body
        val field=EditText(a).apply {setText(original);setSelection(length());minLines=2;maxLines=6;filters=arrayOf(android.text.InputFilter.LengthFilter(12000))}
        val dialog=AlertDialog.Builder(a).setTitle("Edit message").setMessage("Edit within 15 minutes. Other people need an updated Rabta app to see changes.").setView(field).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text=field.text.toString()
            if(text.isBlank())field.error="Enter a message"
            else if(text==original)dialog.dismiss()
            else {dialog.dismiss();MessageChanges.submit(a,m,"edit",text,done)}
        }}
        dialog.show()
    }
    fun delete(a:Activity,messages:List<Db.Message>,done:()->Unit) {
        val dialog=AlertDialog.Builder(a).setMessage(if(messages.size==1) "Delete this message?" else "Delete ${messages.size} messages from this phone?").setNegativeButton("Cancel",null)
            .setPositiveButton("Delete for me") { _,_ -> val db=Db.get(a);messages.forEach { db.deleteMessage(it.id) };done() }
        messages.singleOrNull()?.takeIf {it.mine&&!deleted(it)&&System.currentTimeMillis()-it.sentAt in 0..48*60*60*1000L}?.let {m->
            dialog.setNeutralButton("Delete for everyone") {_,_->
                AlertDialog.Builder(a).setTitle("Delete for everyone?")
                    .setMessage("Requires internet and an updated Rabta app. Changes sync when other devices reconnect, for up to 30 days. Saved copies and forwarded messages cannot be removed.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Delete") {_,_->MessageChanges.submit(a,m,"delete",done=done)}.show()
            }
        }
        dialog.show()
    }
    private fun deleted(m:Db.Message)=m.msgType=="deleted"||(m.msgType=="text"&&m.body=="Message deleted")
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
