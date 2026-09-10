package cn.vocabu.core.logic

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Word
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class EntryCounterTest {

    private val now: Instant = Instant.fromEpochSeconds(1_000_000)

    private fun word(id: Long) = Word.of(
        "w$id", null, null, "词$id",
        Instant.fromEpochSeconds(id), Instant.fromEpochSeconds(id), id,
    )

    private fun rec(wordId: Long, facet: Facet, due: Boolean) = LearningRecord(
        id = 0, wordId = wordId, facet = facet,
        repetitionCount = 1, intervalSeconds = 300,
        nextReviewTime = if (due) now - 1.seconds else now + 3600.seconds,
        lastReviewTime = now - 100.seconds,
    )

    /**
     * w1：英中到期、中英未到期 → 英中可评
     * w2：仅听拼到期（英中/中英缺面 → 回忆两方向均缺面可评）
     * w3：新词（三面全缺，处处可评）
     * w4：英中/中英未到期、听拼缺面 → 仅考察可评（缺面经补查进入今日词表）
     */
    private fun fixture(): Pair<List<Word>, Map<Long, List<LearningRecord>>> {
        val words = listOf(word(1), word(2), word(3), word(4))
        val records = mapOf(
            1L to listOf(rec(1, Facet.EN2ZH, due = true), rec(1, Facet.ZH2EN, due = false)),
            2L to listOf(rec(2, Facet.AUDIO_SPELLING, due = true)),
            4L to listOf(rec(4, Facet.EN2ZH, due = false), rec(4, Facet.ZH2EN, due = false)),
        )
        return words to records
    }

    @Test
    fun `回忆计数按方向取对应考核面工作集`() {
        val (words, records) = fixture()

        assertEquals(3, EntryCounter.recallCount(words, records, "en2zh", now)) // w1到期 + w2缺面 + w3新词
        assertEquals(2, EntryCounter.recallCount(words, records, "zh2en", now)) // w2缺面 + w3新词
        assertEquals(3, EntryCounter.recallCount(words, records, "mixed", now)) // 两面任一：w1/w2/w3
    }

    @Test
    fun `考察计数按方式取考核面并集`() {
        val (words, records) = fixture()

        assertEquals(4, EntryCounter.testCount(words, records, "dictation", now)) // 听拼∪英中：全部
        assertEquals(2, EntryCounter.testCount(words, records, "writing", now))   // 中英：w2 + w3
        assertEquals(4, EntryCounter.testCount(words, records, "mixed", now))     // 三面并集：全部
    }

    @Test
    fun `方向设置变化联动计数`() {
        val (words, records) = fixture()
        // 同一批数据，方向从 en2zh 切到 zh2en：w1 的英中到期不再计入中英工作集
        assertEquals(3, EntryCounter.recallCount(words, records, "en2zh", now))
        assertEquals(2, EntryCounter.recallCount(words, records, "zh2en", now))
    }

    @Test
    fun `未知取值回退混合口径`() {
        val (words, records) = fixture()
        assertEquals(
            EntryCounter.recallCount(words, records, "mixed", now),
            EntryCounter.recallCount(words, records, "unknown", now),
        )
        assertEquals(
            EntryCounter.testCount(words, records, "mixed", now),
            EntryCounter.testCount(words, records, "unknown", now),
        )
    }
}
