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

    /** NOVA header wordmark: the bare R mark and "rabta" with a lime full stop. */
    fun wordmark(context: Context, markDp: Int, textSizeSp: Float): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; contentDescription = "Rabta"
        addView(ImageView(context).apply {
            setImageResource(R.drawable.rabta_mark); scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            // Lime reads on navy; light mode uses the deep NOVA green.
            if (!AppTheme.dark(context)) setColorFilter(AppTheme.accent(context))
        }, LinearLayout.LayoutParams(dp(context, markDp), dp(context, markDp)))
        addView(TextView(context).apply {
            text = android.text.SpannableString("rabta.").apply {
                setSpan(android.text.style.ForegroundColorSpan(AppTheme.accent(context)), length - 1, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = textSizeSp; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(AppTheme.text(context))
            includeFontPadding = false; maxLines = 1; importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(context, 8) })
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
