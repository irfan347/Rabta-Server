package com.irfan.rabta

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private fun launch(cls:Class<out Activity>)=inst.startActivitySync(Intent(ctx,cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("peerId","BBBBAAAACCCC").putExtra("peerName","Waquu").putExtra("fromName","Waquu").putExtra("fromId","BBBBAAAACCCC"))
    private fun screenshot(name:String) {
        inst.waitForIdleSync(); Thread.sleep(600)
        val dir=File(ctx.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
        inst.uiAutomation.takeScreenshot().useBitmap { bitmap -> File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
        // AGP uninstalls the app after tests, so copy captures outside its data directory.
        for(command in listOf("mkdir -p /sdcard/Download/rabta-interface", "cp ${dir.absolutePath}/$name.png /sdcard/Download/rabta-interface/$name.png")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
    }
    private fun Bitmap.useBitmap(block:(Bitmap)->Unit) { try { block(this) } finally { recycle() } }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun mode(mode:String) { ctx.getSharedPreferences("appearance",0).edit().putString("mode",mode).commit() }
    @Test fun themesAttachmentsAndAvatarRenderOnDevice() {
        for(permission in listOf("ACCESS_FINE_LOCATION","ACCESS_COARSE_LOCATION","RECORD_AUDIO")) inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} android.permission.$permission").close()
        Session.init(ctx); Session.id="AAAABBBBCCCC"; Session.name="Irfan"; Session.username="irfan"; Session.token="test"; Session.serverUrl="https://127.0.0.1:8443"
        val db=Db.get(ctx); val now=System.currentTimeMillis()
        db.upsertConversation("BBBBAAAACCCC","Waquu","See you soon!",now,false)
        db.saveMessage("000000000001","BBBBAAAACCCC","BBBBAAAACCCC","Salam! Aaj ka kya plan hai?",now-60000,false,"received")
        db.saveMessage("000000000002","BBBBAAAACCCC",Session.id,"Chai pe milte hain ☕",now,true,"delivered")
        db.saveMessage("000000000003","BBBBAAAACCCC",Session.id,"rbt:11",now+1,true,"delivered","sticker")
        mode("Light")
        val list=launch(ConversationListActivity::class.java); screenshot("01-chats-light")
        inst.runOnMainSync {
            val avatar=views(list.window.decorView).filterIsInstance<TextView>().first { it.text.toString()=="W" }
            assertTrue("Initial fits inside avatar",avatar.textSize < avatar.height*0.55f)
            assertFalse("Search does not steal focus",list.currentFocus is android.widget.EditText)
            list.finish()
        }
        val light=launch(ChatActivity::class.java); screenshot("02-chat-light")
        inst.runOnMainSync {
            val buttons=views(light.window.decorView)
            buttons.first { it.contentDescription=="attach" }.performClick()
        }
        screenshot("03-attachments")
        inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        inst.waitForIdleSync()
        inst.runOnMainSync { views(light.window.decorView).first { it.contentDescription=="Stickers" }.performClick() }
        screenshot("04-stickers")
        inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        inst.runOnMainSync { light.finish() }
        mode("Dark")
        val dark=launch(ChatActivity::class.java); screenshot("05-chat-dark"); inst.runOnMainSync { dark.finish() }
        val darkList=launch(ConversationListActivity::class.java); screenshot("06-chats-dark"); inst.runOnMainSync { darkList.finish() }
        val incoming=launch(IncomingCallActivity::class.java); screenshot("07-incoming-call"); inst.runOnMainSync { incoming.finish() }
        val settings=launch(SettingsActivity::class.java); screenshot("08-settings-dark");inst.runOnMainSync { settings.finish() }
        val nearby=launch(NearbyActivity::class.java); screenshot("09-nearby-dark")
        val monitor=inst.addMonitor(ConnectivityDiagnosticsActivity::class.java.name,null,false)
        inst.runOnMainSync { views(nearby.window.decorView).filterIsInstance<TextView>().first { it.text=="Connectivity diagnostics" }.performClick() }
        val diagnostics=inst.waitForMonitorWithTimeout(monitor,5000)
        assertNotNull("Nearby opens connectivity diagnostics",diagnostics)
        screenshot("09b-connectivity-diagnostics-dark")
        inst.runOnMainSync {
            val labels=views(diagnostics.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(labels.any { it.contains("Bluetooth:") && it.contains("Wi-Fi:") })
            assertTrue(labels.any { it.contains("managed by Nearby; not exposed") })
            assertTrue(labels.any { it.contains("Multi-hop radio delivery has not been verified") })
            assertTrue(labels.any { it.startsWith("Updated ") })
            diagnostics.finish();nearby.finish()
        }
        inst.removeMonitor(monitor)
        db.star("000000000002")
        val starred=launch(StarredActivity::class.java); screenshot("10-starred-dark");inst.runOnMainSync { assertTrue(views(starred.window.decorView).filterIsInstance<TextView>().any { it.text.contains("Chai pe") });starred.finish() }
        val status=launch(StatusActivity::class.java);Thread.sleep(2500);screenshot("11-status-dark");inst.runOnMainSync { assertTrue(views(status.window.decorView).filterIsInstance<TextView>().any { it.text=="Text status" });status.finish() }
        ctx.getSharedPreferences("appearance",0).edit().putString("language","ur").putFloat("scale",1.15f).commit()
        val urdu=launch(SettingsActivity::class.java);screenshot("12-settings-urdu");inst.runOnMainSync { assertTrue(views(urdu.window.decorView).filterIsInstance<TextView>().any { it.text=="ترتیبات" });urdu.finish() }
        ctx.getSharedPreferences("appearance",0).edit().putString("language","en").putFloat("scale",1f).commit()
        // A password failure must leave the database untouched. A valid restore merges lost rows.
        val file=File(ctx.cacheDir,"instrumentation-backup.rbt");val uri=android.net.Uri.fromFile(file)
        ChatBackup.export(ctx,uri,"test-only-long-password".toCharArray())
        db.writableDatabase.execSQL("DELETE FROM messages WHERE id='000000000002'")
        try { ChatBackup.restore(ctx,uri,"incorrect-password".toCharArray());fail("Wrong password accepted") }catch(expected:javax.crypto.AEADBadTagException){}
        assertFalse(db.hasMessage("000000000002"))
        ChatBackup.restore(ctx,uri,"test-only-long-password".toCharArray())
        assertTrue(db.hasMessage("000000000002"));assertTrue(db.meta("000000000002").first)
        file.delete()
        MessageService.stop(ctx)
    }
}
