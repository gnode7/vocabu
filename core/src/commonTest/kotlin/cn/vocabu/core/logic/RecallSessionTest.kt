package cn.vocabu.core.logic

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Rating
import cn.vocabu.core.model.Word
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.random.Random

/**
 * 回忆会话状态机（ISSUE-006；PRD §2.4、ISSUE-001 工作集/混合分配复用）：
 * 工作集过滤与方向分配、批窗口推进、评级（会话内最后生效）、轮末循环与结束、
 * 首评自动下移、落账口径。
 */
class RecallSessionTest {

    private val t0: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun word(id: Long, text: String = "word$id") = Word.of(
        text = text, phonetic = null, pos = "n", translation = "释义$id",
        createdAt = t0, updatedAt = t0, id = id,
    )

    /** 已建账记录：默认未到期（下一次复习 = t0 + 1h）。 */
    private fun record(
        wordId: Long,
        facet: Facet,
        nextReview: Instant = t0 + 3600.seconds,
    ) = LearningRecord(
        wordId = wordId, facet = facet, nextReviewTime = nextReview,
        lastReviewTime = t0 - 3600.seconds,
    )

    private fun recordsByWord(vararg records: LearningRecord): Map<Long, List<LearningRecord>> =
        records.groupBy { it.wordId }

    // ---- 会话构建：工作集过滤（PRD §2.4.1）----

    @Test
    fun `构建_英中方向_未到期已建账面被过滤_缺面与新词参与`() {
        val words = listOf(word(1), word(2), word(3), word(4))
        val records = recordsByWord(
            record(1, Facet.EN2ZH, nextReview = t0), // 到期 → 参与
            record(2, Facet.EN2ZH, nextReview = t0 + 60.seconds), // 未到期 → 排除
            record(3, Facet.ZH2EN, nextReview = t0), // 仅中英面有账，英中面缺 → 参与
            // 4 = 新词（三面全缺）→ 参与
        )
        val s = RecallSessionBuilder.build(words, records, "en2zh", displayCount = 10, random = Random(1), now = t0)
        assertEquals(listOf(1L, 3L, 4L), s.queue.map { it.word.id }.sorted())
        assertTrue(s.queue.all { it.facet == Facet.EN2ZH })
        assertFalse(s.finished)
    }

    @Test
    fun `构建_混合方向_单面可评固定_双面均不可评排除`() {
        val words = listOf(word(1), word(2))
        val records = recordsByWord(
            // 1：英中到期、中英未到期 → 混合下固定分配英中
            record(1, Facet.EN2ZH, nextReview = t0),
            record(1, Facet.ZH2EN, nextReview = t0 + 60.seconds),
            // 2：两面均未到期 → 排除
            record(2, Facet.EN2ZH, nextReview = t0 + 60.seconds),
            record(2, Facet.ZH2EN, nextReview = t0 + 60.seconds),
        )
        val s = RecallSessionBuilder.build(words, records, "mixed", displayCount = 10, random = Random(1), now = t0)
        assertEquals(listOf(1L), s.queue.map { it.word.id })
        assertEquals(Facet.EN2ZH, s.queue.single().facet)
    }

    @Test
    fun `构建_混合方向_双可评随机分配有种子可复现`() {
        val words = (1L..40L).map { word(it) }
        val s = RecallSessionBuilder.build(words, emptyMap(), "mixed", displayCount = 10, random = Random(7), now = t0)
        val facets = s.queue.map { it.facet }.toSet()
        assertEquals(setOf(Facet.EN2ZH, Facet.ZH2EN), facets)
        assertTrue(s.queue.none { it.facet == Facet.AUDIO_SPELLING })
        assertEquals(40, s.queue.size)
    }

    @Test
    fun `构建_新词进入会话_标记无账_旧词有账`() {
        val words = listOf(word(1), word(2))
        val records = recordsByWord(record(2, Facet.EN2ZH, nextReview = t0))
        val s = RecallSessionBuilder.build(words, records, "en2zh", displayCount = 10, random = Random(1), now = t0)
        assertEquals(false, s.queue.first { it.word.id == 1L }.hadRecord)
        assertEquals(true, s.queue.first { it.word.id == 2L }.hadRecord)
    }

    @Test
    fun `构建_批窗口_同时展示条数截断`() {
        val words = (1L..5L).map { word(it) }
        val s = RecallSessionBuilder.build(words, emptyMap(), "en2zh", displayCount = 2, random = Random(1), now = t0)
        assertEquals(0, s.batchStart)
        assertEquals(2, s.batchSize)
        assertEquals(2, s.batch.size)
        assertEquals(5, s.queue.size)
    }

    // ---- 评级：会话内最后生效、改评覆盖（PRD §2.4.2）----

    private fun sm2Record(item: RecallItem, rating: Rating, now: Instant): LearningRecord =
        Sm2.update(item.word.id, item.facet, null, rating.quality, now)

    @Test
    fun `评级_首评写入_改评覆盖以最后一次为准`() {
        val words = (1L..2L).map { word(it) }
        var s = RecallSessionBuilder.build(words, emptyMap(), "en2zh", displayCount = 10, Random(1), t0)
        val i0 = s.batch.indexOfFirst { it.word.id == 1L }
        s = RecallSessionOps.rate(s, i0, Rating.GOOD, t0, ::sm2Record, Random(1))
        assertEquals(Rating.GOOD, s.queue[s.batchStart + i0].rating)
        assertEquals(1, s.queue[s.batchStart + i0].recordAfterRating!!.repetitionCount) // Good 首评 rep=1
        s = RecallSessionOps.rate(s, i0, Rating.FORGET, t0, ::sm2Record, Random(1))
        assertEquals(Rating.FORGET, s.queue[s.batchStart + i0].rating)
        assertEquals(0, s.queue[s.batchStart + i0].recordAfterRating!!.repetitionCount) // Forget 归零（最后一次生效）
        assertEquals(60L, s.queue[s.batchStart + i0].recordAfterRating!!.intervalSeconds)
        assertEquals(2, s.totalRatingEvents) // 改评计入评级事件
        assertFalse(s.finished) // 另一条未评，会话仍在进行
    }

    @Test
    fun `评级_首次评级自动下移至下一条未评_改评不移动`() {
        val words = (1L..3L).map { word(it) }
        var s = RecallSessionBuilder.build(words, emptyMap(), "en2zh", displayCount = 3, Random(1), t0)
        s = RecallSessionOps.rate(s, 0, Rating.GOOD, t0, ::sm2Record, Random(1))
        assertEquals(1, s.selection) // 首评 → 下移
        s = RecallSessionOps.rate(s, 2, Rating.GOOD, t0, ::sm2Record, Random(1))
        assertEquals(1, s.selection) // 后面无未评 → 绕回到首个未评（原型同款）
        s = RecallSessionOps.rate(s, 2, Rating.EASY, t0, ::sm2Record, Random(1))
        assertEquals(1, s.selection) // 改评不移动
        assertEquals(Rating.EASY, s.queue[2].rating)
    }

    // ---- 批窗口推进与轮末循环（PRD §2.4.2 循环与结束）----

    @Test
    fun `批推进_当前批全评后滑入下一批_耗尽且无再现则结束`() {
        val words = (1L..5L).map { word(it) }
        var s = RecallSessionBuilder.build(words, emptyMap(), "en2zh", displayCount = 2, Random(1), t0)
        s = RecallSessionOps.rate(s, 0, Rating.GOOD, t0, ::sm2Record, Random(1))
        s = RecallSessionOps.rate(s, 1, Rating.GOOD, t0, ::sm2Record, Random(1))
        assertEquals(2, s.batchStart) // 滑入下一批
        assertEquals(2, s.batchSize)
        s = RecallSessionOps.rate(s, 0, Rating.GOOD, t0, ::sm2Record, Random(1))
        s = RecallSessionOps.rate(s, 1, Rating.GOOD, t0, ::sm2Record, Random(1))
        assertEquals(4, s.batchStart)
        assertEquals(1, s.batchSize) // 末批
        s = RecallSessionOps.rate(s, 0, Rating.GOOD, t0, ::sm2Record, Random(1))
        assertTrue(s.finished) // Good = +300s，不在会话内再现 → 结束
    }

    @Test
    fun `轮末检查_会话内到期词进入下一轮_洗牌置灰解除`() {
        val words = (1L..3L).map { word(it) }
        var s = RecallSessionBuilder.build(words, emptyMap(), "en2zh", displayCount = 3, Random(1), t0)
        // 条目 0 忘记（+60s），其余记住（+300s）
        val idx0 = s.batch.indexOfFirst { it.word.id == 1L }
        s = RecallSessionOps.rate(s, idx0, Rating.FORGET, t0, ::sm2Record, Random(1))
        val others = s.queue.filter { it.word.id != 1L }
        val i1 = s.batch.indexOfFirst { it.word.id == others[0].word.id }
        s = RecallSessionOps.rate(s, i1, Rating.GOOD, t0, ::sm2Record, Random(1))
        val i2 = s.batch.indexOfFirst { it.word.id == others[1].word.id }
        s = RecallSessionOps.rate(s, i2, Rating.GOOD, t0, ::sm2Record, Random(1))
        assertTrue(s.finished) // t0 时刻检查：60s 未过 → 不再现
        // 时间推进 61s 后再检查（等价于会话中在轮末重查）
        val revived = RecallSessionOps.rate(s, 0, Rating.EASY, t0 + 61.seconds, ::sm2Record, Random(1))
        assertTrue(revived.finished) // 已结束的会话不复活
    }

    @Test
    fun `轮末检查_遗忘词在会话进行中到期则重入新轮`() {
        val words = (1L..3L).map { word(it) }
        var s = RecallSessionBuilder.build(words, emptyMap(), "en2zh", displayCount = 3, Random(1), t0)
        val idx0 = s.batch.indexOfFirst { it.word.id == 1L }
        s = RecallSessionOps.rate(s, idx0, Rating.FORGET, t0, ::sm2Record, Random(1)) // t0+60s 到期
        // 其余两条在 61s 后评级 → 轮末检查时遗忘词已到期
        val others = s.queue.filter { it.word.id != 1L }
        val i1 = s.batch.indexOfFirst { it.word.id == others[0].word.id }
        s = RecallSessionOps.rate(s, i1, Rating.GOOD, t0 + 61.seconds, ::sm2Record, Random(1))
        val i2 = s.batch.indexOfFirst { it.word.id == others[1].word.id }
        s = RecallSessionOps.rate(s, i2, Rating.GOOD, t0 + 61.seconds, ::sm2Record, Random(1))
        assertFalse(s.finished)
        assertEquals(2, s.roundNo) // 新轮
        assertEquals(listOf(1L), s.queue.map { it.word.id }) // 仅遗忘词再现
        assertTrue(s.queue.single().rating == null) // 置灰解除
        assertEquals(0, s.selection)
    }

    // ---- 落账口径（PRD 1.2 #21；ISSUE-006 范围 7）----

    @Test
    fun `落账_首评新词计新学_首评旧词计复习_判定数各一`() {
        val fresh = RecallItem(word(1), Facet.EN2ZH, hadRecord = false)
        val reviewed = RecallItem(word(2), Facet.ZH2EN, hadRecord = true)
        val d1 = RecallSessionOps.logDelta(fresh, isFirstRating = true, rating = Rating.GOOD)
        assertEquals(1, d1.newWordsLearned)
        assertEquals(0, d1.wordsReviewed)
        assertEquals(1, d1.totalJudgments)
        assertEquals(1, d1.correctJudgments)
        val d2 = RecallSessionOps.logDelta(reviewed, isFirstRating = true, rating = Rating.HARD)
        assertEquals(0, d2.newWordsLearned)
        assertEquals(1, d2.wordsReviewed)
        assertEquals(1, d2.correctJudgments)
    }

    @Test
    fun `落账_改评只计判定_忘记不计正确`() {
        val item = RecallItem(word(1), Facet.EN2ZH, hadRecord = false)
        val reRate = RecallSessionOps.logDelta(item, isFirstRating = false, rating = Rating.EASY)
        assertEquals(0, reRate.newWordsLearned)
        assertEquals(0, reRate.wordsReviewed)
        assertEquals(1, reRate.totalJudgments)
        assertEquals(1, reRate.correctJudgments)
        val forget = RecallSessionOps.logDelta(item, isFirstRating = true, rating = Rating.FORGET)
        assertEquals(1, forget.totalJudgments)
        assertEquals(0, forget.correctJudgments) // Forget = 不正确
    }
}
