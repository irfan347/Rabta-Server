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

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
