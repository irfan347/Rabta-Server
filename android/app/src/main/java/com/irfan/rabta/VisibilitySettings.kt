package com.irfan.rabta

import android.app.AlertDialog
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject

/** Server-confirmed controls: never show an unsaved privacy choice as active. */
object VisibilitySettings {
    fun open(a:ThemedActivity) {
        val account=Session.id;val server=Session.serverUrl;val token=Session.token
        FeatureApi.run(a, { FeatureApi.request("/api/me/visibility",serverUrl=server,token=token) }) { current ->
            if(Session.id!=account)return@run
            val content=LinearLayout(a).apply {
                orientation=LinearLayout.VERTICAL
                val p=(20*resources.displayMetrics.density).toInt();setPadding(p,p,p,p)
            }
            content.addView(FeatureUi.text(a,"These settings apply to your account on the server. Hidden information remains visible to you."))
            fun option(label:String,key:String)=CheckBox(a).apply {
                text=label;setTextColor(AppTheme.text(a));isChecked=current.optBoolean(key)
                content.addView(this)
            }
            val online=option("Hide online status","hideOnline")
            val seen=option("Hide last seen","hideLastSeen")
            val photo=option("Hide profile photo from everyone","hidePhoto")
            content.addView(FeatureUi.button(a,"Rabta freeze: hide online and last seen") { online.isChecked=true;seen.isChecked=true })
            content.addView(FeatureUi.text(a,"Photo privacy stops new downloads. Previously viewed or saved copies cannot be removed from another phone. Nearby discovery is controlled separately."))
            val dialog=AlertDialog.Builder(a).setTitle("Online, last seen & photo")
                .setView(content).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if(Session.id!=account) {dialog.dismiss();return@setOnClickListener}
                    val patch=JSONObject().put("hideOnline",online.isChecked).put("hideLastSeen",seen.isChecked).put("hidePhoto",photo.isChecked)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
                    // Enable retry even when the network request fails; FeatureApi.run displays the error.
                    FeatureApi.run(a, {
                        try {FeatureApi.request("/api/me/visibility","POST",patch,server,token)}
                        finally {a.runOnUiThread {if(dialog.isShowing)dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true}}
                    }) {
                        if(Session.id==account)Toast.makeText(a,"Privacy settings saved",Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }
            dialog.show()
        }
    }
}
