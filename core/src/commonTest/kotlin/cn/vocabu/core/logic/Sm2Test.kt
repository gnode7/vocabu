package cn.vocabu.core.logic

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Rating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class Sm2Test {

    private val now: Instant = Instant.fromEpochSeconds(1_000_000)

    /** 已存在的一账本（rep=2、间隔1800s 的典型中途状态） */
    private fun midRecord(
        ef: Float = 2.5f,
        rep: Int = 2,
        interval: Long = 1800,
        reviews: Int = 2,
        forgets: Int = 0,
    ) = LearningRecord(
        id = 7,
        wordId = 1,
        facet = Facet.EN2ZH,
        easeFactor = ef,
        repetitionCount = rep,
        intervalSeconds = interval,
        nextReviewTime = Instant.fromEpochSeconds(999),
        lastReviewTime = Instant.fromEpochSeconds(100),
        totalReviews = reviews,
        totalForgets = forgets,
    )

    // ---- 首次评级（无记录 → 创建记录）----

    @Test
    fun `首次评级Good创建记录 rep1 间隔300秒 EF保持2点5`() {
        val r = Sm2.update(wordId = 1, facet = Facet.EN2ZH, existing = null, quality = Rating.GOOD.quality, now = now)

        assertEquals(1, r.repetitionCount)
        assertEquals(300L, r.intervalSeconds)
        assertEquals(2.5f, r.easeFactor, 1e-3f)
        assertEquals(now, r.lastReviewTime)
        assertEquals(now + 300.seconds, r.nextReviewTime)
    }

    @Test
    fun `首次评级Forget创建记录 rep归零 间隔60秒`() {
        val r = Sm2.update(1, Facet.EN2ZH, null, Rating.FORGET.quality, now)

        assertEquals(0, r.repetitionCount)
        assertEquals(60L, r.intervalSeconds)
        assertEquals(now + 60.seconds, r.nextReviewTime)
        assertEquals(1, r.totalForgets)
    }

    @Test
    fun `首次评级Easy EF上升0点1`() {
        val r = Sm2.update(1, Facet.EN2ZH, null, Rating.EASY.quality, now)
        assertEquals(2.6f, r.easeFactor, 1e-3f)
    }

    @Test
    fun `首次评级Hard EF微降0点14`() {
        val r = Sm2.update(1, Facet.EN2ZH, null, Rating.HARD.quality, now)
        assertEquals(2.36f, r.easeFactor, 1e-3f)
    }

    // ---- 连续评级：三段间隔 ----

    @Test
    fun `Good三连 间隔走300到1800再到EF乘积`() {
        val r1 = Sm2.update(1, Facet.EN2ZH, null, Rating.GOOD.quality, now)
        val r2 = Sm2.update(1, Facet.EN2ZH, r1, Rating.GOOD.quality, now)
        val r3 = Sm2.update(1, Facet.EN2ZH, r2, Rating.GOOD.quality, now)

        assertEquals(300L, r1.intervalSeconds)
        assertEquals(1800L, r2.intervalSeconds)
        // EF 2.5 在 Good 下不变：1800 * 2.5 = 4500
        assertEquals(4500L, r3.intervalSeconds)
        assertEquals(3, r3.repetitionCount)
    }

    @Test
    fun `Easy三连 新EF参与间隔计算`() {
        val r1 = Sm2.update(1, Facet.EN2ZH, null, Rating.EASY.quality, now)
        val r2 = Sm2.update(1, Facet.EN2ZH, r1, Rating.EASY.quality, now)
        val r3 = Sm2.update(1, Facet.EN2ZH, r2, Rating.EASY.quality, now)

        // EF: 2.5 → 2.6 → 2.7 → 2.8；rep3 间隔 = 1800 * 2.8 = 5040
        assertEquals(2.7f, r2.easeFactor, 1e-3f)
        assertEquals(5040L, r3.intervalSeconds)
    }

    // ---- Forget 重置与 EF 下限 ----

    @Test
    fun `中途Forget重置为60秒且rep归零`() {
        val r = Sm2.update(1, Facet.EN2ZH, midRecord(), Rating.FORGET.quality, now)

        assertEquals(0, r.repetitionCount)
        assertEquals(60L, r.intervalSeconds)
        assertEquals(1, r.totalForgets)
    }

    @Test
    fun `EF下限1点3 连续Forget不再下降`() {
        val f1 = Sm2.update(1, Facet.EN2ZH, null, Rating.FORGET.quality, now)          // 2.5-0.8 = 1.7
        val f2 = Sm2.update(1, Facet.EN2ZH, f1, Rating.FORGET.quality, now)            // 1.7-0.8 → 下限 1.3
        val f3 = Sm2.update(1, Facet.EN2ZH, f2, Rating.FORGET.quality, now)            // 仍 1.3

        assertEquals(1.7f, f1.easeFactor, 1e-3f)
        assertEquals(1.3f, f2.easeFactor, 1e-3f)
        assertEquals(1.3f, f3.easeFactor, 1e-3f)
    }

    // ---- 统计字段 ----

    @Test
    fun `成功评级累加totalReviews但不加totalForgets`() {
        val r = Sm2.update(1, Facet.EN2ZH, midRecord(reviews = 5, forgets = 2), Rating.GOOD.quality, now)

        assertEquals(6, r.totalReviews)
        assertEquals(2, r.totalForgets)
    }

    // ---- 评级映射 ----

    @Test
    fun `评级映射 Easy5 Good4 Hard3 Forget0`() {
        assertEquals(5, Rating.EASY.quality)
        assertEquals(4, Rating.GOOD.quality)
        assertEquals(3, Rating.HARD.quality)
        assertEquals(0, Rating.FORGET.quality)
        assertEquals(Rating.HARD, Rating.fromQuality(3))
        assertEquals(Rating.FORGET, Rating.fromQuality(0))
    }
}
