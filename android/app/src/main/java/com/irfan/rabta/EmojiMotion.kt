package com.irfan.rabta

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

object EmojiMotion {
    private val emoji by lazy { EmojiData.categories.flatMap { it.entries }.map { it.first }.sortedByDescending { it.length } }
    /** The emoji [text] is made of, as written (skin tones kept); empty unless it is 1 to 3 emoji and nothing else. */
    fun split(text: String): List<String> {
        if(text.length > 100) return emptyList()
        // The bundled catalog stores base emoji; skin tones modify an existing pictograph. [from] maps each kept character back to the text.
        val normalized=StringBuilder();val from=ArrayList<Int>();var previous=0;var i=0
        while(i < text.length) {
            val cp=text.codePointAt(i)
            if(cp in 0x1F3FB..0x1F3FF) {
                if(previous !in 0x1F000..0x1FAFF && previous !in 0x2600..0x27FF) return emptyList()
            } else { normalized.appendCodePoint(cp); repeat(Character.charCount(cp)) { from += i + it } }
            previous=cp; i+=Character.charCount(cp)
        }
        from += text.length
        val parts=ArrayList<String>();var at=0
        fun spaces() { while(at < normalized.length && normalized[at].isWhitespace()) at++ }
        spaces()
        while(at < normalized.length) {
            val match = emoji.firstOrNull { normalized.startsWith(it, at) } ?: return emptyList()
            parts += text.substring(from[at], from[at + match.length])
            at += match.length; spaces()
            if(parts.size > 3) return emptyList()
        }
        return parts
    }
    /** Match real emoji sequences, including flags, keycaps, skin tones and families. */
    fun count(text: String): Int = split(text).size
    fun play(v: View) {
        if(!Experience.animate(v.context) || !ValueAnimator.areAnimatorsEnabled()) return
        v.animate().cancel(); v.scaleX = 0.75f; v.scaleY = 0.75f
        v.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(DecelerateInterpolator()).start()
    }

    /** Large emoji whose copies are floating up now; a burst of new messages sets off only a few at a time. */
    internal val floating = mutableMapOf<View, Animator>()
    /** A large emoji just sent or just arrived: it pops in and small copies of it float up and fade. Nothing moves when animated emoji or Android's animations are off. */
    fun celebrate(v: TextView) {
        if(!Experience.animate(v.context) || !ValueAnimator.areAnimatorsEnabled()) return
        v.animate().cancel(); v.scaleX = 0.3f; v.scaleY = 0.3f; v.rotation = -14f
        v.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(560).setInterpolator(OvershootInterpolator(2.4f)).start()
        val parts = split(v.text.toString())
        if(parts.isNotEmpty()) v.postDelayed({ rise(v, parts) }, 120)
    }
    private fun rise(v: TextView, parts: List<String>, tries: Int = 0) {
        if(!v.isAttachedToWindow) return
        // Only from an emoji in view: the chat gets a moment to scroll to it; one that arrives below the part being read stays quiet.
        if(!v.getGlobalVisibleRect(Rect())) { if(tries < 8) v.postDelayed({ rise(v, parts, tries + 1) }, 60); return }
        floating.remove(v)?.cancel()
        if(floating.size >= 3) return
        val root = v.rootView; val copies = Copies(v, parts)
        copies.setBounds(0, 0, root.width, root.height); root.overlay.add(copies)
        val run = ValueAnimator.ofFloat(0f, copies.length).apply {
            duration = copies.length.toLong(); interpolator = LinearInterpolator()
            addUpdateListener { copies.time = it.animatedValue as Float; copies.invalidateSelf() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { root.overlay.remove(copies); if(floating[v] === a) floating.remove(v) }
            })
        }
        floating[v] = run; run.start()
    }

    /** Small copies of [v]'s emoji rising from it, on the window's overlay so the bubble does not cut them off, and kept inside the chat. */
    private class Copies(private val v: TextView, parts: List<String>) : Drawable() {
        private class Copy(val emoji: String, val delay: Float, val life: Float, val x: Float, val y: Float, val drift: Float, val rise: Float, val size: Float, val spin: Float, val sway: Float)
        private val paint = TextPaint(v.paint).apply { textAlign = Paint.Align.CENTER }
        private val middle = -(paint.ascent() + paint.descent()) / 2f
        private val chat = generateSequence(v.parent) { it.parent }.firstOrNull { it is ScrollView } as View?
        // Sizes and distances follow the emoji's own size, so they look the same on every screen.
        private val copies = v.textSize.let { s ->
            fun r() = Random.nextFloat()
            List(7) { i -> Copy(parts[i % parts.size], i * 45f + r() * 30f, 950f + r() * 250f, (r() - 0.5f) * 0.6f * s, (r() - 0.3f) * 0.3f * s,
                (r() - 0.5f) * 2.4f * s, (1.8f + r() * 1.2f) * s, 0.42f + r() * 0.2f, (r() - 0.5f) * 80f, (r() - 0.5f) * 0.25f * s) }
        }
        val length = copies.maxOf { it.delay + it.life }
        var time = 0f
        private val at = IntArray(2); private val base = IntArray(2)
        override fun draw(canvas: Canvas) {
            if(!v.isAttachedToWindow) return
            val parent = v.parent as? View ?: return
            // Followed every frame, so the copies stay with their emoji while the chat scrolls.
            v.rootView.getLocationInWindow(base); parent.getLocationInWindow(at)
            val x = at[0] - base[0] + v.left + v.width / 2f; val y = at[1] - base[1] + v.top + v.height / 2f
            canvas.save()
            chat?.let { it.getLocationInWindow(at); canvas.clipRect(at[0] - base[0], at[1] - base[1], at[0] - base[0] + it.width, at[1] - base[1] + it.height) }
            for(c in copies) {
                val t = (time - c.delay) / c.life
                if(t <= 0f || t >= 1f) continue
                val eased = 1f - (1f - t) * (1f - t)
                val scale = c.size * if(t < 0.2f) 0.5f + 2.5f * t else 1f - 0.3f * (t - 0.2f) / 0.8f
                paint.alpha = (255 * when { t < 0.12f -> t / 0.12f; t > 0.55f -> (1f - t) / 0.45f; else -> 1f }).toInt()
                canvas.save()
                canvas.translate(x + c.x + c.drift * eased + c.sway * sin(t * 2f * PI.toFloat()), y + c.y - c.rise * eased)
                canvas.rotate(c.spin * t); canvas.scale(scale, scale)
                canvas.drawText(c.emoji, 0f, middle, paint)
                canvas.restore()
            }
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
