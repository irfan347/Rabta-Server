package com.irfan.rabta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.irfan.rabta.DropEngine.State
import com.irfan.rabta.DropEngine.Transfer
import java.io.File
import java.io.IOException

/** Transfer history for one account, in its own database so the existing chat schema is untouched. */
class DropStore(c: Context, account: String) : SQLiteOpenHelper(c.applicationContext, "drop_$account.db", null, 1), DropEngine.Store {
    override fun onCreate(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE transfers(id TEXT PRIMARY KEY, outgoing INTEGER, peer TEXT, peer_name TEXT, name TEXT, mime TEXT, size INTEGER," +
            " sha TEXT, state TEXT, bytes INTEGER, started INTEGER, updated INTEGER, source TEXT, saved TEXT, error TEXT, accepted INTEGER)")
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Transfer>()

    init {
        // Nothing survives a restart mid-transfer: show it honestly as interrupted (the sender can resume).
        writableDatabase.execSQL("UPDATE transfers SET state='FAILED', error='Interrupted when Rabta stopped' WHERE state IN ('PREPARING','WAITING','SENDING','RECEIVING','VERIFYING')")
        writableDatabase.execSQL("UPDATE transfers SET state='FAILED', error='Offer expired' WHERE state='INCOMING'")
    }
    override fun save(t: Transfer) {
        val previous = cache.put(t.id, t)
        // Progress ticks are frequent; only lifecycle changes and every few seconds of progress reach the disk.
        if (previous != null && previous.state == t.state && previous.error == t.error && t.updated - previous.updated < 3000 && t.active) { cache[t.id] = t.copy(updated = previous.updated); return }
        writableDatabase.insertWithOnConflict("transfers", null, ContentValues().apply {
            put("id", t.id); put("outgoing", if (t.outgoing) 1 else 0); put("peer", t.peer); put("peer_name", t.peerName); put("name", t.name)
            put("mime", t.mime); put("size", t.size); put("sha", t.sha256); put("state", t.state.name); put("bytes", t.bytes); put("started", t.started)
            put("updated", t.updated); put("source", t.source); put("saved", t.saved); put("error", t.error); put("accepted", if (t.accepted) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    override fun get(id: String) = cache[id] ?: query("id=?", arrayOf(id)).firstOrNull()?.also { cache[id] = it }
    fun recent(limit: Int = 60): List<Transfer> {
        val stored = query(null, null, limit).associateBy { it.id }.toMutableMap()
        cache.values.forEach { stored[it.id] = it }
        return stored.values.sortedByDescending { it.started }.take(limit)
    }
    private fun query(where: String?, args: Array<String>?, limit: Int = 1) = readableDatabase.query("transfers", null, where, args, null, null, "started DESC", "$limit").use { c ->
        buildList { while (c.moveToNext()) add(Transfer(c.getString(0), c.getInt(1) == 1, c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6),
            c.getString(7), runCatching { State.valueOf(c.getString(8)) }.getOrDefault(State.FAILED), c.getLong(9), 0, c.getLong(10), c.getLong(11),
            c.getString(12) ?: "", c.getString(13) ?: "", c.getString(14) ?: "", c.getInt(15) == 1)) }
    }
}

/** Safe content-URI reading for the sender; private partial files and user-visible saving for the receiver. */
class DropFiles(private val c: Context) : DropEngine.Files {
    override fun source(uri: String): DropEngine.Source? = runCatching {
        val u = Uri.parse(uri)
        var name = "file"; var size = -1L
        c.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) { name = cursor.getString(0) ?: name; if (!cursor.isNull(1)) size = cursor.getLong(1) }
        }
        val mime = c.contentResolver.getType(u) ?: return null
        if (size <= 0) return null
        DropEngine.Source(name, mime, size) { c.contentResolver.openInputStream(u) ?: throw IOException("File unavailable") }
    }.getOrNull()

    /** App-private and excluded from backup; never visible to other apps while unverified. */
    override fun partial(id: String) = File(c.noBackupFilesDir, "drop/$id.part")

    override fun publish(partial: File, name: String, mime: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Rabta"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                c.contentResolver.openOutputStream(target)?.use { out -> partial.inputStream().use { it.copyTo(out, DropProtocol.CHUNK) } } ?: throw IOException("No output")
                c.contentResolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                target.toString()
            } catch (e: Exception) { c.contentResolver.delete(target, null, null); throw e }
        } else {
            // Android 8–9: this app's own Downloads folder (no storage permission), opened through FileProvider.
            val dir = File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null, "Rabta").also { it.mkdirs() }
            val dot = name.lastIndexOf('.')
            var file = File(dir, name); var n = 1
            while (file.exists()) file = File(dir, if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)").also { n++ }
            partial.copyTo(file)
            FileProvider.getUriForFile(c, c.packageName + ".files", file).toString()
        }
    }.getOrNull()
}

/** Connects the Drop engine that lives with the Nearby Router to screens and notifications. */
object RabtaDrop {
    const val CHANNEL = "rabta_drop"
    const val NOTIFICATION = 7301
    @Volatile var engine: DropEngine? = null; private set
    @Volatile var store: DropStore? = null; private set
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun attach(e: DropEngine, s: DropStore) { engine = e; store = s; changed() }
    // The store is not closed here: transfers still unwinding on the engine's threads may record their final state.
    fun detach(e: DropEngine) { if (engine === e) { engine = null; store = null; changed() } }
    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)
    fun changed() = main.post { listeners.forEach { it() } }
    fun history(): List<Transfer> = store?.recent() ?: emptyList()

    fun listener(c: Context) = object : DropEngine.Listener {
        override fun changed(t: Transfer) { changed() }
        override fun incoming(t: Transfer) { changed(); notifyIncoming(c, t) }
    }

    private fun notifyIncoming(c: Context, t: Transfer) = runCatching {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rabta Drop", NotificationManager.IMPORTANCE_HIGH).apply { description = "Nearby files waiting for your approval" })
        val open = PendingIntent.getActivity(c, NOTIFICATION, Intent(c, DropActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (Privacy.hidePreviews(c)) "Open Rabta Drop to review" else "${t.peerName} wants to send ${t.name} (${size(t.size)})"
        nm.notify(NOTIFICATION, Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat_rabta).setContentTitle("Incoming file")
            .setContentText(text).setContentIntent(open).setAutoCancel(true).setTimeoutAfter(DropProtocol.OFFER_LIFE).build())
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
