package com.irfan.rabta

import android.content.Context
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import android.util.Base64
import java.io.IOException

object Transfers {
    val progress=ConcurrentHashMap<String,Int>()
    private val cancelled=ConcurrentHashMap.newKeySet<String>()
    private val telling=ConcurrentHashMap.newKeySet<String>()
    private val teller=java.util.concurrent.Executors.newSingleThreadExecutor()
    class Cancelled:IOException("Transfer cancelled")
    fun cancel(id:String) { cancelled.add(id) }
    fun upload(c:Context,body:String,id:String,changed:()->Unit={}):String {
        val o=JSONObject(body)
        // Passing on a file you received: send this phone's copy, since the server lets go of a file once everyone it was
        // sent to has saved it. A file not saved here yet is still on the server for this phone, so that one is shared.
        if(o.has("remote"))return localCopy(c,o)?.let { upload(c,it,id,changed) } ?: body
        cancelled.remove(id);val file=Attachments.file(c,body);val prefs=c.getSharedPreferences("uploads_${Session.id}",0)
        val hash=o.getString("sha256");var remote=prefs.getString(hash,null)
        try {
            val state=if(remote==null)FeatureApi.request("/api/uploads","POST",o) else runCatching { FeatureApi.request("/api/uploads/$remote") }.getOrElse { FeatureApi.request("/api/uploads","POST",o) }
            remote=state.getString("id");prefs.edit().putString(hash,remote).commit()
            var offset=state.getLong("received");progress[id]=(offset*100/file.length()).toInt();changed()
            if(!state.optBoolean("done"))file.inputStream().use { input ->
                var remaining=offset;while(remaining>0){val skipped=input.skip(remaining);check(skipped>0);remaining-=skipped}
                val buffer=ByteArray(128*1024)
                while(offset<file.length()) {
                    if(cancelled.contains(id))throw Cancelled()
                    val n=input.read(buffer);check(n>0)
                    val ack=FeatureApi.request("/api/uploads/$remote/chunks","POST",JSONObject().put("offset",offset).put("data",Base64.encodeToString(buffer,0,n,Base64.NO_WRAP)))
                    offset=ack.getLong("received");progress[id]=(offset*100/file.length()).toInt();changed()
                }
            }
            if(cancelled.contains(id))throw Cancelled()
            return FeatureApi.request("/api/uploads/$remote/complete","POST").toString()
        } catch(e:Cancelled) {
            if(remote!=null)runCatching { FeatureApi.request("/api/uploads/$remote","DELETE") }
            prefs.edit().remove(hash).apply();throw e
        } finally { progress.remove(id);cancelled.remove(id);changed() }
    }
    fun download(c:Context,body:String):String {
        val o=JSONObject(body);val remote=o.getString("remote");require(Regex("[a-f0-9]{32}").matches(remote))
        val prefs=c.getSharedPreferences("downloads_${Session.id}",0)
        val local=localCopy(c,o) ?: fetch(c,o,remote)
        if(prefs.getString(remote,null)!=local)prefs.edit().putString(remote,local).apply()
        saved(c,remote)
        return local
    }
    /** This phone's own copy of a received file: saved when it was first opened, or the same bytes restored from a backup. */
    fun localCopy(c:Context,o:JSONObject):String? {
        c.getSharedPreferences("downloads_${Session.id}",0).getString(o.optString("remote"),null)?.takeIf { runCatching { Attachments.file(c,it).exists() }.getOrDefault(false) }?.let { return it }
        return Attachments.find(c,o.optLong("size"),o.optString("sha256"),o.optString("name","attachment"),o.optString("mime","application/octet-stream"))
    }
    private fun fetch(c:Context,o:JSONObject,remote:String):String {
        val request=Request.Builder().url(Session.serverUrl+"/api/files/$remote").header("Authorization","Bearer ${Session.token}").build()
        FeatureApi.http.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "File unavailable (${r.code})" }
            val out=java.io.ByteArrayOutputStream();r.body!!.byteStream().use { input -> val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=Attachments.MAX_BYTES);out.write(buffer,0,n)} }
            val bytes=out.toByteArray();val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            check(bytes.size.toLong()==o.getLong("size")&&hash==o.getString("sha256")) { "File checksum failed" }
            return Attachments.save(c,bytes,o.getString("name"),o.getString("mime"))
        }
    }
    /** Tells the server, once and in the background, that this phone has the file; when everyone it was meant for has
     *  it, the server lets go of its copy. Opening the file never waits for this. */
    private fun saved(c:Context,remote:String) {
        val prefs=c.getSharedPreferences("downloads_${Session.id}",0);val key="got:$remote";val account=Session.id
        if(prefs.getBoolean(key,false)||!telling.add(remote))return
        teller.execute {
            try { if(Session.id==account) { FeatureApi.request("/api/files/$remote/got","POST");prefs.edit().putBoolean(key,true).apply() } } catch(_:Exception) {} finally { telling.remove(remote) }
        }
    }
}
