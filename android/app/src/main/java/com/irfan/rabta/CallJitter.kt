package com.irfan.rabta

/**
 * Turns call audio that comes over the internet in 40 ms frames back into one steady voice. Frames do not arrive
 * evenly: it holds a few before playing, so one that comes a little late does not cut the voice. A frame still missing
 * is filled with the last one fading out, and after such a gap it holds a little more. When frames come bunched up
 * (the connection stalled, then caught up), it skips one now and then until the delay is back to normal, instead of
 * dropping speech all at once. [next] is called for every frame played and always gives 40 ms to play. Thread-safe.
 */
class CallJitter(private val frameBytes: Int, private val minDepth: Int = 2, private val maxDepth: Int = 8, private val limit: Int = 25) {
    private val frames = ArrayDeque<ByteArray>()
    /** Frames held before playing: one more after each gap, one fewer after a steady while. */
    var depth = 3; private set
    private var waiting = true
    private var missed = 0
    private var last: ByteArray? = null
    private var steady = 0
    private var skip = false

    @Synchronized fun put(frame: ByteArray) {
        if (frames.size >= limit) frames.removeFirst()   // a second behind: the oldest goes
        frames.addLast(frame)
    }

    @Synchronized fun next(): ByteArray {
        if (waiting) {
            if (frames.size < depth) return fill()
            waiting = false
        }
        val frame = frames.removeFirstOrNull()
        if (frame == null) {
            // Late: filled in; from now on it holds a little more, and waits to have that before playing on.
            if (missed == 0) { depth = minOf(depth + 1, maxDepth); steady = 0 }
            waiting = true
            return fill()
        }
        val after = missed > 0; missed = 0
        if (frames.size > depth + 2) { if (skip) frames.removeFirst(); skip = !skip } else skip = false
        if (++steady >= STEADY && depth > minDepth) { depth--; steady = 0 }
        last = frame
        return if (after) fadeIn(frame) else frame
    }

    /** The last frame, quieter each time it is repeated, then silence. */
    private fun fill(): ByteArray {
        missed++
        val l = last ?: return ByteArray(frameBytes)
        return when (missed) { 1 -> scaled(l, 0.6f); 2 -> scaled(l, 0.3f); 3 -> scaled(l, 0.1f); else -> ByteArray(l.size) }
    }

    companion object {
        /** Frames (10 s) played without a gap before it holds one fewer. */
        const val STEADY = 250
        /** 16-bit little-endian samples times [gain]. */
        fun scaled(pcm: ByteArray, gain: Float): ByteArray {
            val out = pcm.copyOf()
            for (i in 0 until out.size - 1 step 2) sample(out, i, (sample(pcm, i) * gain).toInt())
            return out
        }
        /** Voice coming back after a gap rises over its first 10 ms (at 8 kHz) instead of clicking in. */
        fun fadeIn(pcm: ByteArray): ByteArray {
            val out = pcm.copyOf(); val ramp = minOf(80, out.size / 2)
            for (n in 0 until ramp) sample(out, n * 2, sample(pcm, n * 2) * n / ramp)
            return out
        }
        private fun sample(b: ByteArray, i: Int) = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
        private fun sample(b: ByteArray, i: Int, v: Int) { val s = v.coerceIn(-32768, 32767); b[i] = s.toByte(); b[i + 1] = (s shr 8).toByte() }
    }
}
