package com.irfan.rabta
import org.junit.Assert.*
import org.junit.Test
class EmojiMotionTest {
    @Test fun countsSequencesRatherThanUtf16Characters() {
        assertEquals(1,EmojiMotion.count("👨‍👩‍👧‍👦"))
        assertEquals(1,EmojiMotion.count("👍🏽"))
        assertEquals(1,EmojiMotion.count("🇵🇰"))
        assertEquals(3,EmojiMotion.count("❤️ 😂 😮"))
    }
    @Test fun textDigitsAndLongRunsStayNormalSize() {
        for(s in listOf("", "   ", "123", "Hello ❤️", "😀😀😀😀", "\u200d")) assertEquals(s,0,EmojiMotion.count(s))
    }
}
