package cn.vocabu.core.logic

import cn.vocabu.core.model.AppSettings
import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.Word
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 播报脚本生成（ISSUE-005，PRD §2.3.3 / §4.2 / §4.3）：
 * 单词默认 = 读音 → 0.5s → 字母拼写（每字母间 0.3s）；词组默认 = 读音 → 0.5s → 中文翻译。
 * 内容按设置多选裁剪；段结构 = (文本, 语言, 段后停顿)。
 */
class SpeechScriptTest {

    private val t0: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun word(text: String = "apple") = Word.of(
        text = text, phonetic = null, pos = "n", translation = "苹果",
        createdAt = t0, updatedAt = t0, id = 1,
    )

    private fun phrase(text: String = "look forward to") = Word.of(
        text = text, phonetic = null, pos = null, translation = "期待",
        createdAt = t0, updatedAt = t0, id = 2,
    )

    // ---- 单词默认：读音 → 0.5s → 字母拼写（字母间 0.3s，末段停顿 0）----

    @Test
    fun `单词默认脚本 读音加字母拼写`() {
        val segs = SpeechScriptBuilder.build(word("apple"), AppSettings())
        assertEquals(listOf("apple", "A", "P", "P", "L", "E"), segs.map { it.text })
        assertEquals(6, segs.size)
    }

    @Test
    fun `段间停顿零点五秒 字母间零点三秒`() {
        val segs = SpeechScriptBuilder.build(word("apple"), AppSettings())
        // 段间（读音→拼写第一字母）0.5s
        assertEquals(500L, segs[0].pauseAfterMillis)
        // 字母间 0.3s
        assertEquals(listOf(300L, 300L, 300L, 300L), segs.drop(1).dropLast(1).map { it.pauseAfterMillis })
        // 末段无停顿
        assertEquals(0L, segs.last().pauseAfterMillis)
    }

    @Test
    fun `读音与拼写均为英文语言`() {
        val segs = SpeechScriptBuilder.build(word("apple"), AppSettings())
        assertTrue(segs.all { it.lang == SpeechLang.EN })
    }

    @Test
    fun `拼写逐字母大写`() {
        val segs = SpeechScriptBuilder.build(word("Apple"), AppSettings())
        assertEquals(listOf("Apple", "A", "P", "P", "L", "E"), segs.map { it.text })
    }

    // ---- 词组默认：读音 → 0.5s → 中文翻译（中文段语言 = ZH）----

    @Test
    fun `词组默认脚本 读音加中文翻译`() {
        val segs = SpeechScriptBuilder.build(phrase("look forward to"), AppSettings())
        assertEquals(2, segs.size)
        assertEquals("look forward to", segs[0].text)
        assertEquals(SpeechLang.EN, segs[0].lang)
        assertEquals(500L, segs[0].pauseAfterMillis)
        assertEquals("期待", segs[1].text)
        assertEquals(SpeechLang.ZH, segs[1].lang)
        assertEquals(0L, segs[1].pauseAfterMillis)
    }

    @Test
    fun `词组不拼读字母`() {
        val segs = SpeechScriptBuilder.build(phrase(), AppSettings())
        assertTrue(segs.none { it.text.length == 1 && it.text[0].isLetter() && it.text != "期待" })
    }

    // ---- 按设置裁剪（PRD §2.3.3 播报内容多选）----

    @Test
    fun `单词关闭拼写 只剩读音`() {
        val segs = SpeechScriptBuilder.build(word(), AppSettings(wordPlaySpelling = false))
        assertEquals(listOf("apple"), segs.map { it.text })
        assertEquals(0L, segs[0].pauseAfterMillis)
    }

    @Test
    fun `单词关闭读音 只剩拼写`() {
        val segs = SpeechScriptBuilder.build(word(), AppSettings(wordPlayPronunciation = false))
        assertEquals(listOf("A", "P", "P", "L", "E"), segs.map { it.text })
    }

    @Test
    fun `单词全关 空脚本`() {
        val segs = SpeechScriptBuilder.build(
            word(), AppSettings(wordPlayPronunciation = false, wordPlaySpelling = false),
        )
        assertTrue(segs.isEmpty())
    }

    @Test
    fun `词组关闭翻译 只剩读音`() {
        val segs = SpeechScriptBuilder.build(phrase(), AppSettings(phrasePlayTranslation = false))
        assertEquals(listOf("look forward to"), segs.map { it.text })
    }

    @Test
    fun `词组关闭读音 只剩翻译`() {
        val segs = SpeechScriptBuilder.build(phrase(), AppSettings(phrasePlayPronunciation = false))
        assertEquals(listOf("期待"), segs.map { it.text })
    }

    // ---- 回忆会话（ISSUE-006；PRD §2.4.2 / §2.4.3）----

    @Test
    fun `回忆英中单词 默认读音加拼写`() {
        val segs = SpeechScriptBuilder.buildRecall(word("apple"), Facet.EN2ZH, AppSettings())
        assertEquals(listOf("apple", "A", "P", "P", "L", "E"), segs.map { it.text })
    }

    @Test
    fun `回忆英中单词 关拼写只剩读音`() {
        val s = AppSettings(recallEn2ZhWordPlaySpelling = false)
        val segs = SpeechScriptBuilder.buildRecall(word(), Facet.EN2ZH, s)
        assertEquals(listOf("apple"), segs.map { it.text })
    }

    @Test
    fun `回忆英中词组 默认只有读音 不播中文`() {
        val segs = SpeechScriptBuilder.buildRecall(phrase(), Facet.EN2ZH, AppSettings())
        assertEquals(listOf("look forward to"), segs.map { it.text })
        assertTrue(segs.all { it.lang == SpeechLang.EN })
    }

    @Test
    fun `回忆英中词组 开翻译可播中文`() {
        val s = AppSettings(recallEn2ZhPhrasePlayTranslation = true)
        val segs = SpeechScriptBuilder.buildRecall(phrase(), Facet.EN2ZH, s)
        assertEquals(listOf("look forward to", "期待"), segs.map { it.text })
        assertEquals(SpeechLang.ZH, segs[1].lang)
    }

    @Test
    fun `回忆中英 默认不播报`() {
        val segs = SpeechScriptBuilder.buildRecall(word(), Facet.ZH2EN, AppSettings())
        assertTrue(segs.isEmpty())
    }

    @Test
    fun `回忆中英 开默认播报只播英文读音`() {
        val segs = SpeechScriptBuilder.buildRecall(word(), Facet.ZH2EN, AppSettings(recallZh2EnAutoPlay = true))
        assertEquals(listOf("apple"), segs.map { it.text })
        assertEquals(SpeechLang.EN, segs[0].lang)
        assertEquals(0L, segs[0].pauseAfterMillis)
    }
}
