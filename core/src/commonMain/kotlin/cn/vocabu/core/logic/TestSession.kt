package cn.vocabu.core.logic

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Rating
import cn.vocabu.core.model.Word
import kotlin.random.Random
import kotlin.time.Instant

/**
 * 考察会话状态机（PRD §2.5、ISSUE-009）。纯逻辑：时间与随机源由参数注入，不读时钟。
 *
 * 结构：单一入口按「考察方式」（dictation/writing/mixed）组建队列——
 * 混合 = 先听写部分（听拼/英中面）后默写部分（中英面），各自洗牌；
 * **工作集只筛词，不筛面**（WorkingSet any-of，ADR 0006）：进入会话后该作答形态
 * 对应的全部考核面都判定并落账（含未到期面）。
 *
 * 听写：播报 → 中文/英文两框作答（先中文后英文，Tab 切换）→ 两段回车：
 * 第一段锁定当前框并切焦点（**锁定即终局**，提交前不可回改），第二段提交批改
 * （按钮 = 直接提交；第二段回车的两框锁定在批改时一并生效）。
 * 默写：单输入，回车或按钮即提交。
 *
 * 提交即落账：每词批改立刻产出各面 SM-2 记录与当日统计增量（分母 = 判定数，听写计 2）。
 * 轮末检查（部内）：**听写部分轮次循环结束后才进默写**（跨界检查听写段到期面，复活词排在新轮
 * 前段、未开始的默写词殿后）；队列末尾只查末段。Forget 面（+60s）到期 → 该词**整词重考**
 * （两面同判同落账，作答/锁定/计时/批改全清），洗牌开新轮；无到期面则结束，已结束的会话不复活。
 * 上一个/下一个为只读回看，不重复落账；提交前不可跳过当前词。
 * 计时 = 焦点注意力时间：焦点在哪个框哪个框累计，另一框暂停；锁定/批改后冻结。
 */

/** 考察部分：听写（听拼 + 英中两面）或默写（中英单面）。 */
enum class TestPart { DICTATION, WRITING }

/** 作答输入框：中文释义（zh）与英文单词（en）；默写仅用 EN 框。 */
enum class TestBox { ZH, EN }

/** 该部分的作答框顺序（听写 = 先中文后英文；默写 = 仅英文）。 */
fun TestPart.boxes(): List<TestBox> = when (this) {
    TestPart.DICTATION -> listOf(TestBox.ZH, TestBox.EN)
    TestPart.WRITING -> listOf(TestBox.EN)
}

/** 单框批改结果（锁定即终局，不可回改）。 */
data class PartJudgment(
    val answer: String,
    val correct: Boolean,
    /** SM-2 quality：0（错/留空）/ 3 / 4 / 5（AnswerJudge.qualityFromTiming） */
    val quality: Int,
    val rating: Rating,
    /** 提交时该框累计焦点时间（秒） */
    val elapsedSeconds: Long,
    /** 该账本面提交前是否有账（新学/复习口径） */
    val hadRecord: Boolean,
    /** 提交产出的 SM-2 记录快照（VM 落库；轮末检查读 nextReviewTime） */
    val record: LearningRecord,
)

/** 考察条目：词 + 部分 + 两框作答/计时/批改状态（默写仅用 en 系字段）。 */
data class TestItem(
    val word: Word,
    val part: TestPart,
    val zhAnswer: String = "",
    val enAnswer: String = "",
    val zhLocked: Boolean = false,
    val enLocked: Boolean = false,
    /** 各框累计焦点时间（秒） */
    val zhElapsed: Long = 0,
    val enElapsed: Long = 0,
    val zhJudgment: PartJudgment? = null,
    val enJudgment: PartJudgment? = null,
    /** 新学/复习口径：构建期确定的「账本面提交前是否有账」 */
    val hadZhRecord: Boolean = false,
    val hadEnRecord: Boolean = false,
)

/** 词粒度是否已完成批改（听写两框全判 / 默写英文框已判）。 */
fun TestItem.isJudged(): Boolean = when (part) {
    TestPart.DICTATION -> zhJudgment != null && enJudgment != null
    TestPart.WRITING -> enJudgment != null
}

/** 该框的评级去向账本（ADR 0004/0006）：中文框→英中；英文框→听写入听拼、默写入中英。 */
fun testLedgerFacet(item: TestItem, box: TestBox): Facet = when (box) {
    TestBox.ZH -> Facet.EN2ZH
    TestBox.EN -> if (item.part == TestPart.DICTATION) Facet.AUDIO_SPELLING else Facet.ZH2EN
}

data class TestSession(
    val mode: String,
    /** 当前轮次（从 1 起） */
    val roundNo: Int,
    /** 本轮全部条目（已洗牌；听写部分在前、默写部分在后） */
    val queue: List<TestItem>,
    /** 当前条目下标 */
    val cursor: Int,
    /** 当前焦点框；null = 焦点移交「下一个/提交」按钮（提交后/只读回看） */
    val focusedBox: TestBox?,
    val finished: Boolean = false,
    /** 会话累计判定数（听写每词 2、默写每词 1） */
    val totalJudgments: Int = 0,
    /** 会话内已落账的 (wordId, facet)——新学/复习口径的实时补充：重考提交不再重复计新学 */
    val ledgered: Set<Pair<Long, Facet>> = emptySet(),
) {
    val currentItem: TestItem? get() = queue.getOrNull(cursor)

    /** 会话去重词数（含已完结轮次；完成面板「共 X 词」口径）。 */
    val distinctWordCount: Int get() = ledgered.map { it.first }.toSet().size

    /** 已完成批改的词数（计量表进度）。 */
    val judgedWords: Int get() = queue.count { it.isJudged() }

    /** 条目首个可用框（未锁定即可用）：听写 = 中文框、默写 = 英文框。 */
    fun defaultBox(item: TestItem): TestBox? = item.part.boxes().firstOrNull { !boxLocked(item, it) }

    private fun boxLocked(item: TestItem, box: TestBox): Boolean =
        if (box == TestBox.ZH) item.zhLocked else item.enLocked
}

object TestSessionBuilder {

    /** 组建会话：按方式筛词（只筛词不筛面）、分部分洗牌（混合先听写后默写）、定初始焦点。 */
    fun build(
        todayList: List<Word>,
        recordsByWordId: Map<Long, List<LearningRecord>>,
        mode: String,
        random: Random,
        now: Instant,
    ): TestSession {
        val part = when (mode) {
            "dictation" -> TestPart.DICTATION
            "writing" -> TestPart.WRITING
            else -> null // mixed 及未知取值回退：两部分并集（EntryCounter 同口径）
        }
        val dictationWords = if (part == null || part == TestPart.DICTATION) {
            WorkingSet.filter(todayList, recordsByWordId, setOf(Facet.AUDIO_SPELLING, Facet.EN2ZH), now)
        } else {
            emptyList()
        }
        val writingWords = if (part == null || part == TestPart.WRITING) {
            WorkingSet.filter(todayList, recordsByWordId, setOf(Facet.ZH2EN), now)
        } else {
            emptyList()
        }
        val queue = dictationWords.map { dictationItem(it, recordsByWordId[it.id].orEmpty()) }.shuffled(random) +
            writingWords.map { writingItem(it, recordsByWordId[it.id].orEmpty()) }.shuffled(random)
        val session = TestSession(mode = mode, roundNo = 1, queue = queue, cursor = 0, focusedBox = null)
        return if (queue.isEmpty()) {
            session.copy(finished = true)
        } else {
            session.copy(focusedBox = session.defaultBox(queue.first()))
        }
    }

    /** 听写条目：新学/复习口径按听拼面（英）与英中面（中）。 */
    private fun dictationItem(word: Word, records: List<LearningRecord>): TestItem = TestItem(
        word = word,
        part = TestPart.DICTATION,
        hadZhRecord = records.any { it.facet == Facet.EN2ZH },
        hadEnRecord = records.any { it.facet == Facet.AUDIO_SPELLING },
    )

    /** 默写条目：口径按中英面（英框）。 */
    private fun writingItem(word: Word, records: List<LearningRecord>): TestItem = TestItem(
        word = word,
        part = TestPart.WRITING,
        hadEnRecord = records.any { it.facet == Facet.ZH2EN },
    )
}

object TestSessionOps {

    /** 提交批改产出：新会话 + 逐面落账产出（VM 按序落库 + 当日统计）。 */
    data class SubmitOutcome(
        val session: TestSession,
        /** 批改明细，顺序与 [TestPart.boxes] 一致（听写：先中文后英文；默写：英文） */
        val outcomes: List<PartOutcome>,
    )

    data class PartOutcome(
        val wordId: Long,
        val facet: Facet,
        val judgment: PartJudgment,
    )

    /** 当日统计增量（一次提交合并计；分母 = 判定数）。 */
    data class LogDelta(
        val newWordsLearned: Int,
        val wordsReviewed: Int,
        val correctJudgments: Int,
        val totalJudgments: Int,
    )

    /** 输入同步（VM TextField）：仅未锁定且未批改的框可改；默写忽略中文框。 */
    fun type(session: TestSession, box: TestBox, text: String): TestSession {
        val item = session.currentItem ?: return session
        if (item.part == TestPart.WRITING && box == TestBox.ZH) return session
        if (boxLocked(item, box) || boxJudged(item, box)) return session
        return session.updateCurrent(if (box == TestBox.ZH) item.copy(zhAnswer = text) else item.copy(enAnswer = text))
    }

    /** 切换焦点（Tab/点击）：锁定或已批改的框不可聚焦（批改后焦点系统移交按钮）。 */
    fun focus(session: TestSession, box: TestBox): TestSession {
        val item = session.currentItem ?: return session
        if (item.part == TestPart.WRITING && box == TestBox.ZH) return session
        if (boxLocked(item, box) || boxJudged(item, box)) return session
        return session.copy(focusedBox = box)
    }

    /** 焦点计时推进（UI 周期 tick）：仅累计当前条目、当前焦点框、未锁定未批改的框。 */
    fun tick(session: TestSession, deltaSeconds: Long = 1): TestSession {
        val item = session.currentItem ?: return session
        val box = session.focusedBox ?: return session
        if (boxLocked(item, box) || boxJudged(item, box)) return session
        return session.updateCurrent(
            if (box == TestBox.ZH) item.copy(zhElapsed = item.zhElapsed + deltaSeconds)
            else item.copy(enElapsed = item.enElapsed + deltaSeconds),
        )
    }

    /** 听写第一段回车：锁定当前框并切焦点——锁定即终局，另一框可用则聚焦之，否则移交按钮。 */
    fun lockAndShift(session: TestSession): TestSession {
        val item = session.currentItem ?: return session
        val box = session.focusedBox ?: return session
        if (item.part == TestPart.WRITING) return session
        if (boxLocked(item, box) || boxJudged(item, box)) return session
        val locked = session.updateCurrent(if (box == TestBox.ZH) item.copy(zhLocked = true) else item.copy(enLocked = true))
        val other = if (box == TestBox.ZH) TestBox.EN else TestBox.ZH
        val updated = locked.currentItem!!
        val otherUsable = other in updated.part.boxes() && !boxLocked(updated, other) && !boxJudged(updated, other)
        return if (otherUsable) locked.copy(focusedBox = other) else locked.copy(focusedBox = null)
    }

    /**
     * 提交批改（PRD §2.5.2）：听写判两框（留空 = Forget）、默写判英文框；
     * 正确性 + 各框累计焦点计时 → quality → Rating；提交即产出各面 SM-2 记录。
     * 提交后两框锁定（锁定即终局）、焦点移交按钮（下一个），计时冻结。
     */
    fun submit(
        session: TestSession,
        easyThresholdSeconds: Long,
        goodThresholdSeconds: Long,
        now: Instant,
        recordFn: (Long, Facet, Int, Instant) -> LearningRecord,
    ): SubmitOutcome? {
        val item = session.currentItem ?: return null
        if (item.isJudged()) return null
        val boxes = item.part.boxes()
        val outcomes = boxes.map { box ->
            val answer = (if (box == TestBox.ZH) item.zhAnswer else item.enAnswer).trim()
            val elapsed = if (box == TestBox.ZH) item.zhElapsed else item.enElapsed
            val correct = when (box) {
                TestBox.ZH -> AnswerJudge.chinese(item.word.translation, answer)
                TestBox.EN -> AnswerJudge.english(item.word.text, answer)
            }
            val quality = AnswerJudge.qualityFromTiming(correct, elapsed, easyThresholdSeconds, goodThresholdSeconds)
            val facet = testLedgerFacet(item, box)
            // 新学/复习口径实时判定：构建期快照 ∨ 会话内已落账（重考必为 update，不重复计新学）
            val hadRecord = (if (box == TestBox.ZH) item.hadZhRecord else item.hadEnRecord) ||
                session.ledgered.contains(item.word.id to facet)
            PartOutcome(
                wordId = item.word.id,
                facet = facet,
                judgment = PartJudgment(
                    answer = answer,
                    correct = correct,
                    quality = quality,
                    rating = Rating.fromQuality(quality),
                    elapsedSeconds = elapsed,
                    hadRecord = hadRecord,
                    record = recordFn(item.word.id, facet, quality, now),
                ),
            )
        }
        val zhOutcome = outcomes.getOrNull(boxes.indexOf(TestBox.ZH))
        val enOutcome = outcomes.getOrNull(boxes.indexOf(TestBox.EN))
        val updatedItem = item.copy(
            zhLocked = true,
            enLocked = true,
            zhJudgment = zhOutcome?.judgment,
            enJudgment = enOutcome?.judgment,
        )
        val newSession = session.updateCurrent(updatedItem)
            .copy(
                focusedBox = null,
                totalJudgments = session.totalJudgments + outcomes.size,
                ledgered = session.ledgered + outcomes.map { it.wordId to it.facet },
            )
        return SubmitOutcome(newSession, outcomes)
    }

    /** 一次提交的当日统计增量（分母 = 判定数：听写 2、默写 1；Forget = 不正确）。 */
    fun logDelta(outcome: SubmitOutcome): LogDelta = LogDelta(
        newWordsLearned = outcome.outcomes.count { !it.judgment.hadRecord },
        wordsReviewed = outcome.outcomes.count { it.judgment.hadRecord },
        correctJudgments = outcome.outcomes.count { it.judgment.correct },
        totalJudgments = outcome.outcomes.size,
    )

    /** 「下一个」：推进到下一条；目标未批改则聚焦其首可用框，已批改 = 只读回看（焦点在按钮）。 */
    fun next(session: TestSession, now: Instant, random: Random): TestSession {
        val item = session.currentItem ?: return session
        if (!item.isJudged()) return session // 提交前不可跳过（落账完整性）
        if (session.cursor < session.queue.lastIndex) {
            val target = session.queue[session.cursor + 1]
            // 混合边界（PRD §2.5.2）：听写部分轮次循环结束后才进默写——跨界先做听写部内轮末检查
            if (item.part == TestPart.DICTATION && target.part == TestPart.WRITING) {
                val due = session.queue.filter { it.part == TestPart.DICTATION && isDueForget(it, now) }
                if (due.isNotEmpty()) return startRound(session, due, random)
            }
            return session.copy(
                cursor = session.cursor + 1,
                focusedBox = if (target.isJudged()) null else session.defaultBox(target),
            )
        }
        return advance(session, now, random)
    }

    /** 「上一个」：只读回看已批改条目（不重复落账）；未批改条目不可回看。 */
    fun prev(session: TestSession): TestSession {
        val target = session.cursor - 1
        if (target < 0) return session
        if (!session.queue[target].isJudged()) return session
        return session.copy(cursor = target, focusedBox = null)
    }

    /**
     * 轮末检查（PRD §2.5.3，部内口径 §2.5.2）：队列末尾所在部分内的 Forget 面（+60s）到期的词 →
     * **整词重考**（两面同判同落账，作答/锁定/计时/批改全清），洗牌开新轮；无到期面则结束。
     * 已结束的会话不复活。
     */
    fun advance(session: TestSession, now: Instant, random: Random): TestSession {
        if (session.finished || session.queue.isEmpty()) return session.copy(finished = true)
        // 部内检查：队列末尾段 = 最后推进的部分，只查该段的到期面（听写段已在跨界检查处理）
        val tailPart = session.queue.last().part
        val dueItems = session.queue.filter { it.part == tailPart && isDueForget(it, now) }
        if (dueItems.isEmpty()) return session.copy(finished = true)
        return startRound(session, dueItems, random)
    }

    // ---- 私有工具 ----

    /** 该词存在 Forget 且已到期（提交 +60s）的判定面 → 部内重考候选。 */
    private fun isDueForget(item: TestItem, now: Instant): Boolean =
        listOfNotNull(item.zhJudgment, item.enJudgment).any { judgment ->
            judgment.rating == Rating.FORGET && judgment.record.nextReviewTime <= now
        }

    /** 开新轮：到期复活词（洗牌在前）+ 尚未开始的默写词（洗牌在后）——听写段循环完毕才轮到默写。 */
    private fun startRound(session: TestSession, dueItems: List<TestItem>, random: Random): TestSession {
        val revived = dueItems.map { item ->
            item.copy(
                zhAnswer = "", enAnswer = "",
                zhLocked = false, enLocked = false,
                zhElapsed = 0, enElapsed = 0,
                zhJudgment = null, enJudgment = null,
            )
        }.shuffled(random)
        val pendingWriting = session.queue
            .filter { it.part == TestPart.WRITING && !it.isJudged() }
            .shuffled(random)
        val nextQueue = revived + pendingWriting
        val next = session.copy(
            roundNo = session.roundNo + 1,
            queue = nextQueue,
            cursor = 0,
            focusedBox = null,
            finished = false,
        )
        return next.copy(focusedBox = next.defaultBox(nextQueue.first()))
    }

    private fun TestSession.updateCurrent(item: TestItem): TestSession =
        copy(queue = queue.toMutableList().also { it[cursor] = item })

    private fun boxLocked(item: TestItem, box: TestBox): Boolean =
        if (box == TestBox.ZH) item.zhLocked else item.enLocked

    private fun boxJudged(item: TestItem, box: TestBox): Boolean =
        if (box == TestBox.ZH) item.zhJudgment != null else item.enJudgment != null
}

/**
 * 环形计时驱动（PRD §2.5.2 每框独立倒计时圈）：进度比例 + 颜色阶段纯函数。
 * 圈走满一圈 = Good 阈值；Easy 阈值内绿、超 Easy 变黄、走满（≥ Good）变 Hard 橙并冻结。
 */
object RingTimer {
    enum class Stage { GREEN, YELLOW, ORANGE }

    /** 进度比例：elapsed / Good 阈值，上限 1（走满冻结）。 */
    fun progress(elapsedSeconds: Long, goodThresholdSeconds: Long): Float =
        if (goodThresholdSeconds <= 0) 1f
        else minOf(1f, elapsedSeconds / goodThresholdSeconds.toFloat())

    /** 颜色阶段：≤ Easy 绿；Easy < elapsed < Good 黄；≥ Good 橙。 */
    fun stage(elapsedSeconds: Long, easyThresholdSeconds: Long, goodThresholdSeconds: Long): Stage = when {
        elapsedSeconds <= easyThresholdSeconds -> Stage.GREEN
        elapsedSeconds < goodThresholdSeconds -> Stage.YELLOW
        else -> Stage.ORANGE
    }
}
