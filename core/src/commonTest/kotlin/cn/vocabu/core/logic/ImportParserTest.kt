package cn.vocabu.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportParserTest {

    /** 便捷构造一行：A=单词 B=词性 C=翻译 D=音标 */
    private fun row(a: String?, b: String? = null, c: String? = null, d: String? = null) = listOf(a, b, c, d)

    @Test
    fun `首行表头无条件跳过 v1_2格式规范`() {
        val outcomes = ImportParser.parse(
            listOf(
                row("单词", "词性", "翻译"),
                row("apple", "n.", "苹果"),
            ),
        )

        assertEquals(1, outcomes.size)
        val entry = outcomes[0] as ImportParser.RowOutcome.Entry
        assertEquals(2, entry.lineNo) // 原文件行号：表头=1，数据从 2 起
        assertEquals("apple", entry.text)
    }

    @Test
    fun `列映射 ABCD 词性音标可空`() {
        val outcomes = ImportParser.parse(
            listOf(
                row("单词", "词性", "翻译", "音标"),
                row("apple", "n.", "苹果", "ˈæpl"),
                row("look up", null, "查阅", null),
            ),
        )

        assertEquals(2, outcomes.size)
        val first = outcomes[0] as ImportParser.RowOutcome.Entry
        assertEquals("n.", first.pos)
        assertEquals("ˈæpl", first.phonetic)
        assertEquals("苹果", first.translation)

        val second = outcomes[1] as ImportParser.RowOutcome.Entry
        assertEquals("look up", second.text)
        assertEquals(null, second.pos)
        assertEquals(null, second.phonetic)
    }

    @Test
    fun `空行静默跳过`() {
        val outcomes = ImportParser.parse(
            listOf(
                row("单词", "词性", "翻译"),
                row(null, null, null),
                row("   ", "", "  "),
                row("apple", "n.", "苹果"),
            ),
        )

        assertEquals(1, outcomes.size)
    }

    @Test
    fun `A列空但行非空 记录为格式错误`() {
        val outcomes = ImportParser.parse(
            listOf(
                row("单词", "词性", "翻译"),
                row(null, "n.", "苹果"),
                row("", "n.", "梨"),
            ),
        )

        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it is ImportParser.RowOutcome.Invalid })
        assertEquals(2, outcomes[0].lineNo)
        assertEquals(3, outcomes[1].lineNo)
    }

    @Test
    fun `C列翻译为空 记录为格式错误`() {
        val outcomes = ImportParser.parse(
            listOf(
                row("单词", "词性", "翻译"),
                row("apple", "n.", "  "),
            ),
        )

        val invalid = outcomes[0] as ImportParser.RowOutcome.Invalid
        assertEquals(2, invalid.lineNo)
        assertTrue(invalid.reason.contains("翻译"))
    }

    @Test
    fun `文本trim保留原始大小写`() {
        val outcomes = ImportParser.parse(
            listOf(
                row("单词", "词性", "翻译"),
                row("  ApplePie  ", " n. ", " 苹果派 "),
            ),
        )

        val entry = outcomes[0] as ImportParser.RowOutcome.Entry
        assertEquals("ApplePie", entry.text) // trim 但不改变大小写
        assertEquals("n.", entry.pos)
        assertEquals("苹果派", entry.translation)
    }
}
