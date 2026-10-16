package com.irfan.rabta

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
class SettingsActivity:ThemedActivity() {
    override fun onCreate(state:Bundle?) { super.onCreate(state);val col=FeatureUi.page(this,"Settings")
        col.addView(FeatureUi.button(this,"Connection details") {ConnectionHelp.show(this)})
        col.addView(FeatureUi.button(this,"Appearance") { AppTheme.choose(this) })
        col.addView(FeatureUi.button(this,"Language") { AlertDialog.Builder(this).setTitle("Language / زبان").setItems(arrayOf("English","اردو")) { _,i -> L.prefs(this).edit().putString("language",if(i==0)"en" else "ur").apply();recreate() }.show() })
        col.addView(FeatureUi.button(this,"Text size") { AlertDialog.Builder(this).setTitle(L.t(this,"Text size")).setSingleChoiceItems(arrayOf("Small","Default","Large","Extra large"),listOf(0.9f,1f,1.15f,1.3f).indexOf(L.prefs(this).getFloat("scale",1f))) { d,i ->L.prefs(this).edit().putFloat("scale",listOf(0.9f,1f,1.15f,1.3f)[i]).apply();d.dismiss();recreate()}.show() })
        col.addView(Switch(this).apply { text=L.t(this@SettingsActivity,"Hide notification previews");setTextColor(AppTheme.text(this@SettingsActivity));isChecked=Privacy.hidePreviews(this@SettingsActivity);setOnCheckedChangeListener { _,v ->Privacy.prefs(this@SettingsActivity).edit().putBoolean("hide_previews",v).apply() } })
        col.addView(FeatureUi.button(this,"App lock") { val enabled=Privacy.prefs(this).getBoolean("lock",false);AlertDialog.Builder(this).setTitle("App lock").setMessage(if(enabled)"App lock is on. Turn it off?" else "Use your phone fingerprint or screen lock to unlock Rabta. Locks after 30 seconds away.").setNegativeButton("Cancel",null).setPositiveButton(if(enabled)"Disable" else "Enable") { _,_ ->AppLock.authenticate(this){Privacy.prefs(this).edit().putBoolean("lock",!enabled).apply();recreate()} }.show() })
        col.addView(FeatureUi.button(this,"Encrypted backup") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Rabta-backup.rbt"),41) })
        col.addView(FeatureUi.button(this,"Restore backup") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),42) })
        col.addView(FeatureUi.text(this,"Backups contain local chats and saved attachments. Use a strong password; it cannot be recovered. Restore merges into the same account. Offline identity keys remain in this phone's secure storage; exchange contact keys again on a new phone."))
        col.addView(FeatureUi.button(this,"Video relay status") { FeatureApi.run(this,{FeatureApi.request("/api/ice").optBoolean("relayConfigured")}) { enabled -> AlertDialog.Builder(this).setTitle("Video relay").setMessage(if(enabled)"Relay is configured. Calls can use it when a direct connection is unavailable." else "Relay is not configured. Video calls may fail on networks that block direct connections.").setPositiveButton("Close",null).show() } })
    }
    override fun onActivityResult(req:Int,res:Int,data:Intent?) {super.onActivityResult(req,res,data);if(res!=RESULT_OK||req !in 41..42)return;val uri=data?.data?:return;val col=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(32,12,32,12) };val password=EditText(this).apply { hint="Backup password (10+ characters)";inputType=129 };col.addView(password);val confirm=EditText(this).apply { hint="Confirm password";inputType=129 };if(req==41)col.addView(confirm)
        val dlg=AlertDialog.Builder(this).setTitle(if(req==41)"Create encrypted backup" else "Restore encrypted backup").setView(col).setNegativeButton("Cancel",null).setPositiveButton("Continue",null).create();dlg.setOnShowListener { dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { val pass=password.text.toString();if(pass.length<10||(req==41&&pass!=confirm.text.toString())){password.error="Use 10+ matching characters";return@setOnClickListener};dlg.dismiss();Toast.makeText(this,"Working…",0).show();FeatureApi.run(this,{ if(req==41)ChatBackup.export(this,uri,pass.toCharArray()) else ChatBackup.restore(this,uri,pass.toCharArray()) }) { Toast.makeText(this,if(req==41)"Encrypted backup saved" else "Chats restored",1).show() } } };dlg.show()
    }
}
