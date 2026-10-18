package com.irfan.rabta

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Shared wordmark for the app's primary branded headers. */
object RabtaBrand {
    fun lockup(context: Context, iconSizeDp: Int, wordmarkColor: Int, textSizeSp: Float): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = LinearLayout.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher_fg)
            // The lime mark always sits on a NOVA navy tile, so it reads in light mode too.
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF080D14.toInt()); cornerRadius = dp(context, iconSizeDp) * 0.28f }
            contentDescription = null
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(context, iconSizeDp), dp(context, iconSizeDp)))
        row.addView(TextView(context).apply {
            text = "Rabta"
            textSize = textSizeSp
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(wordmarkColor)
            includeFontPadding = false
            maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(context, 7)
        })
        row.contentDescription = "Rabta"
        return row
    }

    /**
     * Header lockup: the Rabta mark in a small rounded tile, then "Rabta". Mark, tile and text follow the chosen
     * theme, so nothing clashes; the whole mark is always visible (no cropping at the screen edge).
     */
    fun wordmark(context: Context, markDp: Int, textSizeSp: Float): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; contentDescription = "Rabta"
        val tile = android.widget.FrameLayout(context).apply {
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(AppTheme.surface(context)); cornerRadius = dp(context, 10).toFloat(); setStroke(dp(context, 1), AppTheme.line(context))
            }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.rabta_mark); scaleType = ImageView.ScaleType.FIT_CENTER; setColorFilter(AppTheme.accent(context))
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, android.widget.FrameLayout.LayoutParams(dp(context, markDp * 3 / 4), dp(context, markDp * 3 / 4), Gravity.CENTER))
        }
        addView(tile, LinearLayout.LayoutParams(dp(context, markDp + 8), dp(context, markDp + 8)))
        addView(TextView(context).apply {
            text = "Rabta"; textSize = textSizeSp; letterSpacing = 0.01f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(AppTheme.text(context))
            includeFontPadding = false; maxLines = 1; importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(context, 10) })
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
