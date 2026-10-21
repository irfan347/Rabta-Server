package com.irfan.rabta

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import org.json.JSONArray
import org.json.JSONObject

object PhoneContacts {
    const val REQUEST=730
    fun enabled(a:android.content.Context)=Privacy.prefs(a).getBoolean("sync_contacts",false)
    fun consent(a:SelectContactActivity) {
        AlertDialog.Builder(a).setTitle("Find phone contacts on Rabta?")
            .setMessage("Rabta will send your saved phone numbers to its server to find people who opted into discovery. Names stay on this phone. Numbers are not SMS verified, so confirm the person's username before messaging. Lookup lists are not stored on the server.")
            .setNegativeButton("Cancel",null).setPositiveButton("Enable") { _,_ ->
                Privacy.prefs(a).edit().putBoolean("sync_contacts",true).apply()
                if(a.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED) a.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS),REQUEST) else a.refreshPhoneContacts()
            }.show()
    }
    fun matches(a:android.content.Context):List<Pair<String,JSONObject>> {
        if(!enabled(a)||a.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)return emptyList()
        val country=(a.getSystemService(android.content.Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager).simCountryIso.uppercase(java.util.Locale.ROOT).ifBlank {java.util.Locale.getDefault().country}
        val numbers=linkedMapOf<String,String>()
        a.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),null,null,null)?.use { cursor ->
            while(cursor.moveToNext()&&numbers.size<5000) {
                val number=PhoneNumberUtils.formatNumberToE164(cursor.getString(0),country) ?: continue
                numbers[number]=cursor.getString(1).orEmpty()
            }
        }
        return numbers.keys.chunked(500).flatMap { batch ->
            val users=FeatureApi.request("/api/contacts/match","POST",JSONObject().put("numbers",JSONArray(batch))).getJSONArray("users")
            (0 until users.length()).map { users.getJSONObject(it) }.map { (numbers[it.optString("matchedNumber")].orEmpty()) to it }
        }.distinctBy {it.second.optString("id")}
    }
    fun settings(a:ThemedActivity) {
        FeatureApi.run(a,{FeatureApi.request("/api/me/contact-details")}) { current ->
            val box=android.widget.LinearLayout(a).apply {orientation=android.widget.LinearLayout.VERTICAL;setPadding(32,16,32,16)}
            val number=android.widget.EditText(a).apply {hint="+923001234567";inputType=android.text.InputType.TYPE_CLASS_PHONE;setText(current.optString("phoneNumber"))}
            val discover=android.widget.CheckBox(a).apply {text="Let people with my number find me (not verified)";isChecked=current.optBoolean("discoverable")}
            box.addView(number);box.addView(discover)
            AlertDialog.Builder(a).setTitle("Phone discovery").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save") { _,_ ->
                FeatureApi.run(a,{FeatureApi.request("/api/me/contact-details","POST",JSONObject().put("phoneNumber",number.text.toString().replace(Regex("[ ()-]"),"")).put("discoverable",discover.isChecked))}) { android.widget.Toast.makeText(a,"Contact details saved",android.widget.Toast.LENGTH_SHORT).show() }
            }.show().window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
