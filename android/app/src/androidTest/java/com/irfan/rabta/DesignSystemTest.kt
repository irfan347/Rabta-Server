package com.irfan.rabta

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Test-only local fixtures: no production server/account requests. */
@RunWith(AndroidJUnit4::class)
class DesignSystemTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun ui(block:()->Unit) = inst.runOnMainSync(block)
    private fun waitUntil(check:()->Boolean) {
        val deadline=System.currentTimeMillis()+8000
        do { inst.waitForIdleSync();var ready=false;ui { ready=check() };if(ready)return;Thread.sleep(100) } while(System.currentTimeMillis()<deadline)
        fail("UI did not reach the expected state")
    }
    private fun capture(name:String) {
        inst.waitForIdleSync();Thread.sleep(180)
        val dir=File(ctx.getExternalFilesDir(null),"design-system").apply { mkdirs() }
        val bitmap=checkNotNull(inst.captureScreen()) { "Android returned no screenshot" }
        try { File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
        for(command in listOf("mkdir -p /sdcard/Download/rabta-interface","cp ${dir.absolutePath}/$name.png /sdcard/Download/rabta-interface/$name.png")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
    }
    private fun prepare() {
        for(p in NearbySetup.permissions()+if(android.os.Build.VERSION.SDK_INT>=33)listOf("android.permission.POST_NOTIFICATIONS")else emptyList()) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("pm grant ${ctx.packageName} $p")).use { it.readBytes() }
        }
        Session.init(ctx);Session.id="AAAABBBBCCCC";Session.name="Irfan";Session.username="design_test"
        Session.token="test";Session.serverUrl="https://127.0.0.1:8443"
        ctx.getSharedPreferences("appearance",0).edit().putString("language","en").putFloat("scale",1f).commit()
        Db.get(ctx).apply {
            upsertConversation("DA7A00000001","Design direct contact","A real local fixture",System.currentTimeMillis(),false)
            upsertConversation("design-system-group","Design circle","Group conversation",System.currentTimeMillis(),false,true)
            clearUnread("DA7A00000001");clearUnread("design-system-group")
            upsertConversation("design-unread-contact","Unread fixture contact","Unread local fixture",System.currentTimeMillis(),true)
        }
    }
    @Test fun homeFiltersShowOnlyMatchingLocalConversations() {
        prepare()
        val a=inst.startActivitySync(Intent(ctx,ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun filter(label:String)=all(a.window.decorView).first { it.contentDescription=="$label filter" }
            fun visible(name:String)=all(a.window.decorView).filterIsInstance<TextView>().any { it.isShown&&it.text==name }
            waitUntil { visible("Design direct contact")&&visible("Design circle")&&visible("Unread fixture contact") }
            ui { filter("Unread").performClick() }
            waitUntil { visible("Unread fixture contact")&&!visible("Design direct contact")&&!visible("Design circle") }
            ui { filter("Groups").performClick() }
            waitUntil { visible("Design circle")&&!visible("Design direct contact")&&!visible("Unread fixture contact") }
            ui { filter("All").performClick() }
            waitUntil { visible("Design direct contact")&&visible("Design circle")&&visible("Unread fixture contact") }
        } finally { ui { a.finish() };MessageService.stop(ctx) }
    }
    @Test fun navigationUsesRealGroupsAndKeepsStatusReachable() {
        prepare()
        for(mode in listOf("Light","Dark")) {
            ctx.getSharedPreferences("appearance",0).edit().putString("mode",mode).commit()
            val a=inst.startActivitySync(Intent(ctx,ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                fun nav(label:String)=all(a.window.decorView).first { it.contentDescription==label && it is ViewGroup }
                waitUntil { all(a.window.decorView).filterIsInstance<TextView>().any { it.text=="Design direct contact" } }
                // Local rows can load before the first traversal; wait for real navigation bounds.
                waitUntil { listOf("Chats","Nearby","Spaces","Calls").all { nav(it).isLaidOut&&nav(it).height>0&&nav(it).width>0 } }
                ui {
                    assertFalse(a.currentFocus is EditText)
                    for(label in listOf("Chats","Nearby","Spaces","Calls")) {
                        val item=nav(label)
                        assertTrue("Navigation touch height",item.height>=RabtaUi.dp(a,48))
                        assertTrue("Navigation touch width",item.width>=RabtaUi.dp(a,48))
                    }
                    assertTrue(nav("Chats").isSelected)
                }
                capture("20-home-${mode.lowercase()}")
                ui { nav("Spaces").performClick() }
                waitUntil { all(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text=="Design circle" } }
                ui {
                    assertTrue(nav("Spaces").isSelected)
                    assertFalse("Spaces contains groups rather than mock content",all(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text=="Design direct contact" })
                }
                capture("21-spaces-${mode.lowercase()}")
                ui { nav("Nearby").performClick() }
                waitUntil { all(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.contentDescription=="Search nearby users" } }
                capture("22-nearby-${mode.lowercase()}")
                ui { nav("Calls").performClick() }
                waitUntil { nav("Calls").isSelected }
                capture("23-calls-${mode.lowercase()}")
                ui { nav("Chats").performClick() }
                waitUntil { nav("Chats").isSelected }
                // Status is reached from the first Moments item.
                ui { all(a.window.decorView).first { it.contentDescription=="My Moment. Status" }.performClick() }
                waitUntil {
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any { it is StatusActivity }
                }
                val status=inst.runCatchingResumedStatus()
                assertNotNull("Status is a real screen",status)
                ui { status?.finish() }
                inst.waitForIdleSync()
                ui { all(a.window.decorView).first { it.contentDescription=="Search chats" }.performClick() }
                waitUntil { all(a.window.decorView).filterIsInstance<EditText>().any { it.isShown && it.contentDescription=="Search names or messages" } }
                capture("24-search-${mode.lowercase()}")
            } finally { ui { a.finish() };MessageService.stop(ctx) }
        }
    }
    private fun android.app.Instrumentation.runCatchingResumedStatus():Activity? {
        var status:Activity?=null
        ui { status=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull { it is StatusActivity } }
        return status
    }
    @Test fun colorsRetainContrastAndSystemFontScale() {
        val prefs=ctx.getSharedPreferences("appearance",0)
        val previousMode=AppTheme.mode(ctx);val previousScale=prefs.getFloat("scale",1f)
        val previousTheme=AppTheme.themeKey(ctx);val previousAccent=AppTheme.accentKey(ctx)
        try {
            // Every theme, and every custom accent, keeps AA contrast in both modes.
            val looks=AppTheme.palettes.map { it.key to null as String? }+AppTheme.accents.map { "custom" to it.key }
            for((theme,accent) in looks) for(mode in listOf("Dark","Light")) {
                prefs.edit().putString("mode",mode).commit();AppTheme.setTheme(ctx,theme,accent)
                for(bg in listOf(AppTheme.bg(ctx),AppTheme.surface(ctx),AppTheme.raised(ctx),AppTheme.sent(ctx))) {
                    for(fg in listOf(AppTheme.text(ctx),AppTheme.sub(ctx),AppTheme.accent(ctx),AppTheme.teal(ctx),AppTheme.error(ctx))) {
                        assertTrue("AA text contrast in $theme/$accent $mode",ColorUtils.calculateContrast(fg,bg)>=4.5)
                    }
                }
                assertTrue(ColorUtils.calculateContrast(Color.WHITE,AppTheme.ACCENT)>=4.5)
                assertTrue("Button label contrast in $theme/$accent",ColorUtils.calculateContrast(AppTheme.onPrimary(ctx),AppTheme.primary(ctx))>=4.5)
            }
            AppTheme.setTheme(ctx,previousTheme,previousAccent)
            prefs.edit().putFloat("scale",1.15f).commit()
            val context=ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { fontScale=1.4f })
            assertEquals(1.61f,L.context(context).resources.configuration.fontScale,0.01f)
        } finally { prefs.edit().putString("mode",previousMode).putFloat("scale",previousScale).putString("theme",previousTheme).putString("accent",previousAccent).commit() }
    }
    @Test fun largeTextNavigationSurvivesRecreation() {
        prepare()
        val prefs=ctx.getSharedPreferences("appearance",0)
        prefs.edit().putFloat("scale",1.3f).putString("mode","Dark").commit()
        var a=inst.startActivitySync(Intent(ctx,ConversationListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitUntil { all(a.window.decorView).any { it.isShown && it.contentDescription=="Main navigation" } }
            ui {
                val nav=all(a.window.decorView).first { it.contentDescription=="Main navigation" } as ViewGroup
                for(i in 0 until nav.childCount) {
                    val item=nav.getChildAt(i) as ViewGroup
                    val label=item.getChildAt(1) as TextView
                    assertTrue("Scaled navigation fits horizontally",item.right<=nav.width)
                    assertTrue("Scaled labels have room for their text",label.layout.getEllipsisCount(0)==0)
                }
                all(a.window.decorView).first { it.contentDescription=="Spaces" && it is ViewGroup }.performClick()
            }
            waitUntil { all(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text=="Design circle" } }
            capture("26-spaces-large-text")
            ui { a.recreate() }
            waitUntil {
                val next=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull { it is ConversationListActivity && it!==a }
                if(next!=null) { a=next;true } else false
            }
            waitUntil { all(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text=="Design circle" } }
            ui { assertTrue(all(a.window.decorView).first { it.contentDescription=="Spaces" && it is ViewGroup }.isSelected) }
        } finally { ui { a.finish() };prefs.edit().putFloat("scale",1f).commit();MessageService.stop(ctx) }
    }
    @Test fun chatComposerStaysAboveKeyboard() {
        prepare()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")).use { it.readBytes() }
        val a=inst.startActivitySync(Intent(ctx,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("peerId","DA7A00000001").putExtra("peerName","Design direct contact"))
        try {
            lateinit var editor:EditText
            waitUntil { a.hasWindowFocus() }
            ui {
                editor=all(a.window.decorView).filterIsInstance<EditText>().first { it.hint=="Message" }
                editor.requestFocus()
                editor.post {
                    androidx.core.view.WindowCompat.getInsetsController(a.window,editor).show(WindowInsetsCompat.Type.ime())
                }
            }
            waitUntil { ViewCompat.getRootWindowInsets(a.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true }
            ui {
                val wi=ViewCompat.getRootWindowInsets(a.window.decorView)!!
                val rect=Rect();assertTrue(editor.getGlobalVisibleRect(rect))
                assertTrue("Composer is above the IME",rect.bottom<=a.window.decorView.height-wi.getInsets(WindowInsetsCompat.Type.ime()).bottom+1)
            }
            capture("25-composer-keyboard")
        } finally { ui { a.finish() };MessageService.stop(ctx) }
    }
}
