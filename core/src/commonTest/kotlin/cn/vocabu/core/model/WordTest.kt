package cn.vocabu.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `词组的pos归一化为空串 ADR0007`() {
        val phrase = Word.of("look up", null, "v.", "查阅", ts(), ts())
        assertTrue(phrase.isPhrase)
        assertEquals("", phrase.pos)
    }

    @Test
    fun `单词保留pos且归一化 trim小写去尾点`() {
        val word = Word.of("apple", " ˈæpl ", " N. ", "苹果", ts(), ts())
        assertFalse(word.isPhrase)
        assertEquals("n", word.pos)
        assertEquals("ˈæpl", word.phonetic)
    }

    @Test
    fun `词性归一化单点收敛 trim小写去尾点`() {
        assertEquals("n", Word.normalizePos(" N. "))
        assertEquals("n", Word.normalizePos("n "))
        assertEquals("n", Word.normalizePos("N"))
        assertEquals("n", Word.normalizePos("n."))
        assertEquals("", Word.normalizePos(null))
        assertEquals("", Word.normalizePos(""))
    }

    private fun ts() = Instant.fromEpochSeconds(0)
}
