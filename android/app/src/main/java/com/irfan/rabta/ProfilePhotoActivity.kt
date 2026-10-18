package com.irfan.rabta

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/** Picking, cropping, saving and removing your own profile photo; shared by Profile and the photo screen. */
object ProfilePhoto {
    /** Server limit is 256 KB; a 480px JPEG stays far below it while looking sharp in large avatars. */
    private const val SIZE = 480

    fun current(): Bitmap? = Session.profilePic.takeIf { it.isNotEmpty() }?.let { MediaHelper.base64ToBitmap(it) }

    private var lastSync = 0L
    private var uploading = 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Keeps this phone's photo and the server's copy in step, at most every 20 s so a sleeping server is retried
     * without hammering it. Main thread.
     * - Signing out clears this phone's copy, but the server keeps it: fetch it back.
     * - A photo the server never got (chosen offline, or before photos were shared at all) is shared now.
     */
    fun sync(done: () -> Unit) {
        if (!Session.loggedIn) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSync < 20_000) return
        lastSync = now
        val owner = Session.id
        if (Session.profilePic.isNotEmpty()) { if (!Session.profilePicShared) share(owner); return }
        WsClient.fetchPhoto(Session.serverUrl, Session.token, owner) { answer ->
            val b64 = answer?.photo
            if (b64.isNullOrEmpty() || Session.id != owner || Session.profilePic.isNotEmpty()) return@fetchPhoto
            val bitmap = MediaHelper.base64ToBitmap(b64) ?: return@fetchPhoto
            Session.profilePic = b64; Session.profilePicShared = true; ProfileHelper.set(owner, bitmap); done()
        }
    }

    private fun share(owner: String) {
        if (uploading > 0) return
        val local = Session.profilePic
        uploading++
        Thread {
            val photo = shareable(local)
            main.post {
                uploading--
                if (photo != null && Session.id == owner && Session.profilePic == local) upload(owner, local, photo) { _, _ -> }
            }
        }.start()
    }

    /** Sends [photo], the server's copy of this phone's [local] one. Main thread. */
    private fun upload(owner: String, local: String, photo: String, cb: (Boolean, String) -> Unit) {
        uploading++; Session.profilePicShared = false
        WsClient.uploadPhoto(Session.serverUrl, Session.token, photo) { ok, msg ->
            uploading--
            if (ok && Session.id == owner) when (Session.profilePic) {
                local -> Session.profilePicShared = true
                "" -> WsClient.uploadPhoto(Session.serverUrl, Session.token, "") { _, _ -> }   // removed meanwhile: keep it removed
                else -> Session.profilePicShared = false                                         // changed meanwhile: share the new one next
            }
            cb(ok, msg)
        }
    }

    /** A [SIZE]px JPEG well under the server limit, as base64. Background thread. */
    private fun encode(bitmap: Bitmap): String {
        val square = Bitmap.createScaledBitmap(bitmap, SIZE, maxOf(1, SIZE * bitmap.height / maxOf(1, bitmap.width)), true)
        val out = ByteArrayOutputStream(); var quality = 88
        do { out.reset(); square.compress(Bitmap.CompressFormat.JPEG, quality, out); quality -= 8 } while (out.size() > 200 * 1024 && quality > 40)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** [b64] as the server accepts it: JPEG or PNG up to 256 KB on one base64 line; older or larger photos are re-encoded. */
    private fun shareable(b64: String): String? {
        val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: IllegalArgumentException) { return null }
        val jpeg = bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val png = bytes.size > 8 && bytes.copyOf(8).contentEquals(PNG)
        if ((jpeg || png) && bytes.size <= 256 * 1024) return Base64.encodeToString(bytes, Base64.NO_WRAP)
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::encode)
    }

    /** Opens the picked photo in the circle crop, then saves the result. */
    fun cropAndSave(a: Activity, uri: Uri, decoded: () -> Unit = {}, done: () -> Unit) {
        Thread {
            val bitmap = MediaHelper.decodeBitmap(a, uri, 1600)
            decoded()
            a.runOnUiThread {
                if (a.isDestroyed || a.isFinishing) return@runOnUiThread
                if (bitmap == null) { Toast.makeText(a, "Could not open that image", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                CropScreen.show(a, bitmap, true) { cropped -> save(a, cropped, done) }
            }
        }.start()
    }

    fun save(a: Activity, bitmap: Bitmap, done: () -> Unit) {
        val owner = Session.id
        Thread {
            val encoded = encode(bitmap)
            a.runOnUiThread {
                if (Session.id != owner) return@runOnUiThread
                Session.profilePic = encoded; ProfileHelper.set(owner, MediaHelper.base64ToBitmap(encoded)); done()
                Toast.makeText(a, "Updating photo…", Toast.LENGTH_SHORT).show()
                upload(owner, encoded, encoded) { ok, msg ->
                    if (!a.isDestroyed) Toast.makeText(a, if (ok) "Profile photo updated" else "Photo saved on this phone, but it could not be shared: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    fun remove(a: Activity, done: () -> Unit) {
        AlertDialog.Builder(a).setTitle("Remove profile photo?").setMessage("Your contacts will see your initials instead.")
            .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                val owner = Session.id
                uploading++
                WsClient.uploadPhoto(Session.serverUrl, Session.token, "") { ok, msg ->
                    uploading--
                    if (Session.id != owner) return@uploadPhoto
                    // Forget the photo even if this screen has closed, or it would be shared again later.
                    if (ok) { Session.profilePic = ""; ProfileHelper.set(owner, null) }
                    if (a.isDestroyed) return@uploadPhoto
                    if (ok) { done(); Toast.makeText(a, "Profile photo removed", Toast.LENGTH_SHORT).show() }
                    else Toast.makeText(a, "Could not remove the photo: $msg", Toast.LENGTH_LONG).show()
                }
            }.show()
    }
}

/**
 * WhatsApp-style full-screen profile photo. Your own photo adds Gallery, Camera, Crop and Remove;
 * a contact's photo is view-only.
 */
class ProfilePhotoActivity : ThemedActivity() {
    private lateinit var photo: ZoomImageView
    private lateinit var empty: TextView
    private var own = true
    private var peerId = ""
    private var captureUri: Uri? = null
    private fun dp(n: Int) = RabtaUi.dp(this, n)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        peerId = intent.getStringExtra("peerId").orEmpty().ifBlank { Session.id }
        own = peerId == Session.id
        val name = if (own) "Profile photo" else intent.getStringExtra("peerName").orEmpty().ifBlank { "Profile photo" }
        RabtaUi.systemBars(this, true)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        photo = ZoomImageView(this).apply { contentDescription = if (own) "Your profile photo" else "$name profile photo" }
        root.addView(photo, FrameLayout.LayoutParams(-1, -1))
        empty = RabtaUi.text(this, "No profile photo", 16f, color = 0xFFB8C2CC.toInt()).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        root.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        top.addView(RabtaUi.iconButton(this, "back", "Back") { finish() }.apply { UiIcons.set(this, "back", Color.WHITE, 24, "Back") })
        top.addView(RabtaUi.text(this, name, 19f, true, Color.WHITE).apply { setPadding(dp(4), 0, 0, 0); maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        if (own) {
            val actions = LinearLayout(this).apply {
                gravity = Gravity.CENTER; setPadding(dp(12), dp(14), dp(12), dp(18))
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0))
            }
            actions.addView(action("gallery", "Gallery") { pick() }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(action("photo", "Camera") { camera() }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(action("crop", "Crop") { ProfilePhoto.current()?.let { CropScreen.show(this, it, true) { c -> ProfilePhoto.save(this, c) { show() } } } ?: Toast.makeText(this, "Add a photo first", Toast.LENGTH_SHORT).show() }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(action("trash", "Remove") { if (Session.profilePic.isEmpty()) Toast.makeText(this, "No photo to remove", Toast.LENGTH_SHORT).show() else ProfilePhoto.remove(this) { show() } }, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(actions, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        setContentView(root)
        show()
    }

    private fun action(icon: String, label: String, run: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true; contentDescription = label; setOnClickListener { run() }
        background = RabtaUi.ripple(this@ProfilePhotoActivity, Color.TRANSPARENT, 16); setPadding(0, dp(6), 0, dp(6))
        addView(UiIcons.image(this@ProfilePhotoActivity, icon, Color.WHITE, 24, label).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = RabtaUi.shape(this@ProfilePhotoActivity, 0x33FFFFFF, 999)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(RabtaUi.text(this@ProfilePhotoActivity, label, 13f, true, Color.WHITE).apply {
            gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(6), 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    private fun show() {
        if (own) ProfilePhoto.sync { if (!isDestroyed) show() }
        val bitmap = if (own) ProfilePhoto.current() else ProfileHelper.get(peerId)
        photo.setImageBitmap(bitmap)
        empty.visibility = if (bitmap == null) View.VISIBLE else View.GONE
        setResult(RESULT_OK)
    }

    private fun pick() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), PICK)
    private fun camera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA); return }
        val file = File(filesDir, "attachments/profile-${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file); captureUri = uri
        runCatching { startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION), CAPTURE) }
            .onFailure { Toast.makeText(this, "No camera app available", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val shot = captureUri
        if (requestCode == CAPTURE && shot != null) {
            captureUri = null
            if (resultCode == RESULT_OK) ProfilePhoto.cropAndSave(this, shot, { deleteCapture(shot) }) { show() } else deleteCapture(shot)
            return
        }
        if (requestCode == PICK && resultCode == RESULT_OK) data?.data?.let { ProfilePhoto.cropAndSave(this, it) { show() } }
    }
    /** The camera's full-size original is only an input; the saved avatar is the cropped copy. */
    private fun deleteCapture(uri: Uri) { runCatching { File(filesDir, "attachments/" + uri.lastPathSegment).delete() } }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA) { if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) camera() else Toast.makeText(this, "Camera permission is needed to take a photo", Toast.LENGTH_SHORT).show() }
    }

    companion object { private const val PICK = 61; private const val CAPTURE = 62; private const val CAMERA = 63 }
}
