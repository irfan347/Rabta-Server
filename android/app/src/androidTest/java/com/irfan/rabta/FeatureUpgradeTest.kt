package com.irfan.rabta

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Isolated fixture account; run with the .testing application, never a production account. */
@RunWith(AndroidJUnit4::class)
class FeatureUpgradeTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private val me="FEA700000001"
    private val peer="FEA700000002"
    private lateinit var old:List<String>
    private lateinit var db:Db
    private val activities=mutableListOf<android.app.Activity>()
    private fun ui(block:()->Unit)=inst.runOnMainSync(block)
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))} else emptyList()
    private fun waitFor(message:String,check:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+7000
        while(SystemClock.elapsedRealtime()<end) {inst.waitForIdleSync();var pass=false;ui {pass=check()};if(pass)return;Thread.sleep(40)}
        fail(message)
    }
    @Before fun setUp() {
        Session.init(ctx);old=listOf(Session.id,Session.name,Session.username,Session.token,Session.serverUrl)
        ui {MessageService.stop(ctx);WsClient.disconnect();Session.id=me;Session.name="Fixture";Session.username="fixture";Session.token="fixture-token";Session.serverUrl="https://127.0.0.1:8443"}
        Privacy.prefs(ctx).edit().clear().commit()
        // A chat keeps its draft and quoted reply when it closes; each test starts with none.
        ctx.getSharedPreferences("drafts_$me",0).edit().clear().commit()
        L.prefs(ctx).edit().putString("experience","Standard").putString("language","en").putFloat("scale",1f).commit()
        db=Db.get(ctx)
        db.writableDatabase.delete("messages","conv_id=?",arrayOf(peer));db.writableDatabase.delete("conversations","peer_id=?",arrayOf(peer))
        db.saveContact(peer,"Fixture friend")
        db.upsertConversation(peer,"Fixture friend","private fixture text",System.currentTimeMillis(),false)
        db.saveIfNew("FEA700000003",peer,peer,"Fixture friend","private fixture text",System.currentTimeMillis(),false,"received","text")
    }
    @After fun tearDown() {
        ui {
            for(stage in listOf(Stage.RESUMED,Stage.PAUSED,Stage.STARTED,Stage.STOPPED)) ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).toList().forEach {it.finish()}
            WsClient.disconnect();MessageService.stop(ctx)
        }
        inst.waitForIdleSync()
        Privacy.prefs(ctx).edit().clear().commit();ctx.getSharedPreferences("drafts_$me",0).edit().clear().commit()
        L.prefs(ctx).edit().putString("experience","Standard").apply()
        db.writableDatabase.delete("messages","conv_id=?",arrayOf(peer));db.writableDatabase.delete("conversations","peer_id=?",arrayOf(peer));db.removeContact(peer)
        ui {Session.id=old[0];Session.name=old[1];Session.username=old[2];Session.token=old[3];Session.serverUrl=old[4]}
    }
    private fun chat():ChatActivity = (inst.startActivitySync(Intent(ctx,ChatActivity::class.java).putExtra("peerId",peer).putExtra("peerName","Fixture friend").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ChatActivity).also {activities.add(it)}
    @Test fun chatIsSecureAndSwipeQuotesTheCorrectMessage() {
        val a=chat();var row:View?=null
        waitFor("Message row loads") {row=all(a.window.decorView).firstOrNull {it.tag=="message:FEA700000003"};row!=null}
        ui {
            assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val d=a.resources.displayMetrics.density;val now=SystemClock.uptimeMillis()
            for((action,x) in listOf(MotionEvent.ACTION_DOWN to 20f,MotionEvent.ACTION_MOVE to 55f,MotionEvent.ACTION_MOVE to 120f,MotionEvent.ACTION_UP to 120f)) {
                val e=MotionEvent.obtain(now,now+50,action,x*d,20*d,0);row!!.dispatchTouchEvent(e);e.recycle()
            }
        }
        waitFor("Swipe selects reply") {all(a.window.decorView).filterIsInstance<TextView>().any {it.isShown&&it.text.toString().startsWith("Reply to Fixture friend")}}
    }
    @Test fun jumpToLatestAppearsOnlyAwayFromTheBottom() {
        val now=System.currentTimeMillis()
        repeat(45) { i -> db.saveIfNew("FEA80000%04X".format(i),peer,peer,"Fixture friend","Older message $i",now+i,false,"received","text") }
        val a=chat();var scroll:android.widget.ScrollView?=null;var jump:View?=null
        waitFor("Chat and jump control load") {
            jump=all(a.window.decorView).firstOrNull {it.contentDescription=="Jump to latest message"}
            scroll=all(a.window.decorView).filterIsInstance<android.widget.ScrollView>().firstOrNull {v -> all(v).any {it.tag=="message:FEA80000002C"}}
            jump!=null&&scroll!=null&&scroll!!.height>0
        }
        ui {scroll!!.scrollTo(0,0)}
        waitFor("Jump is shown for older messages") {jump!!.isShown}
        ui {jump!!.performClick()}
        waitFor("Jump disappears at the latest message") {jump!!.visibility==View.GONE}
    }
    @Test fun leftSwipeHighlightsWithoutReplying() {
        val a=chat();var row:View?=null
        waitFor("Message row loads") {row=all(a.window.decorView).firstOrNull {it.tag=="message:FEA700000003"};row!=null}
        ui {
            val d=a.resources.displayMetrics.density;val now=SystemClock.uptimeMillis()
            for((action,x) in listOf(MotionEvent.ACTION_DOWN to 160f,MotionEvent.ACTION_MOVE to 125f,MotionEvent.ACTION_MOVE to 50f,MotionEvent.ACTION_UP to 50f)) {
                val e=MotionEvent.obtain(now,now+50,action,x*d,20*d,0);row!!.dispatchTouchEvent(e);e.recycle()
            }
        }
        waitFor("Left swipe selects message") {androidx.core.view.ViewCompat.getStateDescription(row!!)?.toString()=="Selected"}
        ui {assertFalse(all(a.window.decorView).filterIsInstance<TextView>().any {it.isShown&&it.text.toString().startsWith("Reply to Fixture friend")})}
    }
    @Test fun requestHidesMessageUntilAcceptedAndBlocksCalls() {
        Privacy.markRequest(ctx,peer);assertFalse(Privacy.allowCall(ctx,peer))
        val a=chat()
        waitFor("Request gate is visible") {all(a.window.decorView).filterIsInstance<TextView>().any {it.isShown&&it.text.toString()=="Message request"}}
        ui {assertFalse(all(a.window.decorView).filterIsInstance<TextView>().any {it.isShown&&it.text.toString().contains("private fixture text")})}
        Privacy.accept(ctx,peer);assertTrue(Privacy.allowCall(ctx,peer))
    }
    @Test fun lockedChatDoesNotLoadMessageBeforeAuthentication() {
        Privacy.setChatLocked(ctx,peer,true)
        val a=chat();inst.waitForIdleSync()
        ui {assertFalse(all(a.window.decorView).any {it.tag=="message:FEA700000003"});assertNotEquals(peer,ChatActivity.openPeer)}
    }
    @Test fun easyModeIncreasesTextAndPreferencesStayLocal() {
        Experience.set(ctx,"Easy")
        assertTrue(L.context(ctx).resources.configuration.fontScale>=1.25f)
        Experience.set(ctx,"Developer");assertEquals("Developer",Experience.mode(ctx))
        assertEquals(me,Session.id)
        Privacy.prefs(ctx).edit().putInt("wallpaper_$peer",2).commit()
        assertTrue(Wallpapers.drawable(ctx,peer) is android.graphics.drawable.GradientDrawable)
        assertTrue(Wallpapers.drawable(ctx,"another") is ChatWallpaper)
    }
    @Test fun tabSwipesRespectDirectionAndIgnoreVerticalScrolling() {
        ui {
            var moved=0;val v=TabSwipeLayout(ctx,{true},{moved+=it});val d=ctx.resources.displayMetrics.density
            fun event(action:Int,x:Float,y:Float)=MotionEvent.obtain(0,10,action,x*d,y*d,0)
            event(MotionEvent.ACTION_DOWN,150f,20f).let {v.onInterceptTouchEvent(it);it.recycle()}
            // Attach before swiping: the layout asks its parent to stop intercepting.
            val parent=android.widget.FrameLayout(ctx);parent.addView(v)
            event(MotionEvent.ACTION_MOVE,100f,22f).let {assertTrue(v.onInterceptTouchEvent(it));it.recycle()}
            event(MotionEvent.ACTION_UP,20f,22f).let {v.onTouchEvent(it);it.recycle()};assertEquals(1,moved)
            event(MotionEvent.ACTION_DOWN,150f,20f).let {v.onInterceptTouchEvent(it);it.recycle()}
            event(MotionEvent.ACTION_MOVE,145f,120f).let {assertFalse(v.onInterceptTouchEvent(it));it.recycle()};assertEquals(1,moved)
        }
    }
}
