package com.irfan.rabta
import org.junit.Assert.*
import org.junit.Test
class CallRefreshLoopTest {
    @Test fun resumeRestartsVisibleBarAndNeverDuplicatesTicks() {
        val queue=mutableListOf<Runnable>();var frames=0;var visible=true
        val loop=CallRefreshLoop({queue.add(it)},{queue.remove(it)},{frames++;visible})
        loop.start();assertEquals(1,queue.size)
        repeat(3) {queue.removeAt(0).run();assertEquals(1,queue.size)}
        loop.stop();assertEquals(0,queue.size)
        loop.start();assertEquals(1,queue.size) // Same visible bar, new onResume.
        repeat(5) {loop.refresh();assertEquals(1,queue.size)}
        visible=false;queue.removeAt(0).run();assertTrue(queue.isEmpty())
        visible=true;loop.refresh();assertEquals(1,queue.size)
        loop.stop();loop.refresh();assertTrue(queue.isEmpty());assertTrue(frames>5)
    }
}
