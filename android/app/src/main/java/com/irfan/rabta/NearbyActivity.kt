package com.irfan.rabta

import android.content.Intent
import android.os.Bundle

/** Standalone entry remains available from settings and existing intents. */
class NearbyActivity : ThemedActivity() {
    private lateinit var panel: NearbyPanel
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        panel = NearbyPanel(this)
        FeatureUi.page(this,"Rabta Radar").addView(panel.view)
    }
    override fun onResume() { super.onResume(); panel.resume() }
    override fun onPause() { panel.pause(); super.onPause() }
    override fun onRequestPermissionsResult(r: Int,p: Array<out String>,g: IntArray) {
        super.onRequestPermissionsResult(r,p,g)
        if(r==NearbySetup.PERMISSIONS) { MessageService.restartNearby(this);panel.refresh() }
    }
    override fun onActivityResult(req: Int,res: Int,data: Intent?) {
        super.onActivityResult(req,res,data);panel.handleResult(req,res,data)
    }
}
