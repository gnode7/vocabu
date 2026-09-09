package cn.vocabu.core.logic

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Word
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class WorkingSetTest {

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

    @Test
    fun `新词参与所有模式的工作集`() {
        val fresh = word(1)
        val set = WorkingSet.filter(listOf(fresh), emptyMap(), setOf(Facet.EN2ZH), now)
        assertEquals(listOf(1L), set.map { it.id })
    }

    @Test
    fun `已建账且未到期的词被过滤`() {
        val w = word(2)
        val records = mapOf(2L to listOf(rec(2, Facet.EN2ZH, due = false)))
        val set = WorkingSet.filter(listOf(w), records, setOf(Facet.EN2ZH), now)
        assertEquals(0, set.size)
    }

    @Test
    fun `目标面到期则入选`() {
        val w = word(3)
        val records = mapOf(3L to listOf(rec(3, Facet.EN2ZH, due = true)))
        val set = WorkingSet.filter(listOf(w), records, setOf(Facet.EN2ZH), now)
        assertEquals(listOf(3L), set.map { it.id })
    }

    @Test
    fun `缺目标面则入选`() {
        val w = word(4)
        // 只有英→中账本，缺听拼面 → 听写工作集包含
        val records = mapOf(4L to listOf(rec(4, Facet.EN2ZH, due = false)))
        val set = WorkingSet.filter(listOf(w), records, setOf(Facet.AUDIO_SPELLING), now)
        assertEquals(listOf(4L), set.map { it.id })
    }

    @Test
    fun `不在今日词表的词即使到期也不入选`() {
        val w = word(5)
        val records = mapOf(5L to listOf(rec(5, Facet.EN2ZH, due = true)))
        // 传入的今日词表为空
        val set = WorkingSet.filter(emptyList(), records, setOf(Facet.EN2ZH), now)
        assertEquals(0, set.size)
    }

    // ---- 混合回忆方向分配（PRD §2.4.4）----

    @Test
    fun `单面可评时方向固定`() {
        // ZH2EN 未到期（不可评），EN2ZH 到期（可评）→ 固定 EN2ZH
        val enDue = listOf(rec(1, Facet.EN2ZH, due = true), rec(1, Facet.ZH2EN, due = false))
        assertEquals(Facet.EN2ZH, DirectionAssigner.assign(enDue, Random(42), now))

        // EN2ZH 未到期（不可评），ZH2EN 到期（可评）→ 固定 ZH2EN
        val zhDue = listOf(rec(1, Facet.EN2ZH, due = false), rec(1, Facet.ZH2EN, due = true))
        assertEquals(Facet.ZH2EN, DirectionAssigner.assign(zhDue, Random(42), now))
    }

    @Test
    fun `双面可评时种子固定下两个方向都会出现`() {
        val recs = listOf(rec(1, Facet.EN2ZH, due = true), rec(1, Facet.ZH2EN, due = true))
        val outcomes = (0 until 100).map { DirectionAssigner.assign(recs, Random(it.toLong()), now) }

        assertEquals(100, outcomes.size)
        assertTrue(Facet.EN2ZH in outcomes, "100 个种子下 EN2ZH 应出现")
        assertTrue(Facet.ZH2EN in outcomes, "100 个种子下 ZH2EN 应出现")
        val enCount = outcomes.count { it == Facet.EN2ZH }
        assertTrue(enCount in 30..70, "分布应大致均衡，实测 EN2ZH=$enCount/100")
    }

    @Test
    fun `双面均不可评返回null`() {
        val recs = listOf(rec(1, Facet.EN2ZH, due = false), rec(1, Facet.ZH2EN, due = false))
        assertNull(DirectionAssigner.assign(recs, Random(42), now))
    }
}
