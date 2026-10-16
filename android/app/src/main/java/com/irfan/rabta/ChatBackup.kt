package com.irfan.rabta

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.JsonReader
import android.util.JsonWriter
import java.io.File
import java.util.UUID
import java.util.zip.*
object ChatBackup {
    private val tables=linkedMapOf("conversations" to listOf("peer_id","peer_name","last_msg","last_time","unread","is_group"),"messages" to listOf("id","conv_id","from_id","from_name","body","sent_at","mine","status","msg_type"),"message_meta" to listOf("id","starred","reactions"),"groups" to listOf("id","name","members"),"calls" to listOf("id","peer_id","peer_name","type","duration","started_at"))
    fun export(c:Context,uri:Uri,password:CharArray) {
        require(password.size>=10){"Use at least 10 characters"}
        try { c.contentResolver.openOutputStream(uri,"wt")!!.use { output -> BackupCrypto.encrypt(output,password) { encrypted ->
            ZipOutputStream(encrypted).use { zip ->
                zip.putNextEntry(ZipEntry("account"));zip.write(Session.id.toByteArray());zip.closeEntry()
                zip.putNextEntry(ZipEntry("chats.json"));val writer=JsonWriter(java.io.OutputStreamWriter(zip,Charsets.UTF_8));val db=Db.get(c).readableDatabase
                db.beginTransaction();try {writer.beginObject();for((table,columns) in tables){writer.name(table).beginArray();db.query(table,columns.toTypedArray(),null,null,null,null,null).use { cursor -> while(cursor.moveToNext()){writer.beginObject();columns.forEachIndexed { i,col ->writer.name(col);if(cursor.isNull(i))writer.nullValue()else writer.value(cursor.getString(i)) };writer.endObject()} };writer.endArray() };writer.endObject();writer.flush();db.setTransactionSuccessful()}finally{db.endTransaction()};zip.closeEntry()
                var bytes=0L
                File(c.filesDir,"attachments").listFiles()?.filter { Regex("[a-f0-9-]{36}").matches(it.name) }?.forEach { file -> bytes+=file.length();require(bytes<100L*1024*1024){"Attachments exceed backup limit (100 MB)"};zip.putNextEntry(ZipEntry("attachments/"+file.name));file.inputStream().use { it.copyTo(zip) };zip.closeEntry() }
            }
        } } } finally { password.fill('\u0000') }
    }
    fun restore(c:Context,uri:Uri,password:CharArray) {
        val stage=File(c.cacheDir,"restore-"+UUID.randomUUID()).apply { mkdirs() };val decrypted=File(stage,"verified.zip")
        try {
            c.contentResolver.openInputStream(uri)!!.use { input -> decrypted.outputStream().use { BackupCrypto.decrypt(input,password,it) } }
            var bytes=0L;val seen=mutableSetOf<String>()
            ZipInputStream(decrypted.inputStream()).use { zip -> while(true){val entry=zip.nextEntry?:break;val name=entry.name;require(seen.add(name));require(name in setOf("account","chats.json")||Regex("attachments/[a-f0-9-]{36}").matches(name)){"Invalid backup entry"};val target=File(stage,name);target.parentFile!!.mkdirs();target.outputStream().use { out ->val buffer=ByteArray(8192);while(true){val n=zip.read(buffer);if(n<0)break;bytes+=n;require(bytes<=128L*1024*1024){"Backup is too large"};out.write(buffer,0,n)} };zip.closeEntry() } }
            require(File(stage,"account").readText()==Session.id){"Sign in to the account that created this backup"}
            // Validate the complete table schema before committing any data.
            fun readRows(consume:(String,ContentValues)->Unit) { JsonReader(File(stage,"chats.json").reader()).use { r ->r.beginObject();val found=mutableSetOf<String>();while(r.hasNext()){val table=r.nextName();require(found.add(table));val columns=tables[table]?:error("Unknown backup table");r.beginArray();while(r.hasNext()){val cv=ContentValues();r.beginObject();while(r.hasNext()){val col=r.nextName();require(col in columns&&!cv.containsKey(col));if(r.peek()==android.util.JsonToken.NULL){r.nextNull();cv.putNull(col)}else cv.put(col,r.nextString())};r.endObject();require(cv.size()==columns.size);consume(table,cv)};r.endArray()};r.endObject();require(found==tables.keys) } }
            readRows { _,_ -> }
            val copied=mutableListOf<File>();val db=Db.get(c).writableDatabase
            db.beginTransaction();try {
                File(stage,"attachments").listFiles()?.forEach { f ->val dest=File(c.filesDir,"attachments/"+f.name);dest.parentFile!!.mkdirs();if(dest.exists())require(java.security.MessageDigest.getInstance("SHA-256").digest(dest.readBytes()).contentEquals(java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()))){"Attachment conflict"}else {f.copyTo(dest);copied.add(dest)} }
                readRows { table,cv -> db.insertWithOnConflict(table,null,cv,SQLiteDatabase.CONFLICT_IGNORE) }
                db.setTransactionSuccessful()
            } catch(e:Exception){copied.forEach { it.delete() };throw e} finally {db.endTransaction()}
        } finally {password.fill('\u0000');stage.deleteRecursively()}
    }
}
