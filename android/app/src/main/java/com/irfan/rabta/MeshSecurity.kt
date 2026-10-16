package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject
import java.security.*

class MeshSecurity(private val c:Context,private val id:String) {
    private val prefs=c.getSharedPreferences("mesh_keys_$id",0)
    private val alias="rabta_mesh_v2_$id"
    @Synchronized fun pair():KeyPair {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(!ks.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build())
                generateKeyPair()
            }
        }
        return KeyPair(ks.getCertificate(alias).publicKey,ks.getKey(alias,null) as PrivateKey)
    }
    fun publicText()=MeshCrypto.b64(pair().public.encoded)
    fun key(user:String)=if(user==id) publicText() else prefs.getString(user,null)
    @Synchronized fun pin(user:String,text:String):Boolean {
        synchronized(pinLock) {
        if(!MeshPolicy.validId(user)) return false
        try { MeshCrypto.publicKey(text) } catch(_:Exception) { return false }
        val old=key(user)
        if(old!=null) return old==text
        if(prefs.all.size>=500) return false
        return prefs.edit().putString(user,text).commit()
        }
    }
    companion object {
        private val pinLock=Any()
        fun showIdentity(a:Activity) {
            val sec=MeshSecurity(a,Session.id)
            Thread {
                val result=runCatching { sec.publicText() }
                a.runOnUiThread { result.onSuccess { key ->
                    AlertDialog.Builder(a).setTitle("Your offline security code").setMessage(MeshCrypto.fingerprint(key)+"\n\nCompare this with your contact using a trusted channel. Keys stay on this phone.")
                        .setPositiveButton("Share contact key") { _,_ ->
                            val bundle=JSONObject().put("id",Session.id).put("key",key).toString()
                            a.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,bundle),"Share offline contact"))
                        }.setNegativeButton("Close",null).show()
                }.onFailure { Toast.makeText(a,"Security key unavailable",Toast.LENGTH_SHORT).show() } }
            }.start()
        }
        fun showContact(a:Activity,peer:String) {
            val sec=MeshSecurity(a,Session.id); val key=sec.key(peer)
            val text=key?.let { MeshCrypto.fingerprint(it) } ?: "No key yet. Connect nearby or import your contact's shared key. Messages wait safely until a key is available."
            AlertDialog.Builder(a).setTitle("Offline security · $peer").setMessage(text+"\n\nNearby keys use trust on first use. Compare the code with your contact before sharing sensitive messages. A changed key is blocked.")
                .setPositiveButton("Import key") { _,_ ->
                    val input=EditText(a).apply { hint="Paste shared contact key"; maxLines=5 }
                    AlertDialog.Builder(a).setTitle("Import trusted contact").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Import") { _,_ ->
                        val ok=runCatching { val o=JSONObject(input.text.toString()); require(o.getString("id")==peer); sec.pin(peer,o.getString("key")) }.getOrDefault(false)
                        Toast.makeText(a,if(ok) "Contact key saved" else "Invalid or changed key; compare with your contact",Toast.LENGTH_LONG).show()
                    }.show()
                }.setNegativeButton("Close",null).show()
        }
    }
}
