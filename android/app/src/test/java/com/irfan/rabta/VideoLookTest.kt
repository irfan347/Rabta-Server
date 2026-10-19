package com.irfan.rabta

import org.junit.Assert.*
import org.junit.Test

/** Video call filters and background blur on synthetic frames; no camera, model or Android needed. */
class VideoLookTest {
    private fun frame(w: Int, h: Int, y: (Int, Int) -> Int, u: Int = 128, v: Int = 128) = I420Planes.blank(w, h).apply {
        for (yy in 0 until h) for (xx in 0 until w) this.y[yy * w + xx] = y(xx, yy).toByte()
        this.u.fill(u.toByte()); this.v.fill(v.toByte())
    }
    private fun ByteArray.at(i: Int) = this[i].toInt() and 0xFF

    @Test fun normalChangesNothing() {
        val t = VideoLook.tables(VideoFilter.NORMAL)
        for (i in 0..255) { assertEquals(i, t[0][i]); assertEquals(i, t[1][i]); assertEquals(i, t[2][i]) }
        val p = frame(4, 4, { x, _ -> x * 60 }, 90, 170); val before = VideoLook.copy(p)
        VideoLook.colour(p, VideoFilter.NORMAL)
        assertArrayEquals(before.y, p.y); assertArrayEquals(before.u, p.u); assertArrayEquals(before.v, p.v)
    }

    @Test fun blackAndWhiteRemovesColour() {
        val p = frame(4, 4, { _, _ -> 100 }, 40, 220)
        VideoLook.colour(p, VideoFilter.BW)
        assertTrue(p.u.all { (it.toInt() and 0xFF) == 128 }); assertTrue(p.v.all { (it.toInt() and 0xFF) == 128 })
    }

    @Test fun warmAndCoolPushChromaOppositeWays() {
        val warm = frame(2, 2, { _, _ -> 128 }); VideoLook.colour(warm, VideoFilter.WARM)
        val cool = frame(2, 2, { _, _ -> 128 }); VideoLook.colour(cool, VideoFilter.COOL)
        assertTrue("warm adds red", warm.v.at(0) > 128); assertTrue("warm takes blue", warm.u.at(0) < 128)
        assertTrue("cool adds blue", cool.u.at(0) > 128); assertTrue("cool takes red", cool.v.at(0) < 128)
    }

    @Test fun everyTableStaysInRange() {
        VideoFilter.values().forEach { f -> VideoLook.tables(f).forEach { t -> assertTrue(f.name, t.all { it in 0..255 }) } }
    }

    @Test fun rotationMatchesWebRtcClockwiseTurn() {
        // A 4×2 frame turned 90° clockwise stands 2×4: its top-left pixel moves to the top-right.
        assertEquals((1 shl 16) or 0, VideoLook.upright(0, 0, 4, 2, 90))
        assertEquals((0 shl 16) or 3, VideoLook.upright(0, 0, 4, 2, 270))
        assertEquals((3 shl 16) or 1, VideoLook.upright(0, 0, 4, 2, 180))
        assertEquals(0, VideoLook.upright(0, 0, 4, 2, 0))
        assertEquals(2 to 4, VideoLook.uprightSize(4, 2, 90)); assertEquals(4 to 2, VideoLook.uprightSize(4, 2, 180))
    }

    @Test fun smallUprightCopyTurnsThePicture() {
        // One bright pixel at the buffer's top-left; upright at 90° it is at the top-right of a 2×4 picture.
        val p = frame(4, 2, { x, y -> if (x == 0 && y == 0) 250 else 10 })
        val up = VideoLook.smallUpright(p, 90, 4)
        assertEquals(2, up.w); assertEquals(4, up.h)
        assertEquals(250, up.y.at(0 * up.w + 1)); assertEquals(10, up.y.at(0))
    }

    @Test fun rgbOfGreyIsGrey() {
        assertEquals(0xFFFFFFFF.toInt(), VideoLook.rgb(255, 128, 128))
        assertEquals(0xFF000000.toInt(), VideoLook.rgb(0, 128, 128))
        assertEquals(0xFF808080.toInt(), VideoLook.rgb(128, 128, 128))
    }

    @Test fun blurKeepsThePersonAndSoftensTheRest() {
        // A sharp checkerboard; the model says the left half is the person.
        val w = 32; val h = 16
        val p = frame(w, h, { x, y -> if ((x + y) % 2 == 0) 220 else 30 })
        val before = VideoLook.copy(p)
        val mask = FloatArray(w * h) { if (it % w < w / 2) 1f else 0f }
        VideoLook.blurBackground(p, 0, mask, w, h)
        for (y in 0 until h) for (x in 0 until w / 2 - 2) assertEquals("person untouched at $x,$y", before.y.at(y * w + x), p.y.at(y * w + x))
        val right = (0 until h).flatMap { y -> (w / 2 + 2 until w).map { x -> p.y.at(y * w + x) } }
        val contrast = right.max() - right.min()
        assertTrue("background softened (contrast $contrast)", contrast < 60)
    }

    @Test fun blurWithEveryoneThePersonChangesNothing() {
        val p = frame(16, 8, { x, y -> (x * 13 + y * 7) % 256 })
        val before = VideoLook.copy(p)
        VideoLook.blurBackground(p, 270, FloatArray(8 * 16) { 1f }, 8, 16)
        assertArrayEquals(before.y, p.y); assertArrayEquals(before.u, p.u); assertArrayEquals(before.v, p.v)
    }
}
