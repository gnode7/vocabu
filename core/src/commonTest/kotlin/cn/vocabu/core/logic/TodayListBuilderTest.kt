package cn.vocabu.core.logic

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Word
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TodayListBuilderTest {

    private val now: Instant = Instant.fromEpochSeconds(1_000_000)

    private fun word(id: Long) = Word.of(
        text = "w$id",
        phonetic = null,
        pos = null,
        translation = "词$id",
        createdAt = Instant.fromEpochSeconds(id),
        updatedAt = Instant.fromEpochSeconds(id),
        id = id,
    )

    /** 指定考核面上已到期/未到期的记录 */
    private fun rec(wordId: Long, facet: Facet, due: Boolean, lastReviewedAgo: Long = 100): LearningRecord {
        val last = now - lastReviewedAgo.seconds
        return LearningRecord(
            id = 0,
            wordId = wordId,
            facet = facet,
            repetitionCount = 1,
            intervalSeconds = 300,
            nextReviewTime = if (due) now - 1.seconds else now + 3600.seconds,
            lastReviewTime = last,
        )
    }

    // ---- 新词 ----

    @Test
    fun `新词按添加顺序取上限`() {
        val words = (1L..5L).map { word(it) }
        val list = TodayListBuilder.build(words, emptyMap(), dailyNewWordCount = 3, facetCatchUpQuota = 5, now = now)

        assertEquals(listOf(1L, 2L, 3L), list.all.map { it.id })
        assertEquals(3, list.newWords.size)
    }

    @Test
    fun `剩余新词不足时全部纳入`() {
        val words = (1L..3L).map { word(it) }
        val list = TodayListBuilder.build(words, emptyMap(), dailyNewWordCount = 500, facetCatchUpQuota = 5, now = now)

        assertEquals(3, list.newWords.size)
    }

    @Test
    fun `有任何记录的词不再是新词`() {
        val words = listOf(word(1), word(2))
        val records = mapOf(1L to listOf(rec(1, Facet.EN2ZH, due = false)))
        val list = TodayListBuilder.build(words, records, dailyNewWordCount = 10, facetCatchUpQuota = 0, now = now)

        assertEquals(listOf(2L), list.newWords.map { it.id })
    }

    // ---- 复习词 ----

    @Test
    fun `任一面到期即复习词 未到期不入列`() {
        val words = listOf(word(1), word(2))
        val records = mapOf(
            1L to listOf(rec(1, Facet.EN2ZH, due = true), rec(1, Facet.ZH2EN, due = false)),
            2L to listOf(rec(2, Facet.EN2ZH, due = false)),
        )
        val list = TodayListBuilder.build(words, records, dailyNewWordCount = 10, facetCatchUpQuota = 0, now = now)

        assertEquals(listOf(1L), list.reviewWords.map { it.id })
    }

    @Test
    fun `到期且缺面的词归入复习而非补齐`() {
        val words = listOf(word(1))
        val records = mapOf(1L to listOf(rec(1, Facet.EN2ZH, due = true)))
        val list = TodayListBuilder.build(words, records, dailyNewWordCount = 10, facetCatchUpQuota = 5, now = now)

        assertEquals(0, list.catchUpWords.size)
        assertEquals(listOf(1L), list.reviewWords.map { it.id })
    }

    // ---- 缺面补齐 ----

    @Test
    fun `缺面补齐按最近评级时间最旧优先且受配额限制`() {
        val words = listOf(word(1), word(2), word(3))
        val records = mapOf(
            // word1 最近评级在 200s 前（最旧）
            1L to listOf(rec(1, Facet.EN2ZH, due = false, lastReviewedAgo = 200)),
            // word2 最近评级在 50s 前（最新）
            2L to listOf(rec(2, Facet.ZH2EN, due = false, lastReviewedAgo = 50)),
            // word3 最近评级在 100s 前
            3L to listOf(rec(3, Facet.AUDIO_SPELLING, due = false, lastReviewedAgo = 100)),
        )
        val list = TodayListBuilder.build(words, records, dailyNewWordCount = 0, facetCatchUpQuota = 2, now = now)

        assertEquals(listOf(1L, 3L), list.catchUpWords.map { it.id })
    }

    @Test
    fun `缺面补齐配额为0时关闭`() {
        val words = listOf(word(1))
        val records = mapOf(1L to listOf(rec(1, Facet.EN2ZH, due = false)))
        val list = TodayListBuilder.build(words, records, dailyNewWordCount = 10, facetCatchUpQuota = 0, now = now)

        assertEquals(0, list.catchUpWords.size)
    }

    @Test
    fun `三面齐全且未到期的词不出现`() {
        val words = listOf(word(1))
        val records = mapOf(
            1L to listOf(
                rec(1, Facet.EN2ZH, due = false),
                rec(1, Facet.ZH2EN, due = false),
                rec(1, Facet.AUDIO_SPELLING, due = false),
            ),
        )
        val list = TodayListBuilder.build(words, records, dailyNewWordCount = 10, facetCatchUpQuota = 5, now = now)

        assertEquals(0, list.all.size)
    }

    // ---- 去重与空集 ----

    @Test
    fun `全部为空时今日词表为空`() {
        val list = TodayListBuilder.build(emptyList(), emptyMap(), dailyNewWordCount = 10, facetCatchUpQuota = 5, now = now)
        assertEquals(0, list.all.size)
    }

    @Test
    fun `三类合并去重`() {
        val words = listOf(word(1), word(2), word(3))
        val records = mapOf(
            1L to listOf(rec(1, Facet.EN2ZH, due = true)),   // 复习
            2L to listOf(rec(2, Facet.ZH2EN, due = false)),  // 补齐
        )
        val list = TodayListBuilder.build(words, records, dailyNewWordCount = 5, facetCatchUpQuota = 5, now = now)

        // 新词 word3 + 复习 word1 + 补齐 word2，共 3 条且无重复
        assertEquals(setOf(1L, 2L, 3L), list.all.map { it.id }.toSet())
        assertEquals(3, list.all.size)
    }
}
