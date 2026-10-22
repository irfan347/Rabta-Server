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
    @Test fun splitsIntoTheEmojiAsWritten() {
        // The copies that float up when one is sent are these, skin tones included.
        assertEquals(listOf("😂","❤️","👍"),EmojiMotion.split("😂❤️👍"))
        assertEquals(listOf("👍🏽","😂"),EmojiMotion.split(" 👍🏽 😂 "))
        assertEquals(listOf("👨‍👩‍👧‍👦"),EmojiMotion.split("👨‍👩‍👧‍👦"))
        assertEquals(listOf("🇵🇰"),EmojiMotion.split("🇵🇰"))
        for(s in listOf("Hi 😂 there", "😀😀😀😀", "🏽")) assertEquals(s,emptyList<String>(),EmojiMotion.split(s))
    }
}
