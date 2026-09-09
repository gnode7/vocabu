package cn.vocabu.core.model

import kotlin.test.Test
import kotlin.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordTest {

    @Test
    fun `isPhrase由文本自动派生 trim后含空白即为词组`() {
        assertTrue(Word.of("look forward to", null, null, "期待", ts(), ts()).isPhrase)
        assertTrue(Word.of("  apple  pie ", null, null, "苹果派", ts(), ts()).isPhrase)
        assertFalse(Word.of("apple", null, null, "苹果", ts(), ts()).isPhrase)
        assertFalse(Word.of("  apple  ", null, null, "苹果", ts(), ts()).isPhrase)
    }

    private fun ts() = Instant.fromEpochSeconds(0)
}
