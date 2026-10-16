package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.util.concurrent.Executors

/** Offline, signed offer -> reply -> issuer confirmation. Existing messaging handles later chats. */
class InstantConnectActivity:ThemedActivity() {
    private lateinit var store:InstantPairingStore
    private lateinit var content:LinearLayout
    private lateinit var status:TextView
    private lateinit var expiry:TextView
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val buttons=mutableListOf<TextView>()
    private var busy=false
    private var visible=false
    private var version=0
    private var deadline=0L
    private var image:ImageView?=null
    private var approval:TextView?=null
    private var reviewRaw:String?=null
    private val tick=object:Runnable {
        override fun run() {
            if(!visible)return
            if(deadline>0) {
                val left=(deadline-System.currentTimeMillis()).coerceAtLeast(0)
                expiry.text=if(left>0)"Expires in ${left/60000}:${"%02d".format((left/1000)%60)}" else "This invitation has expired. Create or scan a fresh invitation."
                if(left==0L){image?.visibility=View.GONE;approval?.isEnabled=false;deadline=0}
            }
            main.postDelayed(this,1000)
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val body=FeatureUi.page(this,"Instant Connect")
        status=RabtaUi.text(this,"Pair privately, then use your existing chat connection.",14f,color=AppTheme.sub(this));body.addView(status)
        expiry=RabtaUi.text(this,"",13f,color=AppTheme.teal(this));body.addView(expiry)
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };body.addView(content)
        store=InstantPairingStore(this,Session.id)
        if(!Session.loggedIn) {
            content.addView(RabtaUi.text(this,"Sign in to your existing Rabta account first."))
            content.addView(RabtaUi.button(this,"Sign in") { startActivity(Intent(this,AuthActivity::class.java));finish() });return
        }
        body.addView(button("Scan pairing QR",true) { startActivityForResult(Intent(this,InstantScanActivity::class.java),903) })
        body.addView(button("New temporary invitation") {
            AlertDialog.Builder(this).setTitle("Create a new invitation?").setMessage("A fresh invitation expires in 3 minutes and replaces any unfinished invitation on this phone.")
                .setNegativeButton("Cancel",null).setPositiveButton("Create") { _,_ -> reviewRaw=null;work("Creating signed invitation…",{store.create(Session.name)}) { showOffer(it) } }.show()
        })
        body.addView(button("Cancel unfinished pairing") { work("Cancelling invitation…",{store.cancel()}) { reviewRaw=null;showStored() } })
        body.addView(button("Nearby transport & permissions") { startActivity(Intent(this,NearbyActivity::class.java)) })
        body.addView(FeatureUi.text(this,"No phone numbers, passwords, private keys or account tokens are exchanged. Pairing does not turn on discoverability. Messages use the existing online or approved Nearby transport."))
        val raw=state?.getString("review")?:intent.getStringExtra("pairing_code")
        if(raw!=null)process(raw) else showStored()
    }
    override fun onResume() { super.onResume();if(::store.isInitialized&&Session.id!=store.account){finish();return};visible=true;main.post(tick) }
    override fun onPause() { visible=false;main.removeCallbacks(tick);super.onPause() }
    override fun onDestroy() { version++;worker.shutdown();super.onDestroy() }
    override fun onSaveInstanceState(state:Bundle) { reviewRaw?.let { state.putString("review",it) };super.onSaveInstanceState(state) }
    override fun onActivityResult(code:Int,result:Int,data:Intent?) {
        super.onActivityResult(code,result,data)
        if(code==903&&result==Activity.RESULT_OK)data?.getStringExtra("pairing_code")?.let { process(it) }
    }
    private fun button(label:String,primary:Boolean=false,action:()->Unit)=RabtaUi.button(this,label,primary) { if(!busy)action() }.also { buttons.add(it);it.isEnabled=!busy }
    private fun <T> work(label:String,action:()->T,success:(T)->Unit) {
        if(busy||isDestroyed||Session.id!=store.account)return
        busy=true;buttons.forEach { it.isEnabled=false };status.text=label
        val request=++version
        worker.execute {
            val result=runCatching(action)
            main.post {
                if(isDestroyed||request!=version||Session.id!=store.account)return@post
                busy=false;buttons.forEach { it.isEnabled=true }
                result.onSuccess { status.text="";success(it) }.onFailure { status.text=it.message?:"Pairing failed. Please retry with a fresh invitation." }
            }
        }
    }
    private fun reset(title:String,description:String) {
        content.removeAllViews();buttons.removeAll { it.parent==null };deadline=0;image=null;approval=null;expiry.text=""
        content.addView(RabtaUi.text(this,title,23f,true))
        content.addView(FeatureUi.text(this,description))
    }
    private fun showStored() {
        work("Loading pairing state…",{store.view()}) { state ->
            runCatching {
                when {
                    state.offer!=null -> showOffer(store.verify(state.offer))
                    state.pendingReply!=null&&state.pendingOffer!=null -> showReply(store.verify(state.pendingOffer),store.verify(state.pendingReply))
                    store.paired() -> showPaired(state)
                    else -> reset("Connect with confidence","1. Show an invitation. 2. Scan the other person's reply and compare the pairing code. 3. Let them scan your confirmation. Each person approves the identity; the issuer accepts one reply only.")
                }
            }.onFailure { reset("Pairing needs a fresh invitation",it.message?:"Invitation expired");if(store.paired())pairedAction(state.peerId,state.peerName) }
        }
    }
    private fun qr(packet:InstantInvitation.Verified) {
        deadline=packet.record.expires
        val qr=ImageView(this).apply { contentDescription="Temporary Instant Connect QR code";adjustViewBounds=true;setBackgroundColor(android.graphics.Color.WHITE);setPadding(12,12,12,12) }
        image=qr;content.addView(qr,LinearLayout.LayoutParams(-1,RabtaUi.dp(this,300)).apply { bottomMargin=RabtaUi.dp(this@InstantConnectActivity,16) })
        work("Rendering signed QR…",{BarcodeEncoder().encodeBitmap(packet.raw,BarcodeFormat.QR_CODE,800,800)}) { bitmap:Bitmap -> if(image===qr)qr.setImageBitmap(bitmap) }
    }
    private fun showOffer(offer:InstantInvitation.Verified) {
        reset("Your temporary invitation","Let the other person scan this code and approve your identity. Then scan their reply. This code is single-use and expires in 3 minutes.")
        qr(offer)
        content.addView(RabtaUi.text(this,"${offer.record.name}\n${offer.record.id}\n${MeshCrypto.fingerprint(offer.record.key)}",13f,color=AppTheme.sub(this)))
    }
    private fun showReply(offer:InstantInvitation.Verified,reply:InstantInvitation.Verified) {
        reset("Show your pairing reply","Ask ${offer.record.name} to scan this reply and compare the pairing code. No contact key is saved on this phone until their signed confirmation is scanned.")
        qr(reply);content.addView(RabtaUi.text(this,"Pairing code\n${InstantInvitation.pairingCode(offer,reply)}",18f,true))
    }
    private fun showPaired(state:InstantPairingStore.ViewState) {
        reset("Paired with ${state.peerName}","The contact key is saved. Messages follow your existing connection settings; pairing alone does not establish a radio or Internet connection.")
        state.confirmation?.let { raw ->
            runCatching { store.verify(raw) }.onSuccess { confirmation ->
                content.addView(FeatureUi.text(this,"Let ${state.peerName} scan this final confirmation to finish on their phone."));qr(confirmation)
            }.onFailure { content.addView(FeatureUi.text(this,"The confirmation has expired. If the other person did not finish, create a new invitation.")) }
        }
        pairedAction(state.peerId,state.peerName)
    }
    private fun pairedAction(id:String,name:String) { content.addView(button("Open existing private chat",true) { openChat(id,name) }) }
    private fun process(raw:String) {
        work("Verifying signature, expiry and saved identity…",{store.inspect(raw)}) { packet ->
            reviewRaw=raw
            if(packet.record.kind==InstantInvitation.CONFIRMATION) {
                reset("Review final confirmation","${packet.record.name} approved your pairing reply. Confirm this is the person you intended to pair with.")
            }else reset("Review ${packet.record.name}","The code has a valid signature. Compare this identity with the person before approving. A new key uses trust on first use; a changed saved key is rejected.")
            deadline=packet.record.expires
            content.addView(RabtaUi.text(this,"${packet.record.name}\nRabta ID · ${packet.record.id}",18f,true))
            content.addView(FeatureUi.text(this,"Identity fingerprint\n${MeshCrypto.fingerprint(packet.record.key)}"))
            val state=store.view()
            if(packet.record.kind==InstantInvitation.REPLY&&state.offer!=null) {
                val offer=store.verify(state.offer)
                content.addView(RabtaUi.text(this,"Compare on both phones\n${InstantInvitation.pairingCode(offer,packet)}",18f,true))
            }
            approval=button(when(packet.record.kind){InstantInvitation.OFFER->"Confirm identity & create reply";InstantInvitation.REPLY->"Approve this pairing";else->"Confirm identity & open chat"},true) {
                reviewRaw=null
                when(packet.record.kind) {
                    InstantInvitation.OFFER -> work("Creating signed reply…",{store.respond(raw,Session.name)}) { showReply(packet,it) }
                    InstantInvitation.REPLY -> work("Consuming invitation & saving verified key…",{store.approve(raw)}) { showPaired(store.view()) }
                    else -> work("Verifying issuer confirmation & saving key…",{store.finish(raw)}) { peer -> val current=store.view();showPaired(current);openChat(peer,current.peerName) }
                }
            }.also { content.addView(it) }
            content.addView(button("Reject / go back") { reviewRaw=null;showStored() })
        }
    }
    private fun openChat(id:String,name:String) {
        work("Opening saved conversation…",{
            require(store.paired()&&MeshPolicy.validId(id)&&!Privacy.blocked(this,id,store.account)&&MeshSecurity(this,store.account).key(id)!=null){"Pairing is not available. Review the contact identity first"}
            val db=Db.get(this)
            if(db.conversations().none { it.peerId==id })db.upsertConversation(id,name,"",System.currentTimeMillis(),false)
            id
        }) { peer -> startActivity(Intent(this,ChatActivity::class.java).putExtra("peerId",peer).putExtra("peerName",name)) }
    }
}
