package cn.vocabu.core.logic

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Word
import kotlin.time.Instant

/**
 * 三入口计数（PRD §2.4.1、§2.5.1）：入口按钮显示今日计数 = 当前设置（方向/方式）对应工作集词数。
 * 设置变化 → 计数联动（ISSUE-004 TDD 用例）。
 */
object EntryCounter {

    /** 回忆入口计数 = 回忆方向对应考核面的工作集词数。 */
    fun recallCount(
        todayList: List<Word>,
        recordsByWordId: Map<Long, List<LearningRecord>>,
        direction: String,
        now: Instant,
    ): Int = WorkingSet.filter(todayList, recordsByWordId, facetsForDirection(direction), now).size

    /** 考察入口计数 = 考察方式对应考核面并集的工作集词数（混合 = 先听写后默写 → 三面并集）。 */
    fun testCount(
        todayList: List<Word>,
        recordsByWordId: Map<Long, List<LearningRecord>>,
        mode: String,
        now: Instant,
    ): Int = WorkingSet.filter(todayList, recordsByWordId, facetsForTestMode(mode), now).size

    /** 回忆方向 → 考核面（PRD §2.4.1：英→中 / 中→英 / 混合=两面任一）。未知取值回退混合。 */
    fun facetsForDirection(direction: String): Set<Facet> = when (direction) {
        "en2zh" -> setOf(Facet.EN2ZH)
        "zh2en" -> setOf(Facet.ZH2EN)
        else -> setOf(Facet.EN2ZH, Facet.ZH2EN) // mixed
    }

    /** 考察方式 → 考核面（听写=听拼+英中；默写=中英；混合=三面并集）。未知取值回退混合。 */
    fun facetsForTestMode(mode: String): Set<Facet> = when (mode) {
        "dictation" -> setOf(Facet.AUDIO_SPELLING, Facet.EN2ZH)
        "writing" -> setOf(Facet.ZH2EN)
        else -> Facet.entries.toSet() // mixed
    }
}
