package com.irfan.rabta

/**
 * The pixel work behind video call filters and background blur, on plain I420 arrays: no Android or WebRTC types,
 * so it is tested on the JVM. [VideoEffects] moves frames in and out.
 */
enum class VideoFilter(val label: String) {
    NORMAL("Normal"), BW("Black & white"), WARM("Warm"), COOL("Cool"), BRIGHT("Bright"), VINTAGE("Vintage")
}

/** One frame: full-size luma [y], and [u]/[v] chroma at half width and height (rounded up), each row packed. */
class I420Planes(val w: Int, val h: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {
    val cw = (w + 1) / 2
    val ch = (h + 1) / 2
    init { require(y.size >= w * h && u.size >= cw * ch && v.size >= cw * ch) }
    companion object {
        fun blank(w: Int, h: Int) = I420Planes(w, h, ByteArray(w * h), ByteArray(((w + 1) / 2) * ((h + 1) / 2)), ByteArray(((w + 1) / 2) * ((h + 1) / 2)))
    }
}

object VideoLook {
    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /** Per-channel tables for [filter]: luma, U and V in, out. Colour shifts happen in chroma, light in luma. */
    fun tables(filter: VideoFilter): Array<IntArray> {
        val y = IntArray(256) { it }; val u = IntArray(256) { it }; val v = IntArray(256) { it }
        for (i in 0..255) when (filter) {
            VideoFilter.NORMAL -> {}
            VideoFilter.BW -> { u[i] = 128; v[i] = 128; y[i] = clamp(((i - 128) * 1.08 + 128).toInt()) }
            VideoFilter.WARM -> { u[i] = clamp(i - 10); v[i] = clamp(i + 14); y[i] = clamp(i + 4) }
            VideoFilter.COOL -> { u[i] = clamp(i + 14); v[i] = clamp(i - 10) }
            VideoFilter.BRIGHT -> { y[i] = clamp((i * 1.10 + 14).toInt()); u[i] = clamp(((i - 128) * 1.12 + 128).toInt()); v[i] = clamp(((i - 128) * 1.12 + 128).toInt()) }
            // Sepia: chroma drawn towards a warm brown, contrast softened.
            VideoFilter.VINTAGE -> { y[i] = clamp((20 + i * 0.86).toInt()); u[i] = clamp(((i - 128) * 0.25 + 112).toInt()); v[i] = clamp(((i - 128) * 0.25 + 146).toInt()) }
        }
        return arrayOf(y, u, v)
    }

    /** Applies [filter] to [p] in place. */
    fun colour(p: I420Planes, filter: VideoFilter) {
        if (filter == VideoFilter.NORMAL) return
        val (ty, tu, tv) = tables(filter)
        for (i in 0 until p.w * p.h) p.y[i] = ty[p.y[i].toInt() and 0xFF].toByte()
        for (i in 0 until p.cw * p.ch) { p.u[i] = tu[p.u[i].toInt() and 0xFF].toByte(); p.v[i] = tv[p.v[i].toInt() and 0xFF].toByte() }
    }

    /**
     * Where buffer pixel ([x], [y]) of a [w]×[h] frame lands once the frame is turned [rotation] degrees clockwise
     * to stand upright (WebRTC's frame rotation). Returns x shl 16 or y.
     */
    fun upright(x: Int, y: Int, w: Int, h: Int, rotation: Int): Int = when (((rotation % 360) + 360) % 360) {
        90 -> ((h - 1 - y) shl 16) or x
        180 -> ((w - 1 - x) shl 16) or (h - 1 - y)
        270 -> (y shl 16) or (w - 1 - x)
        else -> (x shl 16) or y
    }
    fun uprightSize(w: Int, h: Int, rotation: Int) = if (((rotation % 360) + 360) % 360 % 180 == 90) h to w else w to h

    /** A small upright copy of [p] (its long side [longSide] pixels), for the segmentation model and the effect previews. */
    fun smallUpright(p: I420Planes, rotation: Int, longSide: Int): I420Planes {
        val (uw, uh) = uprightSize(p.w, p.h, rotation)
        val scale = longSide.toFloat() / maxOf(uw, uh)
        val sw = maxOf(2, (uw * scale).toInt()); val sh = maxOf(2, (uh * scale).toInt())
        val out = I420Planes.blank(sw, sh)
        val r = ((rotation % 360) + 360) % 360
        for (sy in 0 until sh) for (sx in 0 until sw) {
            val ux = minOf(uw - 1, (sx / scale).toInt()); val uy = minOf(uh - 1, (sy / scale).toInt())
            // Back from upright to buffer coordinates.
            val x: Int; val y: Int
            when (r) { 90 -> { x = uy; y = p.h - 1 - ux }; 180 -> { x = p.w - 1 - ux; y = p.h - 1 - uy }; 270 -> { x = p.w - 1 - uy; y = ux }; else -> { x = ux; y = uy } }
            out.y[sy * sw + sx] = p.y[y * p.w + x]
            if (sx % 2 == 0 && sy % 2 == 0) { val c = (sy / 2) * out.cw + sx / 2; out.u[c] = p.u[(y / 2) * p.cw + x / 2]; out.v[c] = p.v[(y / 2) * p.cw + x / 2] }
        }
        return out
    }

    /** The planes as opaque ARGB pixels, row by row. */
    fun argb(p: I420Planes): IntArray = IntArray(p.w * p.h) { i ->
        val x = i % p.w; val y = i / p.w; val c = (y / 2) * p.cw + x / 2
        rgb(p.y[i].toInt() and 0xFF, p.u[c].toInt() and 0xFF, p.v[c].toInt() and 0xFF)
    }

    fun copy(p: I420Planes) = I420Planes(p.w, p.h, p.y.copyOf(), p.u.copyOf(), p.v.copyOf())

    /** BT.601 YUV to opaque ARGB. */
    fun rgb(y: Int, u: Int, v: Int): Int {
        val d = u - 128; val e = v - 128
        val r = clamp(y + ((359 * e) shr 8)); val g = clamp(y - ((88 * d + 183 * e) shr 8)); val b = clamp(y + ((454 * d) shr 8))
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Background blur: keeps where [mask] says a person is (confidence 0–1, upright [mw]×[mh]) and blends the rest
     * with a heavily softened copy of the frame. [p] is changed in place.
     */
    fun blurBackground(p: I420Planes, rotation: Int, mask: FloatArray, mw: Int, mh: Int) {
        val (uw, uh) = uprightSize(p.w, p.h, rotation)
        val fx = mw.toFloat() / uw; val fy = mh.toFloat() / uh
        // Person weight per chroma pixel (0–255), sampled from the mask with bilinear smoothing.
        val alpha = IntArray(p.cw * p.ch)
        for (cy in 0 until p.ch) for (cx in 0 until p.cw) {
            val pos = upright(minOf(p.w - 1, cx * 2), minOf(p.h - 1, cy * 2), p.w, p.h, rotation)
            alpha[cy * p.cw + cx] = (sample(mask, mw, mh, (pos shr 16) * fx, (pos and 0xFFFF) * fy) * 255f).toInt().coerceIn(0, 255)
        }
        val soft = 8
        blend(p.y, p.w, p.h, soft, alpha, p.cw, 2)
        blend(p.u, p.cw, p.ch, soft / 2, alpha, p.cw, 1)
        blend(p.v, p.cw, p.ch, soft / 2, alpha, p.cw, 1)
    }

    private fun sample(m: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, w - 1); val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = minOf(w - 1, x0 + 1); val y1 = minOf(h - 1, y0 + 1)
        val ax = (x - x0).coerceIn(0f, 1f); val ay = (y - y0).coerceIn(0f, 1f)
        val top = m[y0 * w + x0] * (1 - ax) + m[y0 * w + x1] * ax
        val bottom = m[y1 * w + x0] * (1 - ax) + m[y1 * w + x1] * ax
        return top * (1 - ay) + bottom * ay
    }

    /**
     * Blends one plane with its blurred self: box-averaged down by [factor], smoothed again, then read back with
     * bilinear interpolation. [alpha] is at chroma resolution; [step] maps plane pixels onto it.
     */
    private fun blend(plane: ByteArray, w: Int, h: Int, factor: Int, alpha: IntArray, aw: Int, step: Int) {
        val tw = (w + factor - 1) / factor; val th = (h + factor - 1) / factor
        val sum = IntArray(tw * th); val count = IntArray(tw * th)
        for (y in 0 until h) { val row = y * w; val trow = (y / factor) * tw; for (x in 0 until w) { val t = trow + x / factor; sum[t] += plane[row + x].toInt() and 0xFF; count[t]++ } }
        val tiny = IntArray(tw * th) { sum[it] / maxOf(1, count[it]) }
        // One more 3×3 pass so the enlarged background shows no blocks.
        val smooth = IntArray(tw * th)
        for (ty in 0 until th) for (tx in 0 until tw) {
            var s = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) { val yy = ty + dy; val xx = tx + dx; if (yy in 0 until th && xx in 0 until tw) { s += tiny[yy * tw + xx]; n++ } }
            smooth[ty * tw + tx] = s / n
        }
        // Fixed-point (8-bit) column positions in the tiny plane, shared by every row.
        val col0 = IntArray(w); val colA = IntArray(w)
        for (x in 0 until w) { val f = ((x + 0.5f) / factor - 0.5f).coerceIn(0f, (tw - 1).toFloat()); col0[x] = f.toInt(); colA[x] = ((f - col0[x]) * 256).toInt() }
        for (y in 0 until h) {
            val f = ((y + 0.5f) / factor - 0.5f).coerceIn(0f, (th - 1).toFloat()); val r0 = f.toInt(); val r1 = minOf(th - 1, r0 + 1); val ay = ((f - r0) * 256).toInt()
            val arow = (y / step).coerceAtMost(alpha.size / aw - 1) * aw
            for (x in 0 until w) {
                val a = alpha[arow + minOf(aw - 1, x / step)]
                if (a >= 250) continue   // the person: untouched
                val c0 = col0[x]; val c1 = minOf(tw - 1, c0 + 1); val ax = colA[x]
                val top = smooth[r0 * tw + c0] * (256 - ax) + smooth[r0 * tw + c1] * ax
                val bottom = smooth[r1 * tw + c0] * (256 - ax) + smooth[r1 * tw + c1] * ax
                val blurred = (top * (256 - ay) + bottom * ay) shr 16
                val i = y * w + x
                plane[i] = ((((plane[i].toInt() and 0xFF) * a) + blurred * (255 - a)) / 255).toByte()
            }
        }
    }
}
