package com.irfan.rabta

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** The phone's own recent photos and videos (MediaStore), for Add status and the chat attach sheet. */
object RecentMedia {
    data class Item(val uri: Uri, val video: Boolean, val durationMs: Long)

    /** What Rabta asks for on Android [sdk], of the permissions this build [declared]. */
    internal fun permissions(sdk: Int, declared: Set<String>): Array<String> = when {
        sdk >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        sdk >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }.filter { it in declared }.toTypedArray()
    /**
     * None in the Google Play build on Android 13 and later: Play lets only gallery-type apps read all photos, so that
     * build sends from Android's own picker, which hands Rabta only what the user picks.
     */
    fun permissions(c: Context) = permissions(Build.VERSION.SDK_INT, Config.declared(c))
    /** Whether this build may offer the phone's recent photos and videos at all. */
    fun canAsk(c: Context) = permissions(c).isNotEmpty()
    /** Full access, or (Android 14) the photos the user selected. */
    fun hasAccess(c: Context) = permissions(c).any { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Newest first. Blocking: call off the main thread. */
    fun query(c: Context, limit: Int = 300): List<Item> {
        val found = mutableListOf<Item>()
        runCatching {
            val files = MediaStore.Files.getContentUri("external")
            val type = MediaStore.Files.FileColumns.MEDIA_TYPE
            c.contentResolver.query(files, arrayOf(MediaStore.Files.FileColumns._ID, type, "duration"),
                "$type=? OR $type=?", arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()),
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { cursor ->
                while (cursor.moveToNext() && found.size < limit) {
                    val video = cursor.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    found += Item(ContentUris.withAppendedId(base, cursor.getLong(0)), video, if (cursor.isNull(2)) 0 else cursor.getLong(2))
                }
            }
        }
        return found
    }

    /** A small preview. Blocking: call off the main thread. */
    @Suppress("DEPRECATION")
    fun thumbnail(c: Context, item: Item): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) c.contentResolver.loadThumbnail(item.uri, android.util.Size(256, 256), null)
        else if (item.video) MediaStore.Video.Thumbnails.getThumbnail(c.contentResolver, ContentUris.parseId(item.uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
        else MediaStore.Images.Thumbnails.getThumbnail(c.contentResolver, ContentUris.parseId(item.uri), MediaStore.Images.Thumbnails.MINI_KIND, null)
    }.getOrNull()

    fun duration(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
}
