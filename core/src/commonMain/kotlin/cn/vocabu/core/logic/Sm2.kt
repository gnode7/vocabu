package cn.vocabu.core.logic

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * SM-2 调度算法（PRD §3.3，权威公式）。
 * 纯函数：时间由参数传入，不读时钟（ISSUE-001）。
 */
object Sm2 {

    const val MIN_EASE_FACTOR: Float = 1.3f
    const val INITIAL_EASE_FACTOR: Float = 2.5f

    /** 评级 <3（Forget）的重置间隔 */
    const val FORGET_INTERVAL_SECONDS: Long = 60L
    /** rep==1 的间隔 */
    const val FIRST_INTERVAL_SECONDS: Long = 300L
    /** rep==2 的间隔 */
    const val SECOND_INTERVAL_SECONDS: Long = 1800L

    /**
     * 按 quality 更新/创建一条学习记录。
     *
     * @param existing 该考核面上的现有记录；null = 首次评级（创建记录，PRD 修订 #13）
     */
    fun update(
        wordId: Long,
        facet: Facet,
        existing: LearningRecord?,
        quality: Int,
        now: Instant,
    ): LearningRecord {
        val prevEase = existing?.easeFactor ?: INITIAL_EASE_FACTOR
        // SM-2 公式中的 (5-q) 项：quality 越低该项越大，EF 降幅越大
        val qualityGap = (5 - quality).coerceIn(0, 5)
        val ef = (prevEase + (0.1f - qualityGap * (0.08f + qualityGap * 0.02f))).coerceAtLeast(MIN_EASE_FACTOR)

        val repetitionCount: Int
        val intervalSeconds: Long
        if (quality < 3) {
            repetitionCount = 0
            intervalSeconds = FORGET_INTERVAL_SECONDS
        } else {
            repetitionCount = (existing?.repetitionCount ?: 0) + 1
            intervalSeconds = when (repetitionCount) {
                1 -> FIRST_INTERVAL_SECONDS
                2 -> SECOND_INTERVAL_SECONDS
                else -> (existing!!.intervalSeconds * ef).roundToLong()
            }
        }

        return LearningRecord(
            id = existing?.id ?: 0,
            wordId = wordId,
            facet = facet,
            easeFactor = ef,
            repetitionCount = repetitionCount,
            intervalSeconds = intervalSeconds,
            nextReviewTime = now + intervalSeconds.seconds,
            lastReviewTime = now,
            totalReviews = (existing?.totalReviews ?: 0) + 1,
            totalForgets = (existing?.totalForgets ?: 0) + if (quality < 3) 1 else 0,
        )
    }
}
