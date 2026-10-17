package com.irfan.rabta

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/**
 * WhatsApp-style "Add status": quick options, then Camera and the phone's recent photos and videos.
 * It only picks; StatusActivity still chooses the audience and uploads. Returns "kind" (text, image, video) and a Uri.
 */
class AddStatusActivity : ThemedActivity() {
    private data class Media(val uri: Uri, val video: Boolean, val durationMs: Long)
    private val media = mutableListOf<Media>()
    private val thumbs = LruCache<Uri, Bitmap>(120)
    private val loader = Executors.newFixedThreadPool(2)
    private lateinit var grid: GridView
    private lateinit var access: LinearLayout
    private var captureUri: Uri? = null
    private var captureVideo = false
    private fun dp(n: Int) = RabtaUi.dp(this, n)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(AppTheme.surface(this@AddStatusActivity)) }
        // Title bar: close · Add status
        val head = FrameLayout(this).apply { setPadding(dp(8), dp(12), dp(8), dp(4)) }
        head.addView(RabtaUi.iconButton(this, "close", "Close") { finish() }.apply { UiIcons.set(this, "close", AppTheme.text(this@AddStatusActivity), 26, "Close") },
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
        head.addView(RabtaUi.text(this, "Add status", 24f, true).apply { gravity = Gravity.CENTER; androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) },
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(head)
        // Quick options: Text is real; the rest are honest placeholders.
        val options = LinearLayout(this).apply { setPadding(dp(12), dp(12), dp(12), dp(8)) }
        options.addView(option("compose", "Text", false) { done("text", null) })
        options.addView(option("mic", "Voice", true) {})
        options.addView(option("sound", "Music", true) {})
        options.addView(option("spaces", "Layout", true) {})
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(options) })
        val recents = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(12), dp(8), dp(8)) }
        recents.addView(RabtaUi.text(this, "Recents", 20f, false, AppTheme.sub(this)), LinearLayout.LayoutParams(0, -2, 1f))
        recents.addView(RabtaUi.button(this, "All photos") { browse() }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2); background = RabtaUi.ripple(this@AddStatusActivity, android.graphics.Color.TRANSPARENT, 16)
            setTextColor(AppTheme.accent(this@AddStatusActivity))
        })
        root.addView(recents)
        val body = FrameLayout(this)
        grid = GridView(this).apply {
            numColumns = 3; horizontalSpacing = dp(2); verticalSpacing = dp(2); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setBackgroundColor(AppTheme.bg(this@AddStatusActivity)); adapter = Adapter()
            setOnItemClickListener { _, _, position, _ -> if (position == 0) camera() else media[position - 1].let { done(if (it.video) "video" else "image", it.uri) } }
        }
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        // Without gallery access the grid shows only Camera, with a clear way to allow access or browse.
        access = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(24), dp(24), dp(24)); visibility = View.GONE
            addView(RabtaUi.text(this@AddStatusActivity, "Show your recent photos and videos here", 15f, true).apply { gravity = Gravity.CENTER })
            addView(RabtaUi.text(this@AddStatusActivity, "Rabta reads them only to show this list. Nothing is shared until you pick one and choose who sees it.", 13f, color = AppTheme.sub(this@AddStatusActivity)).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(14)) })
            addView(RabtaUi.button(this@AddStatusActivity, "Allow access", true) { requestPermissions(mediaPermissions(), PERMISSION) }.apply { gravity = Gravity.CENTER })
        }
        body.addView(access, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadMedia()
    }
    override fun onDestroy() { loader.shutdownNow(); super.onDestroy() }

    private fun option(icon: String, label: String, soon: Boolean, run: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true
        contentDescription = if (soon) "$label. Coming soon" else label
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { if (soon) Toast.makeText(this@AddStatusActivity, "$label status: coming soon", Toast.LENGTH_SHORT).show() else run() }
        val circle = FrameLayout(this@AddStatusActivity)
        circle.addView(TextView(this@AddStatusActivity).apply {
            UiIcons.set(this, icon, if (soon) AppTheme.sub(this@AddStatusActivity) else AppTheme.text(this@AddStatusActivity), 30, label); gravity = Gravity.CENTER
            background = RabtaUi.shape(this@AddStatusActivity, AppTheme.raised(this@AddStatusActivity), 999); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(72), dp(72)))
        if (soon) circle.addView(RabtaUi.badge(this@AddStatusActivity, "Soon"), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        addView(circle, LinearLayout.LayoutParams(dp(76), dp(76)))
        addView(RabtaUi.text(this@AddStatusActivity, label, 15f, false, if (soon) AppTheme.sub(this@AddStatusActivity) else AppTheme.text(this@AddStatusActivity)).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    private fun mediaPermissions() = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    /** Full access, or (Android 14) the photos the user selected. */
    private fun hasMediaAccess() = mediaPermissions().any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun loadMedia() {
        if (!hasMediaAccess()) { access.visibility = View.VISIBLE; return }
        access.visibility = View.GONE
        loader.execute {
            val found = mutableListOf<Media>()
            runCatching {
                val files = MediaStore.Files.getContentUri("external")
                val type = MediaStore.Files.FileColumns.MEDIA_TYPE
                contentResolver.query(files, arrayOf(MediaStore.Files.FileColumns._ID, type, "duration"),
                    "$type=? OR $type=?", arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()),
                    "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext() && found.size < 300) {
                        val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        found += Media(ContentUris.withAppendedId(base, c.getLong(0)), video, if (c.isNull(2)) 0 else c.getLong(2))
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                media.clear(); media += found; (grid.adapter as BaseAdapter).notifyDataSetChanged()
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = media.size + 1
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val size = (parent.width - dp(4)) / 3
            val cell = (convert as? FrameLayout) ?: FrameLayout(this@AddStatusActivity).apply {
                addView(ImageView(this@AddStatusActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
                addView(TextView(this@AddStatusActivity).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                addView(RabtaUi.text(this@AddStatusActivity, "", 12f, true, android.graphics.Color.WHITE).apply {
                    setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt()); setPadding(dp(6), 0, dp(6), dp(4))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            }
            cell.layoutParams = AbsListView.LayoutParams(-1, if (size > 0) size else dp(120))
            val image = cell.getChildAt(0) as ImageView; val label = cell.getChildAt(1) as TextView; val badge = cell.getChildAt(2) as TextView
            if (position == 0) {
                cell.setBackgroundColor(AppTheme.raised(this@AddStatusActivity)); cell.contentDescription = "Camera"
                image.setImageDrawable(null); image.tag = null; badge.text = ""
                label.visibility = View.VISIBLE
                UiIcons.set(label, "photo", AppTheme.accent(this@AddStatusActivity), 34, "Camera")
                label.append("\nCamera")
                label.textSize = 18f; label.setTextColor(AppTheme.sub(this@AddStatusActivity))
                return cell
            }
            val item = media[position - 1]
            cell.setBackgroundColor(AppTheme.raised(this@AddStatusActivity)); label.visibility = View.GONE
            cell.contentDescription = if (item.video) "Video ${duration(item.durationMs)}" else "Photo"
            badge.text = if (item.video) "▶ ${duration(item.durationMs)}" else ""
            image.tag = item.uri
            val cached = thumbs.get(item.uri)
            image.setImageBitmap(cached)
            if (cached == null) loader.execute {
                val bmp = runCatching { thumbnail(item) }.getOrNull() ?: return@execute
                thumbs.put(item.uri, bmp)
                runOnUiThread { if (image.tag == item.uri) image.setImageBitmap(bmp) }
            }
            return cell
        }
    }

    @Suppress("DEPRECATION")
    private fun thumbnail(item: Media): Bitmap? = if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(item.uri, android.util.Size(256, 256), null)
        else if (item.video) MediaStore.Video.Thumbnails.getThumbnail(contentResolver, ContentUris.parseId(item.uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
        else MediaStore.Images.Thumbnails.getThumbnail(contentResolver, ContentUris.parseId(item.uri), MediaStore.Images.Thumbnails.MINI_KIND, null)

    private fun duration(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    private fun camera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION); return }
        AlertDialog.Builder(this).setItems(arrayOf("Take photo", "Record video")) { _, which -> capture(which == 1) }.show()
    }
    private fun capture(video: Boolean) {
        val file = File(filesDir, "attachments/status-${System.currentTimeMillis()}.${if (video) "mp4" else "jpg"}").also { it.parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file); captureUri = uri; captureVideo = video
        val action = if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE
        runCatching { startActivityForResult(Intent(action).putExtra(MediaStore.EXTRA_OUTPUT, uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION), CAPTURE) }
            .onFailure { Toast.makeText(this, "No camera app available", Toast.LENGTH_SHORT).show() }
    }
    private fun browse() = runCatching {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*")), BROWSE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            CAPTURE -> captureUri?.let { done(if (captureVideo) "video" else "image", it) }
            BROWSE -> data?.data?.let { uri -> done(if (contentResolver.getType(uri)?.startsWith("video/") == true) "video" else "image", uri) }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION -> loadMedia()
            CAMERA_PERMISSION -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) camera() else Toast.makeText(this, "Camera permission is needed to take a photo", Toast.LENGTH_SHORT).show()
        }
    }
    private fun done(kind: String, uri: Uri?) {
        setResult(RESULT_OK, Intent().putExtra("kind", kind).apply { if (uri != null) data = uri; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        finish()
    }

    companion object { private const val PERMISSION = 41; private const val CAMERA_PERMISSION = 42; private const val CAPTURE = 43; private const val BROWSE = 44 }
}
