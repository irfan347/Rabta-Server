package com.irfan.rabta

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator

object EmojiMotion {
    private val emoji by lazy { EmojiData.categories.flatMap { it.entries }.map { it.first }.sortedByDescending { it.length } }
    /** Match real emoji sequences, including flags, keycaps, skin tones and families. */
    fun count(text: String): Int {
        if(text.length > 100) return 0
        // The bundled catalog stores base emoji; skin tones modify an existing pictograph.
        val normalized=StringBuilder();var previous=0
        for(cp in text.codePoints().toArray()) {
            if(cp in 0x1F3FB..0x1F3FF) {
                if(previous !in 0x1F000..0x1FAFF && previous !in 0x2600..0x27FF)return 0
            } else normalized.appendCodePoint(cp)
            previous=cp
        }
        var rest = normalized.toString().trim(); var count = 0
        if(rest.isEmpty()) return 0
        while(rest.isNotEmpty()) {
            val match = emoji.firstOrNull { rest.startsWith(it) } ?: return 0
            rest = rest.substring(match.length).trimStart(); count++
            if(count > 3) return 0
        }
        return count
    }
    fun play(v: View) {
        if(!Experience.animate(v.context) || !ValueAnimator.areAnimatorsEnabled()) return
        v.animate().cancel(); v.scaleX = 0.88f; v.scaleY = 0.88f
        v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }
}
