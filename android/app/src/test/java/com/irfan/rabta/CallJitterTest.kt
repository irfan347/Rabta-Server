package com.irfan.rabta
import org.junit.Assert.*
import org.junit.Test
class CallJitterTest {
    /** A 40 ms frame at 8 kHz whose every sample is [v]: frames are told apart by their level. */
    private fun frame(v: Int) = ByteArray(640).also { for (i in 0 until 640 step 2) { it[i] = v.toByte(); it[i + 1] = (v shr 8).toByte() } }
    private fun level(b: ByteArray, at: Int = 200) = ((b[at * 2 + 1].toInt() shl 8) or (b[at * 2].toInt() and 0xFF)).toShort().toInt()
    private fun silent(b: ByteArray) = b.all { it.toInt() == 0 }

    @Test fun holdsAFewFramesBeforePlayingThenPlaysThemInOrder() {
        val j = CallJitter(640)
        j.put(frame(1000)); j.put(frame(2000))
        assertTrue("Two frames are not yet enough to play", silent(j.next()))
        j.put(frame(3000))
        assertEquals(1000, level(j.next())); assertEquals(2000, level(j.next())); assertEquals(3000, level(j.next()))
    }

    @Test fun aLateFrameIsFilledInFadingOutAndTheBufferThenHoldsMore() {
        val j = CallJitter(640)
        repeat(3) { j.put(frame(10000)) }
        repeat(3) { assertEquals(10000, level(j.next())) }
        assertEquals(6000, level(j.next())); assertEquals(3000, level(j.next())); assertEquals(1000, level(j.next()))
        assertTrue(silent(j.next()))
        assertEquals("After a gap it holds one frame more", 4, j.depth)
        repeat(3) { j.put(frame(20000)) }
        assertTrue("It waits to hold four before playing on", silent(j.next()))
        j.put(frame(20000))
        val back = j.next()
        assertEquals(0, level(back, 0)); assertEquals("The voice rises back in", 20000, level(back))
    }

    @Test fun bunchedUpFramesAreCaughtUpGraduallyAndStayInOrder() {
        val j = CallJitter(640)
        for (k in 1..15) j.put(frame(k * 100))
        val played = (1..12).map { level(j.next()) }
        assertEquals("Fifteen frames have played in the time of twelve, the newest last", 1500, played.last())
        assertTrue("In order, and never two skipped in a row", (1 until played.size).all { played[it] - played[it - 1] in 100..200 })
    }

    @Test fun neverHoldsMoreThanASecond() {
        val j = CallJitter(640)
        for (k in 1..40) j.put(frame(k * 100))
        assertEquals("The oldest frames went", 1600, level(j.next()))
    }

    @Test fun holdsLessAfterASteadyWhile() {
        val j = CallJitter(640)
        repeat(3) { j.put(frame(5000)) }
        repeat(3) { j.next() }; j.next()                  // one gap: holds four
        repeat(4) { j.put(frame(5000)) }
        repeat(CallJitter.STEADY) { j.put(frame(5000)); assertEquals(5000, level(j.next())) }
        assertEquals(3, j.depth)
    }

    @Test fun scalingAndRisingKeepTheSamplesInRange() {
        assertEquals(-16384, level(CallJitter.scaled(frame(-32768), 0.5f)))
        assertEquals(32767, level(CallJitter.scaled(frame(32767), 1f)))
        val risen = CallJitter.fadeIn(frame(8000))
        assertEquals(0, level(risen, 0)); assertEquals(4000, level(risen, 40)); assertEquals(8000, level(risen, 80))
    }
}
