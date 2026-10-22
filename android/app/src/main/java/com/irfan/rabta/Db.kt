package com.irfan.rabta

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** [account] is set only by [get]; a Db constructed directly (isolated test fixtures) keeps the legacy file name. */
class Db(ctx: Context, internal val account: String? = null) : SQLiteOpenHelper(ctx, fileFor(account), null, 7) {

    // WAL permits background message writes while the UI reads history; backup exports use a transaction.
    init { setWriteAheadLoggingEnabled(true) }

    /**
     * [peerName] is the name the chat was stored under (their own Rabta name); [nickname] is yours for them, kept on
     * this phone only. Show [shownName]; store and send with [peerName].
     */
    data class Conversation(val peerId: String, val peerName: String, val lastMsg: String, val lastTime: Long, val unread: Int, val isGroup: Boolean = false,
                            val pinned: Long = 0, val archived: Boolean = false, val nickname: String = "") {
        val shownName get() = nickname.ifBlank { peerName }
    }
    data class Message(val id: String, val convId: String, val fromId: String, val fromName: String, val body: String, val sentAt: Long, val mine: Boolean, val status: String, val msgType: String)
    data class Group(val id: String, val name: String, val members: List<String>)
    /** Someone you have connected with; kept when their chat is cleared or deleted. */
    data class Contact(val peerId: String, val name: String, val username: String, val addedAt: Long, val nickname: String = "") {
        val peerName get() = name
        /** Your name for them when you gave one, otherwise their own. */
        val shownName get() = nickname.ifBlank { name }
    }

    override fun onCreate(db: SQLiteDatabase) = createAll(db)
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 4) {
            try { db.execSQL("ALTER TABLE messages ADD COLUMN from_name TEXT DEFAULT ''") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE messages ADD COLUMN msg_type TEXT DEFAULT 'text'") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE conversations ADD COLUMN is_group INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS calls(id INTEGER PRIMARY KEY AUTOINCREMENT, peer_id TEXT, peer_name TEXT, type TEXT, duration INTEGER DEFAULT 0, started_at INTEGER)") } catch (_: Exception) {}
            db.execSQL("CREATE TABLE IF NOT EXISTS groups(id TEXT PRIMARY KEY, name TEXT, members TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS calls(id INTEGER PRIMARY KEY AUTOINCREMENT, peer_id TEXT, peer_name TEXT, type TEXT, duration INTEGER DEFAULT 0, started_at INTEGER)")
        }
        createAll(db)
        // Additive: everyone you already chat with one-to-one becomes a contact; chats and messages are untouched.
        if (o < 6) db.execSQL("INSERT OR IGNORE INTO contacts(peer_id,name,added_at) SELECT peer_id,peer_name,last_time FROM conversations WHERE is_group=0")
        // Additive: pinned and archived chats, and your own names for contacts. Existing rows keep their data.
        if (o < 7) for (column in listOf("conversations ADD COLUMN pinned INTEGER DEFAULT 0", "conversations ADD COLUMN archived INTEGER DEFAULT 0", "contacts ADD COLUMN nickname TEXT DEFAULT ''"))
            try { db.execSQL("ALTER TABLE $column") } catch (_: Exception) {}   // already there: created by createAll above
    }

    private fun createAll(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS message_meta(id TEXT PRIMARY KEY, starred INTEGER DEFAULT 0, reactions TEXT DEFAULT '{}')")
        db.execSQL("CREATE TABLE IF NOT EXISTS conversations(peer_id TEXT PRIMARY KEY, peer_name TEXT, last_msg TEXT, last_time INTEGER DEFAULT 0, unread INTEGER DEFAULT 0, is_group INTEGER DEFAULT 0, pinned INTEGER DEFAULT 0, archived INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY, conv_id TEXT, from_id TEXT, from_name TEXT DEFAULT '', body TEXT, sent_at INTEGER, mine INTEGER DEFAULT 0, status TEXT DEFAULT 'sent', msg_type TEXT DEFAULT 'text')")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_conv ON messages(conv_id, sent_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS groups(id TEXT PRIMARY KEY, name TEXT, members TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS calls(id INTEGER PRIMARY KEY AUTOINCREMENT, peer_id TEXT, peer_name TEXT, type TEXT, duration INTEGER DEFAULT 0, started_at INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS contacts(peer_id TEXT PRIMARY KEY, name TEXT, username TEXT DEFAULT '', added_at INTEGER DEFAULT 0, nickname TEXT DEFAULT '')")
    }

    companion object {
        private const val LEGACY = "rabta.db"
        @Volatile private var INST: Db? = null

        /** Each account has its own history file; signed out, nothing is written to disk. */
        private fun fileFor(account: String?) = when { account == null -> LEGACY; account.isEmpty() -> null; else -> "rabta_$account.db" }
        private fun current(db: Db?, account: String) = db?.takeIf { it.account == null || it.account == account }

        fun get(ctx: Context): Db {
            val account = runCatching { Session.id }.getOrDefault("")
            current(INST, account)?.let { return it }
            return synchronized(this) {
                current(INST, account) ?: run {
                    val app = ctx.applicationContext
                    if (account.isNotEmpty()) adoptLegacy(app, account)
                    Db(app, account).also { INST = it }
                }
            }
        }

        /** Delete account: closes [account]'s history and deletes its file (the phone is already signed out of it). */
        fun delete(ctx: Context, account: String) {
            synchronized(this) { INST?.takeIf { it.account == account }?.let { runCatching { it.close() }; INST = null } }
            fileFor(account)?.let { ctx.applicationContext.deleteDatabase(it) }
        }

        /**
         * Before per-account files, every account on the phone shared rabta.db. Hand that history to the first
         * account that opens it after the update (normally the account that was signed in when the app updated);
         * it is moved, never copied or deleted. Other accounts start with their own empty history.
         */
        private fun adoptLegacy(app: Context, account: String) {
            val prefs = app.getSharedPreferences("rabta_storage", Context.MODE_PRIVATE)
            if (prefs.contains("legacy_owner")) return
            val legacy = app.getDatabasePath(LEGACY)
            val target = app.getDatabasePath(fileFor(account)!!)
            if (!legacy.exists() || target.exists()) return
            val moved = listOf("", "-journal", "-wal", "-shm").all { suffix ->
                val from = java.io.File(legacy.path + suffix)
                !from.exists() || from.renameTo(java.io.File(target.path + suffix))
            }
            if (moved) prefs.edit().putString("legacy_owner", account).commit()
        }
    }

    /** The files on this phone that this account's messages point to (see Attachments), for deleting the account. */
    fun attachmentKeys(): Set<String> = readableDatabase.rawQuery("SELECT body FROM messages WHERE body LIKE '%\"local\"%'", null).use { c ->
        buildSet { while (c.moveToNext()) runCatching { org.json.JSONObject(c.getString(0)).optString("local") }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(::add) }
    }

    fun upsertConversation(peerId: String, peerName: String, lastMsg: String, time: Long, incUnread: Boolean, isGroup: Boolean = false) {
        val cv = ContentValues().apply { put("peer_id",peerId);put("peer_name",peerName);put("last_msg",lastMsg.take(100));put("last_time",time);put("is_group", if(isGroup) 1 else 0) }
        val started = writableDatabase.insertWithOnConflict("conversations", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        writableDatabase.execSQL("UPDATE conversations SET peer_name=?,last_msg=?,last_time=?,unread=unread+?,is_group=? WHERE peer_id=?",
            arrayOf(peerName, lastMsg.take(100), time, if (incUnread) 1 else 0, if (isGroup) 1 else 0, peerId))
        // A new one-to-one chat saves its person as a contact, so deleting the chat later keeps them. Later messages
        // only keep the contact's name current: a contact you deleted stays deleted.
        if (!isGroup) {
            if (started) saveContact(peerId, peerName)
            else if (peerName.isNotBlank() && peerName != peerId) writableDatabase.execSQL("UPDATE contacts SET name=? WHERE peer_id=?", arrayOf(peerName, peerId))
        }
    }

    /** Saves [peerId] as a contact; a real name or username replaces what was known, a bare ID never does. */
    fun saveContact(peerId: String, name: String, username: String = "") {
        val db = writableDatabase
        db.insertWithOnConflict("contacts", null, ContentValues().apply { put("peer_id",peerId);put("name",name.ifBlank { peerId });put("username",username);put("added_at",System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_IGNORE)
        if (name.isNotBlank() && name != peerId) db.execSQL("UPDATE contacts SET name=? WHERE peer_id=?", arrayOf(name, peerId))
        if (username.isNotBlank()) db.execSQL("UPDATE contacts SET username=? WHERE peer_id=?", arrayOf(username, peerId))
    }

    fun contacts(): List<Contact> = readableDatabase.rawQuery(
        "SELECT peer_id,name,username,added_at,nickname FROM contacts ORDER BY lower(CASE WHEN nickname<>'' THEN nickname ELSE name END)", null).use { c ->
        generateSequence { if (c.moveToNext()) Contact(c.getString(0), c.getString(1) ?: c.getString(0), c.getString(2) ?: "", c.getLong(3), c.getString(4) ?: "") else null }.toList()
    }

    fun removeContact(peerId: String) = writableDatabase.execSQL("DELETE FROM contacts WHERE peer_id=?", arrayOf(peerId))

    /** Your own name for [peerId], on this phone only; blank removes it. Someone not yet a contact becomes one. */
    fun setNickname(peerId: String, nickname: String, theirName: String = "") {
        val clean = nickname.replace(Regex("[\\u0000-\\u001f]"), " ").trim().take(40)
        writableDatabase.insertWithOnConflict("contacts", null, ContentValues().apply { put("peer_id",peerId);put("name",theirName.ifBlank { peerId });put("added_at",System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.execSQL("UPDATE contacts SET nickname=? WHERE peer_id=?", arrayOf(clean, peerId))
    }
    /** Every nickname you have given, by Rabta ID. */
    fun nicknames(): Map<String, String> = readableDatabase.rawQuery("SELECT peer_id,nickname FROM contacts WHERE nickname<>''", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
    }
    fun contact(peerId: String): Contact? = readableDatabase.rawQuery("SELECT peer_id,name,username,added_at,nickname FROM contacts WHERE peer_id=?", arrayOf(peerId)).use { c ->
        if (c.moveToFirst()) Contact(c.getString(0), c.getString(1) ?: c.getString(0), c.getString(2) ?: "", c.getLong(3), c.getString(4) ?: "") else null
    }

    /** Pinned chats stay on top, newest pin first; 0 unpins. */
    fun setPinned(peerId: String, pinned: Boolean) =
        writableDatabase.execSQL("UPDATE conversations SET pinned=? WHERE peer_id=?", arrayOf(if (pinned) System.currentTimeMillis() else 0L, peerId))
    /** Archived chats leave the main list, messages and all, and stay archived when new messages arrive. */
    fun setArchived(peerId: String, archived: Boolean) =
        writableDatabase.execSQL("UPDATE conversations SET archived=?,pinned=CASE WHEN ? THEN 0 ELSE pinned END WHERE peer_id=?", arrayOf(if (archived) 1 else 0, if (archived) 1 else 0, peerId))
    /** Mark as unread puts a badge back on a read chat; reading the chat clears it again. */
    fun markUnread(peerId: String) = writableDatabase.execSQL("UPDATE conversations SET unread=1 WHERE peer_id=? AND unread=0", arrayOf(peerId))

    /** Clear chat: its messages leave this phone, the chat stays in the list (empty). */
    fun clearChat(convId: String) = writableDatabase.run {
        beginTransaction()
        try {
            execSQL("DELETE FROM message_meta WHERE id IN (SELECT id FROM messages WHERE conv_id=?)", arrayOf(convId))
            execSQL("DELETE FROM messages WHERE conv_id=?", arrayOf(convId))
            execSQL("UPDATE conversations SET last_msg='',unread=0 WHERE peer_id=?", arrayOf(convId))
            setTransactionSuccessful()
        } finally { endTransaction() }
    }

    /** Delete chat: the chat and its messages leave this phone; the contact and the call history stay. */
    fun deleteChat(convId: String) = writableDatabase.run {
        beginTransaction()
        try {
            execSQL("DELETE FROM message_meta WHERE id IN (SELECT id FROM messages WHERE conv_id=?)", arrayOf(convId))
            execSQL("DELETE FROM messages WHERE conv_id=?", arrayOf(convId))
            execSQL("DELETE FROM conversations WHERE peer_id=?", arrayOf(convId))
            setTransactionSuccessful()
        } finally { endTransaction() }
    }

    fun clearUnread(peerId: String) = writableDatabase.execSQL("UPDATE conversations SET unread=0 WHERE peer_id=?", arrayOf(peerId))

    fun conversations(): List<Conversation> {
        val list = mutableListOf<Conversation>()
        readableDatabase.rawQuery("SELECT c.peer_id,c.peer_name,c.last_msg,c.last_time,c.unread,c.is_group,c.pinned,c.archived,k.nickname FROM conversations c LEFT JOIN contacts k ON k.peer_id=c.peer_id AND c.is_group=0 " +
            "ORDER BY c.pinned>0 DESC, c.pinned DESC, c.last_time DESC", null).use { c ->
            while (c.moveToNext()) list.add(Conversation(c.getString(0), c.getString(1), c.getString(2) ?: "", c.getLong(3), c.getInt(4), c.getInt(5) == 1, c.getLong(6), c.getInt(7) == 1, c.getString(8) ?: ""))
        }
        return list
    }

    fun saveIfNew(id: String, convId: String, fromId: String, fromName: String, body: String, sentAt: Long, mine: Boolean, status: String, msgType: String = "text"): Boolean {
        val cv = ContentValues().apply {
            put("id",id);put("conv_id",convId);put("from_id",fromId);put("from_name",fromName)
            put("body",body);put("sent_at",sentAt);put("mine",if(mine)1 else 0);put("status",status);put("msg_type",msgType)
        }
        return writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun meta(id:String):Pair<Boolean,String> = readableDatabase.rawQuery("SELECT starred,reactions FROM message_meta WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) (it.getInt(0)==1) to it.getString(1) else false to "{}" }
    fun star(id:String) { val m=meta(id);writableDatabase.execSQL("INSERT OR REPLACE INTO message_meta(id,starred,reactions) VALUES(?,?,?)",arrayOf(id,if(m.first)0 else 1,m.second)) }
    fun react(id:String,from:String,emoji:String) { val m=meta(id);val r=org.json.JSONObject(m.second).put(from,emoji);writableDatabase.execSQL("INSERT OR REPLACE INTO message_meta(id,starred,reactions) VALUES(?,?,?)",arrayOf(id,if(m.first)1 else 0,r.toString())) }
    fun deleteMessage(id:String) { writableDatabase.execSQL("UPDATE messages SET body='Message deleted',msg_type='text' WHERE id=?",arrayOf(id)) }
    private fun selected(where:String):List<Message> { val out=mutableListOf<Message>();readableDatabase.rawQuery("SELECT id,conv_id,from_id,from_name,body,sent_at,mine,status,msg_type FROM messages WHERE "+where+" ORDER BY sent_at DESC",null).use { c -> while(c.moveToNext())out.add(Message(c.getString(0),c.getString(1),c.getString(2),c.getString(3)?:"",c.getString(4),c.getLong(5),c.getInt(6)==1,c.getString(7),c.getString(8))) };return out }
    fun pausedMessages()=selected("mine=1 AND status='paused'")
    /** Outgoing messages AutoSwitch still has to place on a transport (see [MessageDelivery.pending]). */
    fun pendingOutgoing()=selected("mine=1 AND status IN ('queued','sending','mesh','mesh_wait')")
    fun outgoing(id:String):Message?=readableDatabase.rawQuery("SELECT id,conv_id,from_id,from_name,body,sent_at,mine,status,msg_type FROM messages WHERE id=? AND mine=1",arrayOf(id)).use { c -> if(c.moveToFirst())Message(c.getString(0),c.getString(1),c.getString(2),c.getString(3)?:"",c.getString(4),c.getLong(5),true,c.getString(7),c.getString(8)) else null }
    fun deliveryStates(account:String):Map<String,Int> = readableDatabase.rawQuery(
        "SELECT status,COUNT(*) FROM messages WHERE mine=1 AND from_id=? GROUP BY status",arrayOf(account)
    ).use { c -> buildMap { while(c.moveToNext())put(c.getString(0),c.getInt(1)) } }
    fun starred()=selected("id IN (SELECT id FROM message_meta WHERE starred=1)")
    /** The newest message's direction and delivery state, for the chat list's ticks. */
    fun lastMessageState(convId:String):Pair<Boolean,String>? = readableDatabase.rawQuery(
        "SELECT mine,status FROM messages WHERE conv_id=? ORDER BY sent_at DESC LIMIT 1",arrayOf(convId)
    ).use { if(it.moveToFirst()) (it.getInt(0)==1) to (it.getString(1)?:"") else null }
    /** Photos, videos and files shared in one conversation, newest first. */
    fun media(convId:String,limit:Int=60):List<Message> { val out=mutableListOf<Message>()
        readableDatabase.rawQuery("SELECT id,conv_id,from_id,from_name,body,sent_at,mine,status,msg_type FROM messages WHERE conv_id=? AND msg_type IN ('image','video','file') ORDER BY sent_at DESC LIMIT ?",arrayOf(convId,limit.toString())).use { c ->
            while(c.moveToNext())out.add(Message(c.getString(0),c.getString(1),c.getString(2),c.getString(3)?:"",c.getString(4),c.getLong(5),c.getInt(6)==1,c.getString(7),c.getString(8)))
        }
        return out
    }
    fun mediaCount(convId:String)=readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE conv_id=? AND msg_type IN ('image','video','file')",arrayOf(convId)).use { if(it.moveToFirst()) it.getInt(0) else 0 }

    fun hasMessage(id:String)=readableDatabase.rawQuery("SELECT 1 FROM messages WHERE id=?",arrayOf(id)).use { it.moveToFirst() }

    fun saveMessage(id: String, convId: String, fromId: String, body: String, sentAt: Long, mine: Boolean, status: String = "sent", msgType: String = "text", fromName: String = "") {
        saveIfNew(id, convId, fromId, fromName, body, sentAt, mine, status, msgType)
    }

    fun messages(convId: String, limit: Int = 200): List<Message> {
        val list = mutableListOf<Message>()
        readableDatabase.rawQuery("SELECT id,conv_id,from_id,from_name,body,sent_at,mine,status,msg_type FROM messages WHERE conv_id=? ORDER BY sent_at DESC LIMIT ?",
            arrayOf(convId, limit.toString())).use { c ->
            while (c.moveToNext()) list.add(Message(c.getString(0), c.getString(1), c.getString(2), c.getString(3) ?: "", c.getString(4), c.getLong(5), c.getInt(6)==1, c.getString(7), c.getString(8) ?: "text"))
        }
        return list.asReversed()
    }

    /** Applies only forward transitions; returns whether the stored state changed. */
    @Synchronized fun updateStatus(msgId: String, status: String): Boolean {
        val old = this.status(msgId) ?: return false
        if (!MessageDelivery.canTransition(old, status)) return false
        writableDatabase.execSQL("UPDATE messages SET status=? WHERE id=? AND mine=1", arrayOf(status, msgId))
        return true
    }
    fun isGroupConversation(id: String) = group(id) != null ||
        readableDatabase.rawQuery("SELECT 1 FROM conversations WHERE peer_id=? AND is_group=1", arrayOf(id)).use { it.moveToFirst() }
    fun status(id: String): String? = readableDatabase.rawQuery("SELECT status FROM messages WHERE id=? AND mine=1", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun conversationForMessage(id: String): String? = readableDatabase.rawQuery("SELECT conv_id FROM messages WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
    /** A send interrupted by the app stopping is queued again; AutoSwitch resends it with the same ID. */
    fun requeueInterruptedSends() = writableDatabase.execSQL("UPDATE messages SET status='queued' WHERE mine=1 AND status='sending'")
    fun applyReadReceipt(id: String, byId: String) {
        if (byId.isBlank()) return
        if (id == "all") writableDatabase.execSQL("UPDATE messages SET status='read' WHERE conv_id=? AND mine=1 AND status IN ('sent','relayed','delivered','direct_delivered')", arrayOf(byId))
        else writableDatabase.execSQL("UPDATE messages SET status='read' WHERE id=? AND conv_id=? AND mine=1", arrayOf(id, byId))
    }
    fun searchConversations(query: String): List<Conversation> {
        val q = query.trim()
        if (q.isEmpty()) return conversations()
        val result = mutableListOf<Conversation>()
        // INSTR treats %, _ and quotes as literal input; no SQL interpolation.
        readableDatabase.rawQuery("""
            SELECT c.peer_id,c.peer_name,c.last_msg,c.last_time,c.unread,c.is_group,
              (SELECT m.body FROM messages m WHERE m.conv_id=c.peer_id AND m.msg_type='text'
               AND instr(lower(m.body),lower(?))>0 ORDER BY m.sent_at DESC LIMIT 1) AS hit,
              c.pinned,c.archived,k.nickname
            FROM conversations c LEFT JOIN contacts k ON k.peer_id=c.peer_id AND c.is_group=0
            WHERE instr(lower(c.peer_name),lower(?))>0 OR instr(lower(coalesce(k.nickname,'')),lower(?))>0 OR EXISTS
              (SELECT 1 FROM messages m WHERE m.conv_id=c.peer_id AND m.msg_type='text'
               AND instr(lower(m.body),lower(?))>0)
            ORDER BY c.pinned>0 DESC, c.pinned DESC, c.last_time DESC
        """.trimIndent(), arrayOf(q,q,q,q)).use { c ->
            while(c.moveToNext()) result.add(Conversation(c.getString(0),c.getString(1),c.getString(6) ?: c.getString(2) ?: "",c.getLong(3),c.getInt(4),c.getInt(5)==1,c.getLong(7),c.getInt(8)==1,c.getString(9) ?: ""))
        }
        return result
    }
    fun searchMessages(convId: String, query: String): List<Message> {
        if (query.isBlank()) return messages(convId)
        val result = mutableListOf<Message>()
        readableDatabase.rawQuery("SELECT id,conv_id,from_id,from_name,body,sent_at,mine,status,msg_type FROM messages WHERE conv_id=? AND msg_type='text' AND instr(lower(body),lower(?))>0 ORDER BY sent_at DESC LIMIT 200",arrayOf(convId,query.trim())).use { c ->
            while(c.moveToNext()) result.add(Message(c.getString(0),c.getString(1),c.getString(2),c.getString(3) ?: "",c.getString(4),c.getLong(5),c.getInt(6)==1,c.getString(7),c.getString(8)))
        }
        return result.asReversed()
    }

    // Groups
    fun saveGroup(id: String, name: String, members: List<String>) {
        val cv = ContentValues().apply { put("id",id);put("name",name);put("members",members.joinToString(",")) }
        writableDatabase.insertWithOnConflict("groups", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun groups(): List<Group> {
        val list = mutableListOf<Group>()
        readableDatabase.rawQuery("SELECT id,name,members FROM groups", null).use { c ->
            while (c.moveToNext()) list.add(Group(c.getString(0), c.getString(1), c.getString(2).split(",")))
        }
        return list
    }
    data class CallRecord(val id: Long, val peerId: String, val peerName: String, val type: String, val duration: Int, val startedAt: Long)
    fun logCallStart(peerId: String, peerName: String, type: String): Long {
        val cv = ContentValues().apply { put("peer_id",peerId);put("peer_name",peerName);put("type",type);put("started_at",System.currentTimeMillis()) }
        return writableDatabase.insert("calls", null, cv)
    }
    fun deleteCall(id:Long)=writableDatabase.execSQL("DELETE FROM calls WHERE id=?",arrayOf(id))
    fun callOutcome(id:Long,outcome:String)=writableDatabase.execSQL("UPDATE calls SET type=? WHERE id=?",arrayOf(outcome,id))
    fun logCallEnd(callId: Long, durationSec: Int) = writableDatabase.execSQL("UPDATE calls SET duration=? WHERE id=?", arrayOf(durationSec, callId))
    /** One contact's calls, oldest first, for the chat timeline. */
    fun callsWith(peerId: String): List<CallRecord> = readableDatabase.rawQuery("SELECT id,peer_id,peer_name,type,duration,started_at FROM calls WHERE peer_id=? ORDER BY started_at", arrayOf(peerId)).use { c ->
        generateSequence { if (c.moveToNext()) CallRecord(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getLong(5)) else null }.toList()
    }
    fun calls(limit: Int = 100): List<CallRecord> {
        val list = mutableListOf<CallRecord>()
        readableDatabase.rawQuery("SELECT id,peer_id,peer_name,type,duration,started_at FROM calls ORDER BY started_at DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) list.add(CallRecord(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getLong(5)))
        }
        return list
    }

    fun group(id: String): Group? {
        readableDatabase.rawQuery("SELECT id,name,members FROM groups WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) return Group(c.getString(0), c.getString(1), c.getString(2).split(","))
        }
        return null
    }
}
