package cn.vocabu.core.model

import kotlin.time.Instant

/** 考核面：一对「刺激→反应」构成的考核维度（CONTEXT.md）。 */
enum class Facet {
    /** 英→中（认读：英文文本→中文释义） */
    EN2ZH,

    /** 中→英（产出：中文释义→英文拼写） */
    ZH2EN,

    /** 听→拼（听写：英文音频→英文拼写） */
    AUDIO_SPELLING,
}

/** 用户评级 ↔ SM-2 quality 值的映射（PRD §3.3）。 */
enum class Rating(val quality: Int) {
    FORGET(0),
    HARD(3),
    GOOD(4),
    EASY(5),
    ;

    companion object {
        fun fromQuality(quality: Int): Rating = entries.first { it.quality == quality }
    }
}

/**
 * 学习记录：一个词条在一个考核面上的记忆状态（CONTEXT.md）。
 * 时间一律用 [Instant]；epoch 秒换算集中在持久层（ISSUE-002）。
 * 未落库的记录 id 为 0，由仓库分配真实 id。
 */
data class LearningRecord(
    val id: Long = 0,
    val wordId: Long,
    val facet: Facet,
    /** EF，初始 2.5，下限 1.3 */
    val easeFactor: Float = 2.5f,
    /** 连续成功次数；Forget 后归零 */
    val repetitionCount: Int = 0,
    /** 当前间隔（秒）；rep≥3 时下次间隔 = 间隔 × 新 EF */
    val intervalSeconds: Long = 0,
    val nextReviewTime: Instant,
    val lastReviewTime: Instant? = null,
    val totalReviews: Int = 0,
    val totalForgets: Int = 0,
) {
    /** 是否到期（供词表/工作集过滤）。 */
    fun isDue(now: Instant): Boolean = nextReviewTime <= now
}
