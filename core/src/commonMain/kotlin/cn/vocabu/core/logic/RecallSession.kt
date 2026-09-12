package cn.vocabu.core.logic

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Rating
import cn.vocabu.core.model.Word
import kotlin.random.Random
import kotlin.time.Instant

/**
 * 回忆会话状态机（PRD §2.4、ISSUE-006）。纯逻辑：时间与随机源由参数注入，不读时钟。
 *
 * 结构：一轮 = 工作集全体条目（已洗牌）；列表按「同时展示条数」分批窗口展示，
 * 当前批全部评级后滑入下一批；全批耗尽做轮末检查——
 * 下次复习时间 <= 当下的词重新洗牌进入下一轮（置灰解除），否则会话结束。
 * 评级去向：写入该条目分配方向对应考核面账本（ADR 0004）。
 */
data class RecallSession(
    val directionSetting: String,
    val displayCount: Int,
    /** 当前轮次（从 1 起） */
    val roundNo: Int,
    /** 本轮全部条目（已洗牌；已评条目置灰保留不消失、可改评） */
    val queue: List<RecallItem>,
    /** 当前批窗口在 queue 中的起始下标（之前的条目在本轮内均已评） */
    val batchStart: Int,
    /** 当前批条数（≤ displayCount；末批可更小） */
    val batchSize: Int,
    /** 批内选中下标 */
    val selection: Int,
    /** 全部完成（无可再现条目） */
    val finished: Boolean = false,
    /** 全会话评级事件数（含改评） */
    val totalRatingEvents: Int = 0,
) {
    val batch: List<RecallItem> get() = queue.drop(batchStart).take(batchSize)
    val ratedCount: Int get() = queue.count { it.rating != null }
}

/** 回忆会话条目：词 + 分配方向 + 会话内评级状态。 */
data class RecallItem(
    val word: Word,
    /** 分配方向（PRD §2.4.4：混合在可评方向中随机；单方向固定） */
    val facet: Facet,
    /** 会话开始时该面是否已有账（首评级时：无账=新学、有账=复习，PRD 1.2 #21） */
    val hadRecord: Boolean,
    /** 会话内最后一次评级（以最后一次为准，PRD §2.4.5） */
    val rating: Rating? = null,
    /** 最近一次评级后的账面（轮末到期检查依据） */
    val recordAfterRating: LearningRecord? = null,
)

object RecallSessionBuilder {

    /** 组建第 1 轮：工作集（今日词表 ∩ 方向对应面到期或缺面）+ 方向分配 + 洗牌。 */
    fun build(
        todayList: List<Word>,
        recordsByWordId: Map<Long, List<LearningRecord>>,
        directionSetting: String,
        displayCount: Int,
        random: Random,
        now: Instant,
    ): RecallSession {
        val facets = EntryCounter.facetsForDirection(directionSetting)
        val words = WorkingSet.filter(todayList, recordsByWordId, facets, now)
        val items = words.mapNotNull { word ->
            val records = recordsByWordId[word.id].orEmpty()
            val facet: Facet? = when {
                facets == setOf(Facet.EN2ZH) -> Facet.EN2ZH
                facets == setOf(Facet.ZH2EN) -> Facet.ZH2EN
                else -> DirectionAssigner.assign(records, random, now)
            }
            facet?.let {
                RecallItem(
                    word = word,
                    facet = it,
                    hadRecord = records.any { record -> record.facet == it },
                )
            }
        }.let { shuffled -> shuffled.shuffled(random) }

        val count = displayCount.coerceAtLeast(1)
        return RecallSession(
            directionSetting = directionSetting,
            displayCount = count,
            roundNo = 1,
            queue = items,
            batchStart = 0,
            batchSize = minOf(count, items.size),
            selection = 0,
        )
    }
}

object RecallSessionOps {

    /**
     * 批内评级：写入会话内最后一次评级，并由 [recordFn] 产出评级后的账面（SM-2 落库由 VM 执行）。
     * 首评自动把选中态移到批内下一条未评条目（无则停留，交给轮转处理）；改评停留原地。
     * 评级后立即推进状态：当前批全部评完 → 滑下一批或做轮末检查。
     */
    fun rate(
        session: RecallSession,
        batchIndex: Int,
        rating: Rating,
        now: Instant,
        recordFn: (RecallItem, Rating, Instant) -> LearningRecord,
        random: Random,
    ): RecallSession {
        if (session.finished) return session
        val globalIndex = session.batchStart + batchIndex
        val item = session.queue.getOrNull(globalIndex) ?: return session
        val isFirstRating = item.rating == null
        val updated = item.copy(
            rating = rating,
            recordAfterRating = recordFn(item, rating, now),
        )
        val queue = session.queue.toMutableList().also { it[globalIndex] = updated }
        val selection = if (isFirstRating) {
            val batchEnd = session.batchStart + session.batchSize
            (globalIndex + 1 until batchEnd)
                .firstOrNull { queue[it].rating == null }
                ?.minus(session.batchStart)
                ?: session.selection
        } else {
            session.selection
        }
        val afterRating = session.copy(
            queue = queue,
            selection = selection,
            totalRatingEvents = session.totalRatingEvents + 1,
        )
        return advance(afterRating, now, random)
    }

    /**
     * 推进：当前批全部评完 → 窗口滑到下一段未评条目；
     * 全轮评完 → 轮末检查（下次复习时间 <= now 的词重新洗牌进入下一轮，否则结束）。
     */
    fun advance(session: RecallSession, now: Instant, random: Random): RecallSession {
        if (session.finished) return session
        val batch = session.batch
        if (batch.any { it.rating == null }) return session

        val nextUnrated = session.queue.indexOfFirst { it.rating == null }
        if (nextUnrated >= 0) {
            return session.copy(
                batchStart = nextUnrated,
                batchSize = minOf(session.displayCount, session.queue.size - nextUnrated),
                selection = 0,
            )
        }

        val dueAgain = session.queue
            .filter { it.recordAfterRating != null && it.recordAfterRating.nextReviewTime <= now }
            .map { it.copy(rating = null, recordAfterRating = null) }
            .let { it.shuffled(random) }
        if (dueAgain.isEmpty()) return session.copy(finished = true)

        return session.copy(
            roundNo = session.roundNo + 1,
            queue = dueAgain,
            batchStart = 0,
            batchSize = minOf(session.displayCount, dueAgain.size),
            selection = 0,
        )
    }

    /**
     * 单次评级事件的学习日志增量（PRD 1.2 #21：分母 = 判定数，Forget = 不正确）。
     * 首评：无账 = 新学 +1、有账 = 复习 +1；改评只计判定（会话内以最后一次评级更新 SM-2，日志按事件计）。
     */
    fun logDelta(item: RecallItem, isFirstRating: Boolean, rating: Rating): RecallLogDelta {
        val isNewWord = isFirstRating && !item.hadRecord
        val isReview = isFirstRating && item.hadRecord
        return RecallLogDelta(
            newWordsLearned = if (isNewWord) 1 else 0,
            wordsReviewed = if (isReview) 1 else 0,
            correctJudgments = if (rating != Rating.FORGET) 1 else 0,
            totalJudgments = 1,
        )
    }
}

/** 评级事件的当日统计增量（经 StudyLogRepository.increment 落账，修订 #19「提交即落账」）。 */
data class RecallLogDelta(
    val newWordsLearned: Int,
    val wordsReviewed: Int,
    val correctJudgments: Int,
    val totalJudgments: Int,
)
