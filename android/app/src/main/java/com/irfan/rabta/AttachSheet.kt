package com.irfan.rabta

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * WhatsApp-style attach sheet: a grid of coloured actions, then the phone's recent photos and videos.
 * Actions that Rabta does not have yet are marked "Soon" instead of pretending to work.
 */
class AttachSheet(
    private val a: Activity,
    private val title: String,
    private val gallery: () -> Unit,
    private val camera: () -> Unit,
    private val document: () -> Unit,
    private val contact: () -> Unit,
    private val stickers: () -> Unit,
    private val pick: (RecentMedia.Item) -> Unit,
    private val requestAccess: () -> Unit,
) {
    private val dialog = Dialog(a)
    private val media = mutableListOf<RecentMedia.Item>()
    private val thumbs = LruCache<android.net.Uri, Bitmap>(80)
    private val loader = Executors.newFixedThreadPool(2)
    private lateinit var grid: GridView
    private lateinit var access: LinearLayout
    private fun dp(n: Int) = RabtaUi.dp(a, n)

    fun show() {
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(8))
            background = RabtaUi.shape(a, AppTheme.surface(a), 28).apply { val r = dp(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        root.addView(View(a).apply { background = RabtaUi.shape(a, AppTheme.line(a), 2) }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })
        root.addView(RabtaUi.text(a, title, 13f, true, AppTheme.sub(a)).apply { setPadding(dp(8), 0, 0, dp(8)); maxLines = 1 })
        val actions = GridLayout(a).apply { columnCount = 4 }
        val cell = (a.resources.displayMetrics.widthPixels - dp(24)) / 4
        fun add(icon: String, label: String, color: Int, soon: Boolean = false, run: () -> Unit) {
            actions.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true
                contentDescription = if (soon) "$label. Coming soon" else label; setPadding(0, dp(6), 0, dp(10))
                background = RabtaUi.ripple(a, Color.TRANSPARENT, 16)
                setOnClickListener { if (soon) Toast.makeText(a, "$label: coming soon", Toast.LENGTH_SHORT).show() else { dialog.dismiss(); run() } }
                val box = FrameLayout(a)
                box.addView(UiIcons.image(a, icon, color, 26, label).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; alpha = if (soon) 0.45f else 1f
                    background = RabtaUi.shape(a, Color.TRANSPARENT, 16, AppTheme.line(a))
                }, FrameLayout.LayoutParams(dp(64), dp(48)))
                if (soon) box.addView(RabtaUi.badge(a, "Soon").apply { textSize = 9f; setPadding(dp(6), dp(1), dp(6), dp(1)) }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
                addView(box, LinearLayout.LayoutParams(dp(68), dp(52)))
                addView(RabtaUi.text(a, label, 13f, color = if (soon) AppTheme.sub(a) else AppTheme.text(a)).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0); maxLines = 1; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
            }, GridLayout.LayoutParams().apply { width = cell })
        }
        add("gallery", "Gallery", 0xFF2D8CFF.toInt(), run = gallery)
        add("photo", "Camera", 0xFFFF2E74.toInt(), run = camera)
        add("location", "Location", 0xFF1DAA61.toInt(), soon = true) {}
        add("profile", "Contact", 0xFF0795DC.toInt(), run = contact)
        add("doc", "Document", 0xFF7F66FF.toInt(), run = document)
        add("poll", "Poll", 0xFFFFBC38.toInt(), soon = true) {}
        add("event", "Event", 0xFFFF5C7A.toInt(), soon = true) {}
        add("sticker", "Stickers", AppTheme.accent(a), run = stickers)
        root.addView(actions)

        // Recent photos and videos straight from the phone, like WhatsApp; tap one to send it.
        val recent = FrameLayout(a)
        grid = GridView(a).apply {
            numColumns = 4; horizontalSpacing = dp(3); verticalSpacing = dp(3); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = Adapter(); isVerticalScrollBarEnabled = false; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setOnItemClickListener { _, _, position, _ -> media.getOrNull(position)?.let { dialog.dismiss(); pick(it) } }
        }
        recent.addView(grid, FrameLayout.LayoutParams(-1, -1))
        access = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(16), dp(8), dp(16), dp(8))
            if (RecentMedia.canAsk(a)) {
                addView(RabtaUi.text(a, "See your recent photos and videos here", 14f, true).apply { gravity = Gravity.CENTER })
                addView(RabtaUi.text(a, "Rabta only reads them to show this list. Nothing is sent until you tap one.", 12f, color = AppTheme.sub(a)).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10)) })
                addView(RabtaUi.button(a, "Allow access", true) { requestAccess() }.apply { gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-2, -2) })
            } else {
                // The Google Play build does not read the phone's photos: Android's picker hands Rabta only what is picked.
                addView(RabtaUi.text(a, "Send photos and videos from your gallery", 14f, true).apply { gravity = Gravity.CENTER })
                addView(RabtaUi.text(a, "Rabta sees only the ones you pick.", 12f, color = AppTheme.sub(a)).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10)) })
                addView(RabtaUi.button(a, "Open gallery", true) { dialog.dismiss(); gallery() }.apply { gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-2, -2) })
            }
        }
        recent.addView(access, FrameLayout.LayoutParams(-1, -1))
        root.addView(recent, LinearLayout.LayoutParams(-1, dp(250)).apply { topMargin = dp(6) })

        dialog.setContentView(root)
        dialog.setOnDismissListener { loader.shutdownNow() }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.45f)
        }
        dialog.show()
        dialog.window?.apply { setGravity(Gravity.BOTTOM); setLayout(-1, -2) }
        reload()
    }

    /** Called again after the user allows access. */
    fun reload() {
        if (!dialog.isShowing) return
        if (!RecentMedia.hasAccess(a)) { access.visibility = View.VISIBLE; grid.visibility = View.INVISIBLE; return }
        access.visibility = View.GONE; grid.visibility = View.VISIBLE
        loader.execute {
            val found = RecentMedia.query(a, 120)
            a.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                media.clear(); media += found; (grid.adapter as BaseAdapter).notifyDataSetChanged()
                if (found.isEmpty()) { access.visibility = View.VISIBLE; access.removeAllViews(); access.addView(RabtaUi.text(a, "No photos or videos on this phone yet", 13f, color = AppTheme.sub(a)).apply { gravity = Gravity.CENTER }) }
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = media.size
        override fun getItem(position: Int): Any = media[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val size = (parent.width - dp(9)) / 4
            val cellView = (convert as? FrameLayout) ?: FrameLayout(a).apply {
                setBackgroundColor(AppTheme.raised(a))
                addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
                addView(RabtaUi.text(a, "", 11f, true, Color.WHITE).apply { setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt()); setPadding(dp(5), 0, dp(5), dp(3)) },
                    FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            }
            cellView.layoutParams = AbsListView.LayoutParams(-1, if (size > 0) size else dp(84))
            val item = media[position]
            val image = cellView.getChildAt(0) as ImageView; val badge = cellView.getChildAt(1) as TextView
            cellView.contentDescription = if (item.video) "Video ${RecentMedia.duration(item.durationMs)}" else "Photo"
            badge.text = if (item.video) "▶ ${RecentMedia.duration(item.durationMs)}" else ""
            image.tag = item.uri
            val cached = thumbs.get(item.uri); image.setImageBitmap(cached)
            if (cached == null) runCatching {
                loader.execute {
                    val bmp = RecentMedia.thumbnail(a, item) ?: return@execute
                    thumbs.put(item.uri, bmp)
                    a.runOnUiThread { if (image.tag == item.uri) image.setImageBitmap(bmp) }
                }
            }
            return cellView
        }
    }
}
