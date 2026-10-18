package com.irfan.rabta

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.widget.Toast
import org.json.JSONObject

/**
 * WhatsApp-style notifications: one popup per chat with its latest messages and Reply / Mark as read / Mute, and
 * missed calls that say whether they were voice or video calls.
 */
object ChatNotifications {
    const val REPLY = "com.irfan.rabta.notification.REPLY"
    const val READ = "com.irfan.rabta.notification.READ"
    const val MUTE = "com.irfan.rabta.notification.MUTE"
    const val DECLINE = "com.irfan.rabta.notification.DECLINE"
    const val HANG_UP = "com.irfan.rabta.notification.HANG_UP"
    const val KEY_REPLY = "reply"
    const val MISSED_CHANNEL = "rabta_missed_calls"
    private const val MUTE_MS = 8 * 60 * 60_000L

    private class Line(val text: String, val at: Long, val fromId: String, val fromName: String)
    private val recent = HashMap<String, MutableList<Line>>()

    private fun id(convId: String) = 0x10000000 or (convId.hashCode() and 0x0FFFFFFF)
    private fun missedId(peerId: String) = 0x20000000 or (peerId.hashCode() and 0x0FFFFFFF)
    @Volatile private var channelsReady = false
    /** Also before [MessageService] has run in this process, e.g. straight after an update. */
    private fun manager(c: Context): NotificationManager? {
        if (!channelsReady) { MessageService.channels(c); channelsReady = true }
        return c.getSystemService(NotificationManager::class.java)
    }

    /** The chat as a notification's tap target; the data URI keeps each chat's buttons apart. */
    private fun chat(c: Context, convId: String, title: String, isGroup: Boolean) = Intent(c, ChatActivity::class.java)
        .setData(Uri.parse("rabta://chat/$convId")).putExtra("peerId", convId).putExtra("peerName", title).putExtra("isGroup", isGroup)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    private fun action(c: Context, what: String, convId: String, title: String, isGroup: Boolean, mutable: Boolean = false): PendingIntent {
        val intent = Intent(c, NotificationActionReceiver::class.java).setAction(what).setData(Uri.parse("rabta://chat/$convId"))
            .putExtra("id", convId).putExtra("title", title).putExtra("isGroup", isGroup)
        // A reply's text is filled in by Android, so that one intent must stay mutable.
        val flags = if (mutable) PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            else PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(c, id(convId), intent, flags)
    }
    private fun button(c: Context, label: String, intent: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat_rabta), label, intent)

    /** What a message says in a notification, like the chats list. */
    fun preview(body: String, msgType: String) = when (msgType) {
        "image" -> "📷 Photo"; "voice" -> "🎤 Voice message"; "file" -> "📄 File"; "video" -> "🎥 Video"; "sticker" -> "Sticker"
        "rich" -> runCatching { JSONObject(body).getString("text") }.getOrDefault("Message")
        else -> body.take(300)
    }

    /** A contact's photo when Rabta already has it, round like in the app. */
    fun photo(peerId: String): Icon? = ProfileHelper.get(peerId)?.let { Icon.createWithBitmap(round(it)) }
    private fun round(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height); val size = side.coerceAtMost(192)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { postTranslate(-(src.width - side) / 2f, -(src.height - side) / 2f); postScale(size.toFloat() / side, size.toFloat() / side) })
            }
        }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    fun muted(c: Context, convId: String) = Privacy.prefs(c).getLong("mute_$convId", 0) > System.currentTimeMillis()

    /** One popup per chat holding its latest messages, with Reply, Mark as read and Mute. */
    fun message(c: Context, convId: String, title: String, isGroup: Boolean, fromId: String, fromName: String, text: String) {
        if (muted(c, convId)) return
        val lines = synchronized(recent) {
            recent.getOrPut(convId) { mutableListOf() }.apply { add(Line(text, System.currentTimeMillis(), fromId, fromName)); while (size > 6) removeAt(0) }.toList()
        }
        val b = Notification.Builder(c, MessageService.MSG_CHANNEL).setSmallIcon(R.drawable.ic_stat_rabta)
            .setContentIntent(PendingIntent.getActivity(c, id(convId), chat(c, convId, title, isGroup), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE).setShowWhen(true).setWhen(lines.last().at)
        when {
            Privacy.hidePreviews(c) -> b.setContentTitle(title).setContentText(if (lines.size > 1) "${lines.size} new messages" else "New message")
            Build.VERSION.SDK_INT >= 28 -> b.setStyle(Notification.MessagingStyle(Person.Builder().setName("You").setKey(Session.id).build()).apply {
                if (isGroup) { conversationTitle = title; isGroupConversation = true }
                lines.forEach { addMessage(Notification.MessagingStyle.Message(it.text, it.at, Person.Builder().setName(it.fromName).setKey(it.fromId).setIcon(photo(it.fromId)).build())) }
            })
            else -> b.setContentTitle(title).setContentText(lines.last().text)
                .setStyle(Notification.InboxStyle().also { style -> lines.forEach { style.addLine(if (isGroup) "${it.fromName}: ${it.text}" else it.text) } })
        }
        if (!isGroup) photo(fromId)?.let { b.setLargeIcon(it) }
        b.addAction(button(c, "Reply", action(c, REPLY, convId, title, isGroup, mutable = true))
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply to $title").build())
            .apply { if (Build.VERSION.SDK_INT >= 28) setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY) }.build())
        b.addAction(button(c, "Mark as read", action(c, READ, convId, title, isGroup))
            .apply { if (Build.VERSION.SDK_INT >= 28) setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) }.build())
        b.addAction(button(c, "Mute", action(c, MUTE, convId, title, isGroup))
            .apply { if (Build.VERSION.SDK_INT >= 28) setSemanticAction(Notification.Action.SEMANTIC_ACTION_MUTE) }.build())
        runCatching { manager(c)?.notify(id(convId), b.build()) }
    }

    /** The chat was opened or answered: its popup and remembered lines go. */
    fun clear(c: Context, convId: String) {
        synchronized(recent) { recent.remove(convId) }
        runCatching { manager(c)?.cancel(id(convId)) }
    }

    fun markRead(c: Context, convId: String, isGroup: Boolean) {
        clear(c, convId)
        Thread { runCatching { Db.get(c).clearUnread(convId) }; RabtaEvents.postHistoryChanged(convId) }.start()
        if (!isGroup) WsClient.sendRead(convId, "all")
    }

    /** Like WhatsApp's notification button: no popups from this chat for 8 hours. */
    fun mute(c: Context, convId: String) {
        Privacy.prefs(c).edit().putLong("mute_$convId", System.currentTimeMillis() + MUTE_MS).apply()
        clear(c, convId)
        Toast.makeText(c, "Muted for 8 hours", Toast.LENGTH_SHORT).show()
    }

    /** Sends a reply typed in the notification through the usual outbox, then treats the chat as read. */
    fun reply(c: Context, convId: String, title: String, isGroup: Boolean, text: String) {
        val body = text.trim()
        if (body.isNotEmpty() && body.toByteArray().size <= 12000 && (isGroup || !Privacy.blocked(c, convId))) Outbox.send(convId, title, isGroup, body, "text")
        markRead(c, convId, isGroup)
    }

    /** "Missed voice call" or "Missed video call", with Call back and Message. */
    fun missedCall(c: Context, peerId: String, peerName: String, video: Boolean) {
        if (Privacy.blocked(c, peerId)) return
        val nm = manager(c) ?: return
        val kind = if (video) "video" else "voice"
        val open = PendingIntent.getActivity(c, missedId(peerId), chat(c, peerId, peerName, false), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val callBack = PendingIntent.getActivity(c, missedId(peerId), chat(c, peerId, peerName, false).setData(Uri.parse("rabta://call/$kind/$peerId")).putExtra("requestedCallKind", kind),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(c, MISSED_CHANNEL).setSmallIcon(R.drawable.ic_stat_rabta)
            .setContentTitle(peerName).setContentText(if (video) "Missed video call" else "Missed voice call")
            .setShowWhen(true).setWhen(System.currentTimeMillis()).setAutoCancel(true).setContentIntent(open)
            .addAction(button(c, "Call back", callBack).build()).addAction(button(c, "Message", open).build())
        if (Build.VERSION.SDK_INT >= 30) b.setCategory(Notification.CATEGORY_MISSED_CALL)
        photo(peerId)?.let { b.setLargeIcon(it) }
        runCatching { nm.notify(missedId(peerId), b.build()) }
    }
}

/** The buttons on Rabta's notifications. Not exported: only Android's notification system sends these. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Session.init(c)
        if (!Session.loggedIn) return
        if (intent.action == ChatNotifications.HANG_UP) { if (VideoCall.active) VideoCall.hangUp() else VoiceCall.end(); return }
        val id = intent.getStringExtra("id").orEmpty().ifEmpty { return }
        val isGroup = intent.getBooleanExtra("isGroup", false)
        when (intent.action) {
            ChatNotifications.REPLY -> ChatNotifications.reply(c, id, intent.getStringExtra("title").orEmpty(), isGroup,
                RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ChatNotifications.KEY_REPLY)?.toString().orEmpty())
            ChatNotifications.READ -> ChatNotifications.markRead(c, id, isGroup)
            ChatNotifications.MUTE -> ChatNotifications.mute(c, id)
            ChatNotifications.DECLINE -> MessageService.decline(c, intent.getStringExtra("fromId").orEmpty(), id, intent.getBooleanExtra("isVideo", false))
        }
    }
}
