package com.irfan.rabta

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadarScreenTest {
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) }else emptyList()
    @Test fun radarStartsPrivateAndToggleUsesAccountOptIn() {
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        Session.init(ctx)
        val old=listOf(Session.id,Session.name,Session.username,Session.token,Session.serverUrl)
        val account=java.util.UUID.randomUUID().toString().replace("-","").take(12).uppercase()
        var screen:NearbyActivity?=null
        try {
            inst.runOnMainSync {
                MessageService.stop(ctx);Session.id=account;Session.name="Radar test";Session.username="radar_test";Session.token="fixture";Session.serverUrl="https://127.0.0.1:8443"
            }
            screen=inst.startActivitySync(Intent(ctx,NearbyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NearbyActivity
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val all=views(screen!!.window.decorView)
                val toggle=all.filterIsInstance<Switch>().single()
                assertFalse(toggle.isChecked);assertFalse(RadarPrefs.enabled(ctx));assertFalse(MeshBridge.snapshot().active)
                assertTrue(all.filterIsInstance<TextView>().any { it.text.toString().contains("No phone numbers are shared") })
                toggle.isChecked=true;assertTrue(RadarPrefs.enabled(ctx))
                toggle.isChecked=false;assertFalse(RadarPrefs.enabled(ctx));assertFalse(MeshBridge.snapshot().active)
            }
        }finally {
            inst.runOnMainSync { screen?.finish();MessageService.stop(ctx);Session.id=old[0];Session.name=old[1];Session.username=old[2];Session.token=old[3];Session.serverUrl=old[4] }
            ctx.getSharedPreferences("radar_$account",0).edit().clear().commit()
            ctx.getSharedPreferences("mesh_keys_$account",0).edit().clear().commit()
            ctx.deleteDatabase("mesh_v2_$account.db")
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("rabta_mesh_v2_$account") }
        }
    }
}
