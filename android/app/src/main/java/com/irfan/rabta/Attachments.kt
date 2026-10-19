package com.irfan.rabta

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Private files on disk; only small metadata goes into SQLite's cursor window. */
object Attachments {
    const val MAX_BYTES=8*1024*1024
    private fun root(c:Context)=File(c.filesDir,"attachments").apply { mkdirs() }
    private fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun safeName(s:String)=s.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}]"),"").take(120).ifBlank { "attachment" }
    fun save(c:Context,b:ByteArray,name:String,mime:String):String {
        require(b.isNotEmpty() && b.size<=MAX_BYTES) { "Choose a file up to 8 MB" }
        val key=UUID.randomUUID().toString()
        File(root(c),key).writeBytes(b)
        return JSONObject().put("v",1).put("local",key).put("name",safeName(name)).put("mime",mime.take(100)).put("size",b.size).put("sha256",hash(b)).toString()
    }
    fun pick(c:Context,uri:Uri,sticker:Boolean=false):String {
        var name="attachment"
        c.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) name=it.getString(0) ?: name }
        val mime=c.contentResolver.getType(uri) ?: "application/octet-stream"
        if(sticker) require(mime in setOf("image/png","image/webp","image/jpeg")) { "Choose a PNG, WebP or JPEG sticker" }
        val limit=if(sticker) 512*1024 else MAX_BYTES
        val bytes=c.contentResolver.openInputStream(uri)?.use { input ->
            val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(true) { val n=input.read(buffer); if(n<0) break; require(out.size()+n<=limit) { if(sticker) "Sticker must be under 512 KB" else "Choose a file up to 8 MB" }; out.write(buffer,0,n) }
            out.toByteArray()
        } ?: error("Could not read this file")
        return save(c,bytes,name,mime)
    }
    fun file(c:Context,body:String):File {
        val key=JSONObject(body).getString("local")
        require(Regex("[a-f0-9-]{36}").matches(key))
        return File(root(c),key)
    }
    fun wire(c:Context,body:String,type:String):String {
        if(type !in setOf("file","video","sticker") || body.startsWith("rbt:")) return body
        val o=JSONObject(body); if(o.has("remote")) return body; val bytes=file(c,body).readBytes(); require(bytes.size<=MAX_BYTES)
        o.remove("local"); o.put("data",Base64.encodeToString(bytes,Base64.NO_WRAP)); return o.toString()
    }
    fun receive(c:Context,body:String,type:String):String {
        if(type !in setOf("file","video","sticker") || body.startsWith("rbt:")) return body
        require(body.length<=12*1024*1024)
        val o=JSONObject(body); if(o.has("remote")) { require(Regex("[a-f0-9]{32}").matches(o.getString("remote"))&&o.getLong("size") in 1..MAX_BYTES.toLong()&&Regex("[a-f0-9]{64}").matches(o.getString("sha256")));return body }; val encoded=o.getString("data")
        require(encoded.length <= (MAX_BYTES+2)/3*4)
        val bytes=Base64.decode(encoded,Base64.DEFAULT)
        require(bytes.size==o.getInt("size") && hash(bytes)==o.getString("sha256")) { "Attachment checksum failed" }
        return save(c,bytes,o.optString("name","attachment"),o.optString("mime","application/octet-stream"))
    }
    fun discard(c:Context,body:String) { runCatching { file(c,body).delete() } }
    /** A file on this phone with exactly these bytes (e.g. restored from a backup), as a local body; null when there is none. */
    fun find(c:Context,size:Long,sha256:String,name:String,mime:String):String? {
        if(size !in 1..MAX_BYTES.toLong()||!Regex("[a-f0-9]{64}").matches(sha256))return null
        val match=root(c).listFiles()?.firstOrNull { it.length()==size&&Regex("[a-f0-9-]{36}").matches(it.name)&&runCatching { hash(it.readBytes())==sha256 }.getOrDefault(false) } ?: return null
        return JSONObject().put("v",1).put("local",match.name).put("name",safeName(name)).put("mime",mime.take(100)).put("size",size).put("sha256",sha256).toString()
    }
    fun name(body:String)=runCatching { JSONObject(body).getString("name") }.getOrDefault("Attachment")
    fun details(body:String):String=runCatching { val n=JSONObject(body).getLong("size"); if(n>=1024*1024) "%.1f MB".format(n/1048576.0) else "${(n+1023)/1024} KB" }.getOrDefault("")
    fun open(a:Activity,body:String) {
        if(runCatching { JSONObject(body).has("remote") }.getOrDefault(false)) { FeatureApi.run(a,{ Transfers.download(a,body) }) { open(a,it) };return }
        try {
            val o=JSONObject(body); val uri=FileProvider.getUriForFile(a,a.packageName+".files",file(a,body),name(body))
            a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,o.optString("mime","application/octet-stream")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch(_:Exception) { Toast.makeText(a,"No app available to open this file",Toast.LENGTH_SHORT).show() }
    }
}
