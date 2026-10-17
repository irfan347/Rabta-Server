package com.irfan.rabta

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout

/**
 * Appearance: System / Light / Dark, four premium themes with live previews, and a custom accent.
 * Saved on this phone only; nothing about accounts or chats changes.
 */
class ThemeActivity : ThemedActivity() {
    private fun dp(n: Int) = RabtaUi.dp(this, n)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val col = FeatureUi.page(this, "Appearance")
        col.addView(RabtaUi.section(this, "Mode"))
        val modes = LinearLayout(this).apply { background = RabtaUi.shape(this@ThemeActivity, AppTheme.surface(this@ThemeActivity), 999); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        listOf("System", "Light", "Dark").forEach { value ->
            val on = AppTheme.mode(this) == value
            modes.addView(RabtaUi.text(this, value, 15f, true, if (on) AppTheme.onPrimary(this) else AppTheme.text(this)).apply {
                gravity = Gravity.CENTER; minHeight = dp(44); isFocusable = true; contentDescription = "$value mode${if (on) ", selected" else ""}"
                background = if (on) RabtaUi.shape(this@ThemeActivity, AppTheme.primary(this@ThemeActivity), 999) else RabtaUi.ripple(this@ThemeActivity, Color.TRANSPARENT, 999)
                setOnClickListener { getSharedPreferences("appearance", 0).edit().putString("mode", value).apply(); recreate() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        col.addView(modes, LinearLayout.LayoutParams(-1, -2))

        col.addView(RabtaUi.section(this, "Theme"))
        val grid = GridLayout(this).apply { columnCount = 2 }
        val width = (resources.displayMetrics.widthPixels - dp(40) - dp(12)) / 2
        AppTheme.palettes.forEach { p -> grid.addView(card(p, AppTheme.themeKey(this) == p.key) { AppTheme.setTheme(this, p.key); recreate() }, GridLayout.LayoutParams().apply { this.width = width; setMargins(0, 0, dp(12), dp(12)) }) }
        val customAccent = AppTheme.accents.firstOrNull { it.key == AppTheme.accentKey(this) } ?: AppTheme.accents[0]
        grid.addView(card(AppTheme.custom(customAccent), AppTheme.themeKey(this) == "custom") { AppTheme.setTheme(this, "custom"); recreate() }, GridLayout.LayoutParams().apply { this.width = width; setMargins(0, 0, dp(12), dp(12)) })
        col.addView(grid)

        col.addView(RabtaUi.section(this, "Custom accent"))
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        AppTheme.accents.forEach { a ->
            val on = AppTheme.themeKey(this) == "custom" && AppTheme.accentKey(this) == a.key
            dots.addView(View(this).apply {
                isFocusable = true; contentDescription = "${a.name} accent${if (on) ", selected" else ""}"
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(if (AppTheme.dark(this@ThemeActivity)) a.dark else a.light)
                    if (on) setStroke(dp(3), AppTheme.text(this@ThemeActivity))
                }
                setOnClickListener { AppTheme.setTheme(this@ThemeActivity, "custom", a.key); recreate() }
            }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(8) })
        }
        col.addView(dots, LinearLayout.LayoutParams(-1, dp(40)))
        col.addView(RabtaUi.text(this, "Pick a colour to use your own accent on graphite surfaces. Your theme is saved on this phone only.", 13f, color = AppTheme.sub(this)).apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun card(p: AppTheme.Palette, selected: Boolean, pick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; isFocusable = true; contentDescription = "${p.name} theme${if (selected) ", selected" else ""}"
        setPadding(dp(8), dp(8), dp(8), dp(10)); setOnClickListener { pick() }
        background = RabtaUi.shape(this@ThemeActivity, AppTheme.surface(this@ThemeActivity), 20, if (selected) AppTheme.accent(this@ThemeActivity) else AppTheme.line(this@ThemeActivity)).apply {
            if (selected) setStroke(dp(2), AppTheme.accent(this@ThemeActivity))
        }
        addView(ThemePreview(this@ThemeActivity, p, AppTheme.dark(this@ThemeActivity)), LinearLayout.LayoutParams(-1, dp(128)))
        val row = LinearLayout(this@ThemeActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(4), 0) }
        row.addView(RabtaUi.text(this@ThemeActivity, p.name, 15f, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (selected) row.addView(UiIcons.image(this@ThemeActivity, "check", AppTheme.accent(this@ThemeActivity), 20, "Selected"), LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(row)
    }
}

/** A small drawing of the theme: header, a received and a sent bubble, and a button, in its real colours. */
class ThemePreview(c: Context, private val p: AppTheme.Palette, private val dark: Boolean) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val t = if (dark) p.dark else p.light; val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = t.bg; r.set(0f, 0f, w, h); canvas.drawRoundRect(r, 14 * d, 14 * d, paint)
        paint.color = t.surface; r.set(0f, 0f, w, 26 * d); canvas.drawRoundRect(r, 14 * d, 14 * d, paint); canvas.drawRect(0f, 14 * d, w, 26 * d, paint)
        paint.color = t.accent; canvas.drawCircle(16 * d, 13 * d, 6 * d, paint)
        paint.color = if (dark) 0x66FFFFFF else 0x55000000; r.set(28 * d, 10 * d, w * 0.55f, 16 * d); canvas.drawRoundRect(r, 3 * d, 3 * d, paint)
        paint.color = t.surface; r.set(10 * d, 36 * d, w * 0.62f, 58 * d); canvas.drawRoundRect(r, 9 * d, 9 * d, paint)
        paint.color = t.sent; r.set(w * 0.36f, 66 * d, w - 10 * d, 88 * d); canvas.drawRoundRect(r, 9 * d, 9 * d, paint)
        paint.color = AppTheme.readTick(context); canvas.drawCircle(w - 20 * d, 82 * d, 2.5f * d, paint)
        paint.color = p.primary; canvas.drawCircle(w - 22 * d, h - 20 * d, 12 * d, paint)
        paint.color = p.onPrimary; paint.strokeWidth = 2 * d; canvas.drawLine(w - 27 * d, h - 20 * d, w - 17 * d, h - 20 * d, paint); canvas.drawLine(w - 22 * d, h - 25 * d, w - 22 * d, h - 15 * d, paint)
    }
}
