package cn.vocabu.core.logic

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Rating
import cn.vocabu.core.model.Word
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * 考察会话状态机（ISSUE-009；PRD §2.5）：工作集筛词（只筛词不筛面）、批改编排
 * （留空=Forget、计时评级、账本映射）、两段回车锁定、焦点分别计时、环形计时驱动、
 * 混合编排（先听写后默写）、提交即落账口径、轮末整词重考循环、只读回看导航。
 */
class TestSessionTest {

    private val t0: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun word(id: Long, text: String = "word$id", translation: String = "释义$id") = Word.of(
        text = text, phonetic = null, pos = "n", translation = translation,
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

    /** 测试落账：直接走 SM-2（面从零建账）。 */
    private fun sm2(wordId: Long, facet: Facet, quality: Int, now: Instant): LearningRecord =
        Sm2.update(wordId, facet, null, quality, now)

    // ---- 构建：工作集筛词（PRD §2.5.1 只筛词不筛面）----

    @Test
    fun `构建_听写_听拼或英中缺面到期参与_全未到期排除`() {
        val words = listOf(word(1), word(2), word(3), word(4))
        val records = recordsByWord(
            record(1, Facet.AUDIO_SPELLING, nextReview = t0), // 听拼到期 → 参与
            record(2, Facet.AUDIO_SPELLING, nextReview = t0 + 60.seconds), // 听拼未到期，英中缺 → 参与
            record(3, Facet.ZH2EN, nextReview = t0), // 仅中英有账，听拼/英中缺 → 参与
            record(4, Facet.AUDIO_SPELLING, nextReview = t0 + 60.seconds),
            record(4, Facet.EN2ZH, nextReview = t0 + 60.seconds), // 两面均未到期 → 排除
        )
        val s = TestSessionBuilder.build(words, records, "dictation", Random(1), t0)
        assertEquals(listOf(1L, 2L, 3L), s.queue.map { it.word.id }.sorted())
        assertTrue(s.queue.all { it.part == TestPart.DICTATION })
        assertEquals(TestBox.ZH, s.focusedBox) // 听写初始焦点 = 中文框
        assertFalse(s.finished)
    }

    @Test
    fun `构建_默写_中英面缺或到期参与`() {
        val words = listOf(word(1), word(2))
        val records = recordsByWord(
            record(1, Facet.ZH2EN, nextReview = t0), // 到期 → 参与
            // 2 新词（三面全缺）→ 参与
        )
        val s = TestSessionBuilder.build(words, records, "writing", Random(1), t0)
        assertEquals(listOf(1L, 2L), s.queue.map { it.word.id }.sorted())
        assertTrue(s.queue.all { it.part == TestPart.WRITING })
        assertEquals(TestBox.EN, s.focusedBox)
    }

    @Test
    fun `构建_混合_先听写后默写_新学复习口径按面`() {
        val words = listOf(word(1), word(2))
        val records = recordsByWord(
            record(1, Facet.ZH2EN, nextReview = t0), // 1 仅中英到期 → 只进默写部分
            // 2 新词 → 听写 + 默写两部分都进
        )
        val s = TestSessionBuilder.build(words, records, "mixed", Random(2), t0)
        assertEquals(
            listOf(TestPart.DICTATION, TestPart.DICTATION, TestPart.WRITING, TestPart.WRITING),
            s.queue.map { it.part }, // 部分内洗牌，部分间先听写后默写
        )
        assertEquals(listOf(1L, 2L), s.queue.filter { it.part == TestPart.WRITING }.map { it.word.id }.sorted())
        assertEquals(2, s.queue.count { it.word.id == 2L }) // 新词两部分各一条
        // 新学/复习口径：新词三面无账；词 1 听写部分英中/听拼均无账
        val w2dict = s.queue.first { it.word.id == 2L && it.part == TestPart.DICTATION }
        assertFalse(w2dict.hadZhRecord)
        assertFalse(w2dict.hadEnRecord)
        val w1writing = s.queue.first { it.word.id == 1L && it.part == TestPart.WRITING }
        assertTrue(w1writing.hadEnRecord) // 中英面有账 → 复习
    }

    @Test
    fun `构建_空工作集_直接结束`() {
        val words = listOf(word(1))
        val records = recordsByWord(
            record(1, Facet.AUDIO_SPELLING, nextReview = t0 + 60.seconds),
            record(1, Facet.EN2ZH, nextReview = t0 + 60.seconds),
            record(1, Facet.ZH2EN, nextReview = t0 + 60.seconds),
        )
        val s = TestSessionBuilder.build(words, records, "mixed", Random(1), t0)
        assertTrue(s.finished)
    }

    // ---- 提交批改：留空=Forget、计时评级、账本映射（PRD §2.5.2）----

    @Test
    fun `提交_听写两框_中文对英文空_各面独立评级与账本`() {
        val words = listOf(word(1))
        var s = TestSessionBuilder.build(words, emptyMap(), "dictation", Random(1), t0)
        s = TestSessionOps.type(s, TestBox.ZH, "释义1")
        // 中文框 3 秒（≤Easy 5 → EASY）、英文框留空 → 两面均判
        s = TestSessionOps.tick(s, 3)
        val out = TestSessionOps.submit(s, easyThresholdSeconds = 5, goodThresholdSeconds = 10, now = t0, recordFn = ::sm2)!!
        assertEquals(2, out.outcomes.size)
        val zh = out.outcomes.first { it.facet == Facet.EN2ZH }
        val en = out.outcomes.first { it.facet == Facet.AUDIO_SPELLING }
        assertTrue(zh.judgment.correct)
        assertEquals(Rating.EASY, zh.judgment.rating)
        assertEquals(3, zh.judgment.elapsedSeconds)
        assertFalse(en.judgment.correct) // 留空 = Forget
        assertEquals(Rating.FORGET, en.judgment.rating)
        assertEquals(0, en.judgment.quality)
        // 会话状态：两框锁定、焦点移交按钮
        val item = out.session.currentItem!!
        assertTrue(item.zhLocked && item.enLocked)
        assertNull(out.session.focusedBox)
        assertEquals(2, out.session.totalJudgments)
    }

    @Test
    fun `提交_默写单框_英文评级入中英账`() {
        val words = listOf(word(1, text = "apple"))
        val s0 = TestSessionBuilder.build(words, emptyMap(), "writing", Random(1), t0)
        val s1 = TestSessionOps.type(s0, TestBox.EN, "apple")
        val s2 = TestSessionOps.tick(s1, 2) // 2 秒 ≤ Easy → EASY
        val out = TestSessionOps.submit(s2, 5, 10, t0, ::sm2)!!
        assertEquals(1, out.outcomes.size)
        assertEquals(Facet.ZH2EN, out.outcomes.single().facet)
        assertEquals(Rating.EASY, out.outcomes.single().judgment.rating)
        assertTrue(out.outcomes.single().judgment.correct)
    }

    @Test
    fun `提交_计时评级边界_走满Good判Hard`() {
        val words = listOf(word(1, text = "apple"), word(2, text = "bee"))
        val s0 = TestSessionBuilder.build(words, emptyMap(), "writing", Random(1), t0)
        val s1 = TestSessionOps.type(s0, TestBox.EN, s0.currentItem!!.word.text) // 洗牌序不定，按当前词作答
        val s2 = TestSessionOps.tick(s1, 11) // > Good(10) → HARD
        val out1 = TestSessionOps.submit(s2, 5, 10, t0, ::sm2)!!
        assertEquals(Rating.HARD, out1.outcomes.single().judgment.rating)
        // 第二词：恰好 Good 边界 → GOOD
        val s3 = TestSessionOps.next(out1.session, t0, Random(1))
        val s4 = TestSessionOps.type(s3, TestBox.EN, s3.currentItem!!.word.text)
        val s5 = TestSessionOps.tick(s4, 10)
        val out2 = TestSessionOps.submit(s5, 5, 10, t0, ::sm2)!!
        assertEquals(Rating.GOOD, out2.outcomes.single().judgment.rating)
    }

    @Test
    fun `提交_答错Forget_重复提交无效`() {
        val words = listOf(word(1, text = "apple"))
        val s0 = TestSessionBuilder.build(words, emptyMap(), "writing", Random(1), t0)
        val s1 = TestSessionOps.type(s0, TestBox.EN, "banana")
        val out = TestSessionOps.submit(s1, 5, 10, t0, ::sm2)!!
        assertEquals(Rating.FORGET, out.outcomes.single().judgment.rating)
        assertNull(TestSessionOps.submit(out.session, 5, 10, t0, ::sm2)) // 已批改不可重复提交
    }

    // ---- 两段回车：锁定即终局（PRD §2.5.2）----

    @Test
    fun `两段回车_第一段锁定当前框并切焦点_锁定框拒输入拒聚焦`() {
        val words = listOf(word(1))
        var s = TestSessionBuilder.build(words, emptyMap(), "dictation", Random(1), t0)
        s = TestSessionOps.type(s, TestBox.ZH, "释义1")
        s = TestSessionOps.lockAndShift(s) // 第一段回车
        val item = s.currentItem!!
        assertTrue(item.zhLocked)
        assertEquals(TestBox.EN, s.focusedBox) // 切到英文框
        // 锁定框不可回改、不可聚焦
        assertEquals(item, TestSessionOps.type(s, TestBox.ZH, "改不动").currentItem)
        assertEquals(TestBox.EN, TestSessionOps.focus(s, TestBox.ZH).focusedBox)
        // Tab 只在可用框间循环：focus(EN) 仍可
        assertEquals(TestBox.EN, TestSessionOps.focus(s, TestBox.EN).focusedBox)
    }

    @Test
    fun `默写_中文框不可输入不可聚焦`() {
        val words = listOf(word(1))
        val s = TestSessionBuilder.build(words, emptyMap(), "writing", Random(1), t0)
        assertEquals(s, TestSessionOps.type(s, TestBox.ZH, "x"))
        assertEquals(TestBox.EN, TestSessionOps.focus(s, TestBox.ZH).focusedBox)
    }

    // ---- 焦点分别计时（可切走暂停）----

    @Test
    fun `计时_焦点切换暂停续走_锁定后冻结`() {
        val words = listOf(word(1))
        var s = TestSessionBuilder.build(words, emptyMap(), "dictation", Random(1), t0)
        s = TestSessionOps.tick(s, 2) // 中文框焦点走 2s
        s = TestSessionOps.focus(s, TestBox.EN) // 切走 → 中文暂停
        s = TestSessionOps.tick(s, 5) // 英文框走 5s
        s = TestSessionOps.focus(s, TestBox.ZH)
        s = TestSessionOps.tick(s, 1) // 中文续走 1s
        val item = s.currentItem!!
        assertEquals(3, item.zhElapsed)
        assertEquals(5, item.enElapsed)
        // 锁定后冻结
        s = TestSessionOps.lockAndShift(s) // 锁中文 → 焦点英文
        val after = TestSessionOps.tick(s, 9)
        assertEquals(3, after.currentItem!!.zhElapsed) // 中文已冻结
        assertEquals(14, after.currentItem!!.enElapsed) // 英文继续
        // 未聚焦（焦点在按钮）不计时
        val judged = TestSessionOps.submit(after, 5, 10, t0, ::sm2)!!.session
        assertEquals(after.currentItem!!.enElapsed, judged.currentItem!!.enElapsed)
        assertNull(TestSessionOps.tick(judged, 1).focusedBox)
    }

    // ---- 环形计时驱动 ----

    @Test
    fun `环形计时_比例与颜色阶段_走满冻结`() {
        assertEquals(0.4f, RingTimer.progress(2, 5))
        assertEquals(1f, RingTimer.progress(9, 5)) // 走满冻结
        assertEquals(1f, RingTimer.progress(5, 5))
        assertEquals(RingTimer.Stage.GREEN, RingTimer.stage(5, 5, 10))
        assertEquals(RingTimer.Stage.YELLOW, RingTimer.stage(6, 5, 10))
        assertEquals(RingTimer.Stage.ORANGE, RingTimer.stage(10, 5, 10))
        assertEquals(RingTimer.Stage.ORANGE, RingTimer.stage(99, 5, 10))
    }

    // ---- 落账口径：分母 = 判定数（PRD 1.2 #21）----

    @Test
    fun `落账_听写一次提交计两次_新旧按面_忘记不计正确`() {
        val words = listOf(word(1), word(2))
        val records = recordsByWord(record(2, Facet.EN2ZH, nextReview = t0)) // 词 2 英中面有账
        var s = TestSessionBuilder.build(words, records, "dictation", Random(1), t0)
        // 词 2 在前？洗牌序不定 → 找到词 2 的下标直接 type/tick/submit
        val idx2 = s.queue.indexOfFirst { it.word.id == 2L }
        while (s.cursor != idx2) {
            // 先把前面的词提交掉（快速通过：两框留空）
            s = TestSessionOps.submit(s, 5, 10, t0, ::sm2)!!.session
            s = TestSessionOps.next(s, t0, Random(1))
        }
        s = TestSessionOps.type(s, TestBox.ZH, "释义2")
        val out = TestSessionOps.submit(s, 5, 10, t0, ::sm2)!!
        val delta = TestSessionOps.logDelta(out)
        assertEquals(2, delta.totalJudgments) // 听写一次提交计 2 次
        assertEquals(1, delta.correctJudgments) // 仅中文框对
        // 英中面有账（复习）→ review；听拼面无账（新学）
        assertEquals(1, delta.wordsReviewed)
        assertEquals(1, delta.newWordsLearned)
    }

    @Test
    fun `落账_轮末重考不重复计新学_重考计复习`() {
        var s = TestSessionBuilder.build(listOf(word(1)), emptyMap(), "dictation", Random(1), t0)
        // 第一轮：两框留空全 Forget → 两面均首次创建记录 = 新学
        val out = TestSessionOps.submit(s, 5, 10, t0, ::sm2)!!
        assertEquals(2, TestSessionOps.logDelta(out).newWordsLearned)
        s = out.session
        s = TestSessionOps.next(s, t0, Random(1))
        assertTrue(s.finished) // t0 时刻 Forget(+60s) 未到期
        // 61s 后重查进入新轮（整词重考）→ 重考提交 = update 不是 create
        s = TestSessionOps.advance(s.copy(finished = false), t0 + 61.seconds, Random(1))
        assertEquals(2, s.roundNo)
        s = TestSessionOps.type(s, TestBox.ZH, "释义1")
        s = TestSessionOps.type(s, TestBox.EN, "word1")
        val reOut = TestSessionOps.submit(s, 5, 10, t0 + 61.seconds, ::sm2)!!
        val delta = TestSessionOps.logDelta(reOut)
        assertEquals(0, delta.newWordsLearned) // 重考不重复计新学
        assertEquals(2, delta.wordsReviewed)
        assertEquals(2, delta.totalJudgments)
    }

    // ---- 混合编排与轮末循环（PRD §2.5.3）----

    @Test
    fun `混合编排_听写段循环完毕才进默写_错词先重现`() {
        // 词 1：仅听拼到期 → 只进听写部分；词 2：仅中英到期 → 只进默写部分
        val words = listOf(word(1, text = "apple"), word(2, text = "bee"))
        val records = recordsByWord(
            record(1, Facet.AUDIO_SPELLING, nextReview = t0),
            record(1, Facet.EN2ZH, nextReview = t0 + 3600.seconds),
            record(1, Facet.ZH2EN, nextReview = t0 + 3600.seconds),
            record(2, Facet.AUDIO_SPELLING, nextReview = t0 + 3600.seconds),
            record(2, Facet.EN2ZH, nextReview = t0 + 3600.seconds),
            record(2, Facet.ZH2EN, nextReview = t0),
        )
        var s = TestSessionBuilder.build(words, records, "mixed", Random(3), t0)
        assertEquals(2, s.queue.size)
        assertEquals(TestPart.DICTATION, s.queue.first().part)
        assertEquals(TestPart.WRITING, s.queue.last().part)
        // 听写词答错：两框留空全 Forget
        s = TestSessionOps.submit(s, 5, 10, t0, ::sm2)!!.session
        // 61s 后按「下一个」：跨界检查捞起到期 Forget → 听写错词先重现，默写词排新轮后段
        s = TestSessionOps.next(s, t0 + 61.seconds, Random(1))
        assertEquals(2, s.roundNo)
        assertEquals(2, s.queue.size) // 重考词在前 + 未开始的默写词在后
        assertEquals(1L, s.currentItem!!.word.id)
        assertEquals(TestPart.DICTATION, s.currentItem!!.part)
        assertNull(s.currentItem!!.zhJudgment) // 整词重考：判定清空
        assertEquals(TestBox.ZH, s.focusedBox)
        // 重考答对 → 无感进入默写段
        s = TestSessionOps.type(s, TestBox.ZH, "释义1")
        s = TestSessionOps.type(s, TestBox.EN, "apple")
        s = TestSessionOps.submit(s, 5, 10, t0 + 61.seconds, ::sm2)!!.session
        s = TestSessionOps.next(s, t0 + 61.seconds, Random(1))
        assertEquals(2L, s.currentItem!!.word.id)
        assertEquals(TestPart.WRITING, s.currentItem!!.part)
        assertEquals(TestBox.EN, s.focusedBox)
    }

    @Test
    fun `混合编排_听写耗尽接默写_无遗忘到期则结束`() {
        // 词 1：听拼到期、英中缺 → 只进听写部分；词 2：仅中英到期 → 只进默写部分
        val words = listOf(word(1, text = "apple"), word(2, text = "bee"))
        val records = recordsByWord(
            record(1, Facet.AUDIO_SPELLING, nextReview = t0),
            record(1, Facet.ZH2EN, nextReview = t0 + 3600.seconds),
            record(2, Facet.AUDIO_SPELLING, nextReview = t0 + 3600.seconds),
            record(2, Facet.EN2ZH, nextReview = t0 + 3600.seconds),
            record(2, Facet.ZH2EN, nextReview = t0),
        )
        var s = TestSessionBuilder.build(words, records, "mixed", Random(3), t0)
        assertEquals(2, s.queue.size)
        assertEquals(TestPart.DICTATION, s.queue.first().part) // 先听写
        assertEquals(TestPart.WRITING, s.queue.last().part) // 后默写
        // 听写词：中文对、英文对
        s = TestSessionOps.type(s, TestBox.ZH, s.currentItem!!.word.translation)
        s = TestSessionOps.type(s, TestBox.EN, s.currentItem!!.word.text)
        s = TestSessionOps.submit(s, 5, 10, t0, ::sm2)!!.session
        s = TestSessionOps.next(s, t0, Random(1))
        assertEquals(TestPart.WRITING, s.currentItem!!.part) // 无感衔接默写
        assertEquals(TestBox.EN, s.focusedBox)
        s = TestSessionOps.type(s, TestBox.EN, s.currentItem!!.word.text)
        s = TestSessionOps.submit(s, 5, 10, t0, ::sm2)!!.session
        s = TestSessionOps.next(s, t0, Random(1))
        assertTrue(s.finished) // 无 Forget → 结束
    }

    @Test
    fun `轮末检查_遗忘词到期整词重考_未到期与已结束不复活`() {
        val words = listOf(word(1, text = "apple"), word(2, text = "bee"))
        var s = TestSessionBuilder.build(words, emptyMap(), "dictation", Random(1), t0)
        // 词序：Random(1) 洗牌 → 按队列顺序逐词：词1 忘、词2 对
        val first = s.currentItem!!.word
        s = TestSessionOps.submit(s, 5, 10, t0, ::sm2)!!.session // 两框留空 → 全 Forget
        s = TestSessionOps.next(s, t0, Random(1))
        s = TestSessionOps.type(s, TestBox.ZH, if (first.id == 1L) "释义2" else "释义1")
        s = TestSessionOps.type(s, TestBox.EN, if (first.id == 1L) "bee" else "apple")
        s = TestSessionOps.submit(s, 5, 10, t0, ::sm2)!!.session
        s = TestSessionOps.next(s, t0, Random(1))
        // t0 时刻轮末检查：Forget(+60s) 未到期 → 结束
        assertTrue(s.finished)
        assertEquals(1, s.roundNo)
        // 61s 后重查：遗忘词整词重考（词粒度、两面同判）——重新按到期重查进入新轮
        val recheck = TestSessionOps.advance(s.copy(finished = false), t0 + 61.seconds, Random(1))
        assertEquals(2, recheck.roundNo)
        assertEquals(listOf(first.id), recheck.queue.map { it.word.id })
        val revived = recheck.currentItem!!
        assertTrue(revived.zhAnswer.isEmpty() && revived.enAnswer.isEmpty())
        assertFalse(revived.zhLocked || revived.enLocked)
        assertEquals(0, revived.zhElapsed + revived.enElapsed)
        assertNull(revived.zhJudgment)
        assertNull(revived.enJudgment)
        assertEquals(TestBox.ZH, recheck.focusedBox) // 重考条目重获焦点
    }

    // ---- 导航：只读回看不重复落账（PRD §2.5.2 上一个/下一个）----

    @Test
    fun `导航_只读回看_未批改不可跳过_判定不重复`() {
        val words = listOf(word(1, text = "apple"), word(2, text = "bee"))
        var s = TestSessionBuilder.build(words, emptyMap(), "writing", Random(1), t0)
        // 提交前不可下一个
        assertEquals(0, TestSessionOps.next(s, t0, Random(1)).cursor)
        s = TestSessionOps.type(s, TestBox.EN, "apple")
        s = TestSessionOps.submit(s, 5, 10, t0, ::sm2)!!.session
        val judgmentsBefore = s.currentItem!!.enJudgment
        s = TestSessionOps.next(s, t0, Random(1))
        assertEquals(1, s.cursor)
        assertEquals(1, s.totalJudgments)
        // 回看词 1：只读，焦点在按钮，判定保留
        s = TestSessionOps.prev(s)
        assertEquals(0, s.cursor)
        assertNull(s.focusedBox)
        assertEquals(judgmentsBefore, s.currentItem!!.enJudgment)
        assertEquals(1, s.totalJudgments) // 不重复落账
        // 回看态输入无效（已批改框拒改）
        assertEquals(s, TestSessionOps.type(s, TestBox.EN, "hack"))
        // 越界回看无效
        assertEquals(0, TestSessionOps.prev(s).cursor)
    }

    // ---- 播报计时联动（ISSUE-008 焦点③：听写计时起点 = 播报结束）----

    @Test
    fun `播报hold_计时冻结_放行后起计`() {
        val s0 = TestSessionBuilder.build(listOf(word(1)), emptyMap(), "dictation", Random(1), t0)
        // 词条激活 hold：tick 冻结
        val held = TestSessionOps.holdTiming(s0)
        assertTrue(held.currentItem!!.timingHeld)
        assertEquals(0, TestSessionOps.tick(held, 5).currentItem!!.zhElapsed)
        assertEquals(0, TestSessionOps.tick(held, 5).currentItem!!.enElapsed)
        // 放行（onFinished/onSilent 同构）后正常起计
        val released = TestSessionOps.releaseTiming(held, held.cursor)
        assertFalse(released.currentItem!!.timingHeld)
        assertEquals(2, TestSessionOps.tick(released, 2).currentItem!!.zhElapsed)
    }

    @Test
    fun `播报hold_重复hold幂等_默写不hold`() {
        // 重复 hold 幂等
        val s = TestSessionBuilder.build(listOf(word(1)), emptyMap(), "dictation", Random(1), t0)
        assertEquals(TestSessionOps.holdTiming(s), TestSessionOps.holdTiming(TestSessionOps.holdTiming(s)))
        // 默写词条不参与 hold（replay/批改回放无联动）
        val w = TestSessionBuilder.build(listOf(word(1)), emptyMap(), "writing", Random(1), t0)
        assertEquals(w, TestSessionOps.holdTiming(w))
    }

    @Test
    fun `迟到放行_已判定no_op`() {
        // 手快场景：播报中提交 → 判定落账 → 迟到的放行回调不得改动状态
        var s = TestSessionBuilder.build(listOf(word(1)), emptyMap(), "dictation", Random(1), t0)
        s = TestSessionOps.holdTiming(s)
        s = TestSessionOps.type(s, TestBox.ZH, "释义1")
        s = TestSessionOps.type(s, TestBox.EN, "word1")
        val judged = TestSessionOps.submit(s, 5, 10, t0, ::sm2)!!.session
        val after = TestSessionOps.releaseTiming(judged, judged.cursor)
        assertEquals(judged, after)
    }

    @Test
    fun `迟到放行_不误放新词条hold`() {
        val words = listOf(word(1, text = "apple"), word(2, text = "bee"))
        var s = TestSessionBuilder.build(words, emptyMap(), "dictation", Random(1), t0)
        val cursorA = s.cursor
        s = TestSessionOps.holdTiming(s, cursorA) // 词条 A hold
        // A 播报中提交 → 判定 → 手快翻到 B
        s = TestSessionOps.type(s, TestBox.ZH, "x")
        s = TestSessionOps.type(s, TestBox.EN, "x")
        s = TestSessionOps.submit(s, 5, 10, t0, ::sm2)!!.session
        s = TestSessionOps.next(s, t0, Random(1))
        val cursorB = s.cursor
        assertTrue(cursorB != cursorA)
        s = TestSessionOps.holdTiming(s, cursorB) // B hold（新播报进行中）
        // A 的迟到放行到达：不得放行 B
        s = TestSessionOps.releaseTiming(s, cursorA)
        assertTrue(s.queue[cursorB].timingHeld)
        // B 自己的放行才生效
        s = TestSessionOps.releaseTiming(s, cursorB)
        assertFalse(s.queue[cursorB].timingHeld)
    }

    @Test
    fun `重考_残留hold清理_新轮可正常起计`() {
        // 播报中提交（hold 未放行即判定）→ Forget +60s 整词重考 → 复活条目必须清 hold
        var s = TestSessionBuilder.build(listOf(word(1)), emptyMap(), "dictation", Random(1), t0)
        s = TestSessionOps.holdTiming(s)
        val judged = TestSessionOps.submit(s, 5, 10, t0, ::sm2)!!.session // 两框留空全 Forget
        s = TestSessionOps.next(judged, t0, Random(1))
        assertTrue(s.finished)
        s = TestSessionOps.advance(s.copy(finished = false), t0 + 61.seconds, Random(1))
        assertFalse(s.currentItem!!.timingHeld) // 残留 hold 已清
        assertEquals(1, TestSessionOps.tick(s, 1).currentItem!!.zhElapsed) // 新轮计时正常
    }
}
