package cn.vocabu.core.logic

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Word
import kotlin.time.Instant

/**
 * 今日词表生成（PRD §2.2.2、修订 #7）：
 * 新词（按添加顺序，受每日新词上限）+ 到期复习词（任一面到期）+ 缺面补齐词（配额内最旧活动优先）。
 * 三类互斥，去重天然成立。
 */
object TodayListBuilder {

    data class TodayList(
        val newWords: List<Word>,
        val reviewWords: List<Word>,
        val catchUpWords: List<Word>,
    ) {
        /** 今日词表全量：新词 + 复习 + 补齐，顺序即新词在前。 */
        val all: List<Word> get() = newWords + reviewWords + catchUpWords
    }

    fun build(
        words: List<Word>,
        recordsByWordId: Map<Long, List<LearningRecord>>,
        dailyNewWordCount: Int,
        facetCatchUpQuota: Int,
        now: Instant,
    ): TodayList {
        // 新词：三面全无记录，按添加顺序（id 升序）取 min(上限, 剩余未学)
        val newWords = words
            .filter { recordsByWordId[it.id].isNullOrEmpty() }
            .sortedBy { it.id }
            .take(dailyNewWordCount.coerceAtLeast(0))

        // 到期复习词：任一面 nextReviewTime <= now
        val reviewWords = words
            .filter { word -> recordsByWordId[word.id]?.any { it.isDue(now) } == true }
            .sortedBy { it.id }
        val reviewIds: Set<Long> = reviewWords.map { it.id }.toSet()

        // 缺面补齐：有记录、有缺面、未到期（到期已归复习），按最近评级时间最旧优先
        val catchUpWords = words
            .asSequence()
            .filter { it.id !in reviewIds && !recordsByWordId[it.id].isNullOrEmpty() }
            .filter { word ->
                val recs = recordsByWordId[word.id].orEmpty()
                Facet.entries.any { facet -> recs.none { it.facet == facet } }
            }
            .sortedWith(compareBy({ recentActivityOf(recordsByWordId[it.id]) }, { it.id }))
            .take(facetCatchUpQuota.coerceAtLeast(0))
            .toList()

        return TodayList(newWords, reviewWords, catchUpWords)
    }

    /** 最近一次评级时间（该词全部考核面中最新者）；无记录视为最早。 */
    private fun recentActivityOf(records: List<LearningRecord>?): Instant =
        records?.mapNotNull { it.lastReviewTime }?.maxOrNull() ?: Instant.DISTANT_PAST
}
