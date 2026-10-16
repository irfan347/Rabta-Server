package com.irfan.rabta

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Account-scoped durable carry queue and replay cache. */
class MeshStore(c:Context,id:String):SQLiteOpenHelper(c,"mesh_v2_$id.db",null,1) {
    override fun onCreate(d:SQLiteDatabase) {
        d.execSQL("CREATE TABLE packets(id TEXT PRIMARY KEY, json TEXT NOT NULL, expires INTEGER NOT NULL, own INTEGER NOT NULL DEFAULT 0)")
        d.execSQL("CREATE TABLE seen(id TEXT PRIMARY KEY, expires INTEGER NOT NULL)")
        d.execSQL("CREATE TABLE pending(id TEXT PRIMARY KEY, recipient TEXT, body TEXT, type TEXT, sent INTEGER)")
    }
    override fun onUpgrade(d:SQLiteDatabase,o:Int,n:Int) {}
    @Synchronized fun put(id:String,json:String,expires:Long,own:Boolean=false):Boolean {
        if(json.toByteArray().size>MeshPolicy.MAX_PACKET) return false
        val d=writableDatabase
        val size=d.rawQuery("SELECT COUNT(*),COALESCE(SUM(length(json)),0) FROM packets",null).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }
        if(size.first>=500 || size.second+json.length>8*1024*1024) return false
        return d.insertWithOnConflict("packets",null,ContentValues().apply { put("id",id);put("json",json);put("expires",expires);put("own",if(own)1 else 0) },SQLiteDatabase.CONFLICT_REPLACE)!=-1L
    }
    fun all():List<String> = readableDatabase.rawQuery("SELECT json FROM packets ORDER BY own DESC,expires ASC",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun contains(id:String)=readableDatabase.rawQuery("SELECT 1 FROM packets WHERE id=?",arrayOf(id)).use { it.moveToFirst() }
    fun seen(id:String)=readableDatabase.rawQuery("SELECT 1 FROM seen WHERE id=?",arrayOf(id)).use { it.moveToFirst() }
    fun mark(id:String,expires:Long) {
        if(readableDatabase.rawQuery("SELECT COUNT(*) FROM seen",null).use { it.moveToFirst(); it.getInt(0) } >= 10000) return
        writableDatabase.insertWithOnConflict("seen",null,ContentValues().apply { put("id",id);put("expires",expires) },SQLiteDatabase.CONFLICT_REPLACE) }
    fun remove(id:String) { writableDatabase.delete("packets","id=?",arrayOf(id)); writableDatabase.delete("pending","id=?",arrayOf(id)) }
    data class Pending(val id:String,val to:String,val body:String,val type:String,val sent:Long)
    fun pending():List<Pending> = readableDatabase.rawQuery("SELECT id,recipient,body,type,sent FROM pending",null).use { c -> buildList { while(c.moveToNext()) add(Pending(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4))) } }
    fun pending(p:Pending):Boolean {
        if(pending().size>=100) return false
        return writableDatabase.insertWithOnConflict("pending",null,ContentValues().apply { put("id",p.id);put("recipient",p.to);put("body",p.body);put("type",p.type);put("sent",p.sent) },SQLiteDatabase.CONFLICT_REPLACE)!=-1L
    }
    fun removePending(id:String) { writableDatabase.delete("pending","id=?",arrayOf(id)) }
    fun prune(now:Long):List<String> {
        val expired=readableDatabase.rawQuery("SELECT id FROM packets WHERE expires<=? AND own=1 UNION SELECT id FROM pending WHERE sent<=?",arrayOf(now.toString(),(now-MeshPolicy.LIFE).toString())).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        writableDatabase.delete("packets","expires<=?",arrayOf(now.toString()))
        writableDatabase.delete("seen","expires<=?",arrayOf(now.toString()))
        writableDatabase.delete("pending","sent<=?",arrayOf((now-MeshPolicy.LIFE).toString()))
        return expired
    }
}
