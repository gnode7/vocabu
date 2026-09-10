package cn.vocabu.core.logic

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Word
import kotlin.random.Random
import kotlin.time.Instant

/**
 * 工作集过滤（PRD §2.4.1、修订 #8）：
 * 词 ∈ 今日词表 且（目标考核面缺记录 或 到期）。
 * 「到期」只是进入工作集的触发器，不是唯一考核时机——考察场景按 ADR 0006 双面全量落账，
 * 未到期面的评级等效一次提前复习；此处筛选语义不受影响。
 */
object WorkingSet {

    /** 模式的目标考核面：英译中={EN2ZH}、中译英={ZH2EN}、听写={AUDIO_SPELLING, EN2ZH}、默写={ZH2EN}。 */
    fun facetsFor(mode: StudyMode): Set<Facet> = when (mode) {
        StudyMode.FIRST_PASS -> emptySet() // 初步记忆面向全部新词，不走工作集过滤
        StudyMode.EN2ZH_RECALL -> setOf(Facet.EN2ZH)
        StudyMode.ZH2EN_RECALL -> setOf(Facet.ZH2EN)
        StudyMode.MIXED_RECALL -> setOf(Facet.EN2ZH, Facet.ZH2EN)
        StudyMode.DICTATION -> setOf(Facet.AUDIO_SPELLING, Facet.EN2ZH)
        StudyMode.WRITING -> setOf(Facet.ZH2EN)
    }

    /**
     * @param todayList 今日词表（TodayListBuilder 产出）
     * @param facets 该模式目标考核面集合
     */
    fun filter(
        todayList: List<Word>,
        recordsByWordId: Map<Long, List<LearningRecord>>,
        facets: Set<Facet>,
        now: Instant,
    ): List<Word> = todayList.filter { word ->
        val recs = recordsByWordId[word.id].orEmpty()
        facets.any { facet ->
            val facetRec = recs.firstOrNull { it.facet == facet }
            facetRec == null || facetRec.isDue(now)
        }
    }
}

/** 学习模式（PRD §2.4/§2.5）。 */
enum class StudyMode {
    /** 初步记忆（浏览，零副作用） */
    FIRST_PASS,

    /** 英译中回忆 */
    EN2ZH_RECALL,

    /** 中译英回忆 */
    ZH2EN_RECALL,

    /** 混合回忆 */
    MIXED_RECALL,

    /** 听写（双评分：英文段→听拼账，中文段→英中账） */
    DICTATION,

    /** 默写（评级入中→英账） */
    WRITING,
}

/**
 * 混合回忆方向分配（PRD §2.4.4、修订 #9）：
 * 在可评方向（缺记录或到期）中随机；仅单面可评则固定；双面均不可评为 null。
 * 仅考虑 EN2ZH / ZH2EN 两方向（听拼面不参与混合回忆）。
 */
object DirectionAssigner {

    fun assign(
        records: List<LearningRecord>,
        random: Random,
        now: Instant,
    ): Facet? {
        fun rateable(facet: Facet): Boolean {
            val rec = records.firstOrNull { it.facet == facet }
            return rec == null || rec.isDue(now)
        }

        val en2zh = rateable(Facet.EN2ZH)
        val zh2en = rateable(Facet.ZH2EN)
        return when {
            en2zh && zh2en -> if (random.nextBoolean()) Facet.EN2ZH else Facet.ZH2EN
            en2zh -> Facet.EN2ZH
            zh2en -> Facet.ZH2EN
            else -> null
        }
    }
}
