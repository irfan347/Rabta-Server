package com.irfan.rabta

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class InstantConnectTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private fun id()=java.util.UUID.randomUUID().toString().replace("-","").take(12).uppercase()
    private fun reject(action:()->Unit) { assertTrue(runCatching(action).isFailure) }
    private fun clean(account:String) {
        ctx.getSharedPreferences("instant_connect_$account",0).edit().clear().commit()
        ctx.getSharedPreferences("mesh_keys_$account",0).edit().clear().commit()
        ctx.getSharedPreferences("privacy_$account",0).edit().clear().commit()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("rabta_mesh_v2_$account") }
    }
    private fun qr(raw:String):String {
        val bitmap=BarcodeEncoder().encodeBitmap(raw,BarcodeFormat.QR_CODE,900,900)
        return try {
            decode(bitmap)
        }finally{bitmap.recycle()}
    }
    private fun decode(bitmap:Bitmap):String {
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        return InstantQrDecoderFactory().createDecoder(mutableMapOf<com.google.zxing.DecodeHintType,Any>()).decode(RGBLuminanceSource(bitmap.width,bitmap.height,pixels))?.text?:error("Real scanner could not decode the rendered QR")
    }
    @Test fun actualQrImagesRoundTripAndOneTimePairingPersists() {
        val aid=id();val bid=id();val a=InstantPairingStore(ctx,aid);val b=InstantPairingStore(ctx,bid)
        try {
            val offer=a.create("A");assertEquals(offer.raw,qr(offer.raw))
            b.inspect(qr(offer.raw));assertNull(MeshSecurity(ctx,bid).key(aid))
            val reply=b.respond(qr(offer.raw),"B");assertEquals(reply.raw,b.respond(offer.raw,"B").raw)
            assertNull(MeshSecurity(ctx,bid).key(aid));assertNull(MeshSecurity(ctx,aid).key(bid))
            val complete=a.approve(qr(reply.raw));assertTrue(a.paired());assertNotNull(MeshSecurity(ctx,aid).key(bid))
            assertEquals(aid,b.finish(qr(complete.raw)));assertTrue(b.paired());assertNotNull(MeshSecurity(ctx,bid).key(aid))
            reject { InstantPairingStore(ctx,aid).approve(reply.raw) }
            reject { InstantPairingStore(ctx,bid).finish(complete.raw) }
            reject { InstantPairingStore(ctx,bid).respond(offer.raw,"B") }
            assertEquals(bid,InstantPairingStore(ctx,aid).view().peerId)
        }finally{clean(aid);clean(bid)}
    }
    @Test fun issuerConsumesOnlyOneReplyAndRejectsRevokedInvitations() {
        val aid=id();val bid=id();val cid=id();val a=InstantPairingStore(ctx,aid);val b=InstantPairingStore(ctx,bid);val c=InstantPairingStore(ctx,cid)
        try {
            val offer=a.create("A");val rb=b.respond(offer.raw,"B");val rc=c.respond(offer.raw,"C")
            val complete=a.approve(rb.raw);reject { a.approve(rc.raw) };reject { c.finish(complete.raw) }
            assertFalse(c.paired());assertNull(MeshSecurity(ctx,cid).key(aid))
            val second=a.create("A");val response=c.respond(second.raw,"C")
            a.create("A");reject { a.approve(response.raw) }
            assertEquals(bid,a.view().peerId)
        }finally{clean(aid);clean(bid);clean(cid)}
    }
    @Test fun keyMismatchAndBlockedUsersCannotPairOrOverwritePins() {
        val aid=id();val bid=id();val cid=id();val a=InstantPairingStore(ctx,aid);val b=InstantPairingStore(ctx,bid)
        try {
            val offer=a.create("A");val wrong=MeshSecurity(ctx,cid).publicText()
            assertTrue(MeshSecurity(ctx,bid).pin(aid,wrong));reject { b.respond(offer.raw,"B") }
            assertEquals(wrong,MeshSecurity(ctx,bid).key(aid))
            ctx.getSharedPreferences("privacy_$aid",0).edit().putStringSet("blocked",setOf(bid)).commit()
            val reply=InstantInvitation.reply(offer,bid,"B",MeshSecurity(ctx,bid).pair(),System.currentTimeMillis())
            reject { a.approve(reply.raw) };assertNull(MeshSecurity(ctx,aid).key(bid))
        }finally{clean(aid);clean(bid);clean(cid)}
    }
    @Test fun expirationSurvivesReopenAndClockRollback() {
        val aid=id();val bid=id();var now=System.currentTimeMillis()
        val a=InstantPairingStore(ctx,aid){now};val b=InstantPairingStore(ctx,bid){now}
        try {
            val offer=a.create("A");val reply=b.respond(offer.raw,"B")
            now=offer.record.expires;reject { a.approve(reply.raw) }
            now=offer.record.issued
            reject { InstantPairingStore(ctx,aid){now}.approve(reply.raw) }
            assertNull(MeshSecurity(ctx,aid).key(bid))
        }finally{clean(aid);clean(bid)}
    }
    @Test fun racingKeyPinsNeverReplaceTheFirstSavedIdentity() {
        val aid=id();val bid=id();val cid=id();val target=id();val start=CountDownLatch(1);val end=CountDownLatch(2)
        try {
            val keys=listOf(MeshSecurity(ctx,bid).publicText(),MeshSecurity(ctx,cid).publicText())
            val results=java.util.Collections.synchronizedList(mutableListOf<Boolean>())
            keys.forEach { key -> Thread { start.await();try { results.add(MeshSecurity(ctx,aid).pin(target,key)) }finally{end.countDown()} }.start() }
            start.countDown();assertTrue(end.await(5,TimeUnit.SECONDS));assertEquals(1,results.count { it })
            val pinned=MeshSecurity(ctx,aid).key(target);assertTrue(keys.contains(pinned))
            keys.filter { it!=pinned }.forEach { assertFalse(MeshSecurity(ctx,aid).pin(target,it)) }
        }finally{clean(aid);clean(bid);clean(cid)}
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) }else emptyList()
    @Test fun finalConfirmationOpensExistingChatWithoutReplacingHistoryOrSession() {
        Session.init(ctx)
        val old=listOf(Session.id,Session.name,Session.username,Session.token,Session.serverUrl)
        val aid=id();val bid=id();val message=id();val a=InstantPairingStore(ctx,aid);val b=InstantPairingStore(ctx,bid)
        var screen:InstantConnectActivity?=null;var chat:android.app.Activity?=null
        val monitor=inst.addMonitor(ChatActivity::class.java.name,null,false)
        try {
            val offer=a.create("A");val reply=b.respond(offer.raw,"B");val complete=a.approve(reply.raw)
            inst.runOnMainSync { MessageService.stop(ctx);Session.id=bid;Session.name="B";Session.username="qr_fixture";Session.token="unchanged-fixture-token";Session.serverUrl="https://127.0.0.1:8443" }
            // History belongs to the signed-in account B, which keeps it after pairing.
            val db=Db.get(ctx);db.upsertConversation(aid,"Saved contact","Keep history",System.currentTimeMillis(),false)
            db.saveMessage(message,aid,aid,"Keep history",System.currentTimeMillis(),false,"received")
            screen=inst.startActivitySync(Intent(ctx,InstantConnectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("pairing_code",complete.raw)) as InstantConnectActivity
            val end=System.currentTimeMillis()+5000;var ready=false
            while(!ready&&System.currentTimeMillis()<end) { inst.runOnMainSync { ready=views(screen!!.window.decorView).filterIsInstance<TextView>().any { it.text=="Confirm identity & open chat" } };Thread.sleep(30) }
            assertTrue(ready)
            inst.runOnMainSync { views(screen!!.window.decorView).filterIsInstance<TextView>().first { it.text=="Confirm identity & open chat" }.performClick() }
            chat=inst.waitForMonitorWithTimeout(monitor,5000);assertNotNull("Successful pairing opens private chat",chat)
            assertEquals(aid,chat!!.intent.getStringExtra("peerId"))
            assertEquals("Keep history",db.messages(aid).first { it.id==message }.body)
            assertEquals("unchanged-fixture-token",Session.token);assertFalse(RadarPrefs.enabled(ctx,bid))
        }finally {
            inst.removeMonitor(monitor)
            inst.runOnMainSync { chat?.finish();screen?.finish();MessageService.stop(ctx);Session.id=old[0];Session.name=old[1];Session.username=old[2];Session.token=old[3];Session.serverUrl=old[4] }
            ctx.deleteDatabase("rabta_$bid.db")
            clean(aid);clean(bid);ctx.deleteDatabase("mesh_v2_$bid.db");ctx.getSharedPreferences("radar_$bid",0).edit().clear().commit()
        }
    }
    @Test fun pairingReviewRendersRealQrAndRequiresUserConfirmation() {
        Session.init(ctx)
        val old=listOf(Session.id,Session.name,Session.username,Session.token,Session.serverUrl)
        val aid=id();val bid=id();val a=InstantPairingStore(ctx,aid);var screen:InstantConnectActivity?=null
        try {
            val offer=a.create("QR fixture")
            inst.runOnMainSync { MessageService.stop(ctx);Session.id=bid;Session.name="B";Session.username="qr_fixture";Session.token="fixture";Session.serverUrl="https://127.0.0.1:8443" }
            screen=inst.startActivitySync(Intent(ctx,InstantConnectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("pairing_code",offer.raw)) as InstantConnectActivity
            val end=System.currentTimeMillis()+5000
            var ready=false
            while(!ready&&System.currentTimeMillis()<end) { inst.runOnMainSync { ready=views(screen!!.window.decorView).filterIsInstance<TextView>().any { it.text=="Confirm identity & create reply" } };Thread.sleep(30) }
            assertTrue(ready);assertNull(MeshSecurity(ctx,bid).key(aid));assertNull(InstantPairingStore(ctx,bid).view().pendingReply)
            inst.runOnMainSync { views(screen!!.window.decorView).filterIsInstance<TextView>().first { it.text=="Confirm identity & create reply" }.performClick() }
            val replyEnd=System.currentTimeMillis()+5000
            while(InstantPairingStore(ctx,bid).view().pendingReply==null&&System.currentTimeMillis()<replyEnd)Thread.sleep(30)
            val reply=InstantPairingStore(ctx,bid).view().pendingReply!!;assertEquals(reply,qr(reply));assertNull(MeshSecurity(ctx,bid).key(aid))
            // Verify the actual 800px production ImageView bitmap, not only a test-generated QR.
            var rendered:Bitmap?=null
            val renderEnd=System.currentTimeMillis()+5000
            while(rendered==null&&System.currentTimeMillis()<renderEnd) {
                inst.runOnMainSync {
                    val qrView=views(screen!!.window.decorView).filterIsInstance<ImageView>()
                        .firstOrNull { it.contentDescription=="Temporary Instant Connect QR code" }
                    rendered=(qrView?.drawable as? BitmapDrawable)?.bitmap?.copy(Bitmap.Config.ARGB_8888,false)
                }
                if(rendered==null)Thread.sleep(30)
            }
            assertNotNull("Production QR must be rendered",rendered)
            try { assertEquals(reply,decode(rendered!!)) }finally{rendered?.recycle()}
            inst.waitForIdleSync()
            val dir=java.io.File(ctx.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
            val screenshot=inst.captureScreen()
            screenshot?.let { image -> java.io.File(dir,"instant-connect-reply.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle() }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/rabta-interface")).use { it.readBytes() }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cp ${dir.absolutePath}/instant-connect-reply.png /sdcard/Download/rabta-interface/instant-connect-reply.png")).use { it.readBytes() }
        }finally {
            inst.runOnMainSync { screen?.finish();MessageService.stop(ctx);Session.id=old[0];Session.name=old[1];Session.username=old[2];Session.token=old[3];Session.serverUrl=old[4] }
            clean(aid);clean(bid)
        }
    }
}
