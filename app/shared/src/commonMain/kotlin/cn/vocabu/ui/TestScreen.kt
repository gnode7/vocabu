package cn.vocabu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.delay
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.vocabu.core.logic.PartJudgment
import cn.vocabu.core.model.Rating
import cn.vocabu.core.logic.RingTimer
import cn.vocabu.core.logic.SpeechScriptBuilder
import cn.vocabu.core.logic.SpeechSegment
import cn.vocabu.core.logic.Sm2
import cn.vocabu.core.logic.TestBox
import cn.vocabu.core.logic.TestPart
import cn.vocabu.core.logic.TestSession
import cn.vocabu.core.logic.TestSessionBuilder
import cn.vocabu.core.logic.TestSessionOps
import cn.vocabu.core.model.AppSettings
import cn.vocabu.core.model.Word
import cn.vocabu.core.repo.LearningRecordRepository
import cn.vocabu.core.repo.SettingsRepository
import cn.vocabu.core.repo.StudyLogRepository
import cn.vocabu.core.repo.WordRepository
import kotlin.random.Random
import kotlin.time.Instant

/** 批改回放延迟：告警音先响，回放随后（ISSUE-008 焦点⑤）。 */
private const val CORRECTION_REPLAY_DELAY_MS = 200L

/**
 * 考察会话（ISSUE-009；PRD §2.5）：单一入口按「考察方式」执行——
 * 听写（播报读音 → 英文/中文两框作答（2026-09-15 口径：英文框在上）、两段回车、每框独立倒计时圈、批改徽章/红框/右侧正确答案/告警音）
 * 与默写（中文释义 → 英文单框）+ 混合编排（先听写后默写）。
 * 提交即落账（SM-2 各面 + 当日统计，分母 = 判定数）；Forget +60s 轮末整词重考。
 * 纯逻辑在 core（TestSession 状态机）；本类只做仓储读写、播报与状态推进编排。
 */
class TestViewModel(
    private val words: WordRepository,
    private val records: LearningRecordRepository,
    private val settings: SettingsRepository,
    private val studyLog: StudyLogRepository,
    /** 播报脚本由 VM 组装，装配层注入播放（ISSUE-008 焦点③④：startDelay + 完成回调接缝）。 */
    val speak: (List<SpeechSegment>, Long, () -> Unit, () -> Unit) -> Unit,
    val stopSpeak: () -> Unit = {},
    /** 短促告警音接缝（真实音效 ISSUE-008 装配；Fake 阶段静默）。 */
    val errorCue: () -> Unit = {},
    private val now: () -> Instant,
    private val today: () -> String,
    private val random: Random = Random.Default,
) {
    /** 会话状态：null = 未开始；空工作集与已完成由 UI 判定展示。 */
    var session by mutableStateOf<TestSession?>(null)
        private set

    fun speechSettings(): AppSettings = settings.get()

    /** 进入考察屏即组建新会话（工作集按当下时间与设置重算，PRD §2.5.1）。 */
    fun startSession() {
        val s = settings.get()
        val recordsByWord = records.findAll().groupBy { it.wordId }
        val todayList = cn.vocabu.core.logic.TodayListBuilder.build(
            words.getAll(), recordsByWord, s.dailyNewWordCount, s.facetCatchUpQuota, now(),
        )
        val built = TestSessionBuilder.build(todayList.all, recordsByWord, s.testMode, random, now())
        session = built
        speakForItem(built)
    }

    /** 离开会话：取消播报并清空状态（下次进入重新组建）。 */
    fun exit() {
        stopSpeak()
        session = null
    }

    /** 考察阈值（Easy/Good，秒）供环形圈绘制。 */
    fun thresholds(): Pair<Long, Long> =
        settings.get().let { it.dictationEasyThreshold.toLong() to it.dictationGoodThreshold.toLong() }

    fun type(box: TestBox, text: String) {
        session = session?.let { TestSessionOps.type(it, box, text) }
    }

    fun focusBox(box: TestBox) {
        session = session?.let { TestSessionOps.focus(it, box) }
    }

    /** Tab 捕获：仅在可用输入框间循环（锁定/批改框跳过；默写无第二框）。 */
    fun tabNext() {
        val cur = session?.currentItem ?: return
        if (cur.part == TestPart.WRITING) return
        val target = when (session?.focusedBox) {
            TestBox.ZH -> TestBox.EN
            else -> TestBox.ZH
        }
        focusBox(target)
    }

    /**
     * 回车（修订 #10）：已批改 → 触发「下一个」；听写未锁定任何框 → 第一段（锁定+切焦点）；
     * 听写已有锁定框 → 提交批改；默写 → 直接提交。
     */
    fun onEnter() {
        val s = session ?: return
        val item = s.currentItem ?: return
        if (item.zhJudgment != null || item.enJudgment != null) {
            next()
        } else if (item.part == TestPart.WRITING || item.zhLocked || item.enLocked) {
            submit()
        } else {
            session = TestSessionOps.lockAndShift(s)
        }
    }

    /**
     * 提交批改（PRD §2.5.2）：逐面 SM-2 落库 + 当日统计增量（分母 = 判定数）；
     * 有错播告警音 + 自动回放（correctionReplay 开关，修订 #11）。
     */
    fun submit() {
        val s = session ?: return
        val st = settings.get()
        val out = TestSessionOps.submit(
            s, st.dictationEasyThreshold.toLong(), st.dictationGoodThreshold.toLong(), now(), ::rateRecord,
        ) ?: return
        session = out.session
        out.outcomes.forEach { records.upsert(it.judgment.record) }
        val delta = TestSessionOps.logDelta(out)
        studyLog.increment(
            today(),
            newWordsLearned = delta.newWordsLearned,
            wordsReviewed = delta.wordsReviewed,
            correctJudgments = delta.correctJudgments,
            totalJudgments = delta.totalJudgments,
        )
        val wrong = out.outcomes.filter { !it.judgment.correct }
        if (wrong.isNotEmpty()) {
            errorCue()
            if (st.correctionReplay) {
                // 提交时当前词即被批改词（submit 不推进 cursor）；
                // 告警音在前，回放 +200ms 延迟避免重叠（ISSUE-008 焦点⑤）。
                // 回放必须带放行闭包（2026-09-15 用户反馈②顺带，同 replayDictation 教训）：
                // 听写读音播放中提交（hold 中）时，回放 speak 顶掉原播报会经 clear 撤走原放行回调，
                // 传空回调将泄漏 timingHeld 致倒计时圈停走；releaseTiming 幂等三不兜底，
                // 批改后/非 hold 情形为 no-op，语义仍在「不新增 hold」框架内。
                val cursor = s.cursor
                speak(
                    SpeechScriptBuilder.buildCorrectionReplay(s.currentItem!!.word, wrong.map { it.facet }, st),
                    CORRECTION_REPLAY_DELAY_MS,
                    onFinished@ { session = session?.let { TestSessionOps.releaseTiming(it, cursor) } },
                    onSilent@ { session = session?.let { TestSessionOps.releaseTiming(it, cursor) } },
                )
            }
        }
    }

    /** 「下一个」：推进；越过末条做轮末检查（整词重考/结束）。到未判听写词自动播报。 */
    fun next() {
        val s = session ?: return
        val n = TestSessionOps.next(s, now(), random)
        session = n
        speakForItem(n)
    }

    /** 「上一个」：只读回看（不重播报、不重复落账）。 */
    fun prev() {
        session = session?.let { TestSessionOps.prev(it) }
    }

    /** 焦点注意力计时心跳（UI 1s 驱动；焦点在哪个框哪个框累计）。 */
    fun tick() {
        session = session?.let { TestSessionOps.tick(it, 1) }
    }

    /**
     * 手动重听（听写词条播报读音；复审 0009 必修项）：重听顶掉原播报会经 clear 撤走原放行回调，
     * 故此处补同 cursor 的放行闭包——releaseTiming 幂等且三不（未 hold 时 no-op，core 单测兜底），
     * 防 timingHeld 泄漏到判定致倒计时圈停走；语义仍在「重听不新增 hold」框架内。
     */
    fun replayDictation() {
        val s = session ?: return
        val item = s.currentItem ?: return
        if (item.part == TestPart.DICTATION && item.zhJudgment == null && item.enJudgment == null) {
            val cursor = s.cursor
            speak(
                SpeechScriptBuilder.buildDictation(item.word), 0,
                onFinished@ { session = session?.let { TestSessionOps.releaseTiming(it, cursor) } },
                onSilent@ { session = session?.let { TestSessionOps.releaseTiming(it, cursor) } },
            )
        }
    }

    /**
     * 听写词条激活自动播报读音（已批改回看/默写词条不播）。
     * 计时联动（ISSUE-008 焦点③，PRD §4.4）：先 hold 冻结计时 → 播报结束（onFinished）
     * 或播报未发生（onSilent：空脚本/全部失败）才放行起计。回调闭包捕获发起词条的
     * cursor，放行时由 core 校验目标仍 hold 且未判定——迟到放行 no-op 三不。
     */
    private fun speakForItem(s: TestSession) {
        val item = s.currentItem ?: return
        if (item.part == TestPart.DICTATION && item.zhJudgment == null && item.enJudgment == null) {
            val cursor = s.cursor
            session = session?.let { TestSessionOps.holdTiming(it, cursor) }
            speak(
                SpeechScriptBuilder.buildDictation(item.word), 0,
                onFinished@ { session = session?.let { TestSessionOps.releaseTiming(it, cursor) } },
                onSilent@ { session = session?.let { TestSessionOps.releaseTiming(it, cursor) } },
            )
        }
    }

    /** 逐面 SM-2：从当前账面再次更新（改评/重考沿用既有账）。 */
    private fun rateRecord(wordId: Long, facet: cn.vocabu.core.model.Facet, quality: Int, at: Instant) =
        Sm2.update(
            wordId, facet,
            records.findAll().firstOrNull { it.wordId == wordId && it.facet == facet },
            quality, at,
        )
}

/** 考察屏（PRD §2.5：听写/默写双形态 + 混合编排 + 轮次横幅）。 */
@Composable
fun TestScreen(vm: TestViewModel, onExit: () -> Unit) {
    LaunchedEffect(Unit) { vm.startSession() }
    DisposableEffect(Unit) { onDispose { vm.exit() } }

    val s = vm.session
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            if (s == null) {
                Spacer(Modifier.weight(1f))
            } else if (s.queue.isEmpty()) {
                EmptyTestPanel(onExit)
            } else if (s.finished) {
                FinishedTestPanel(vm, onExit)
            } else {
                // 计时心跳：按词驱动；焦点移交按钮（批改后）自动停
                LaunchedEffect(s.cursor) {
                    while (true) {
                        delay(1000)
                        val cur = vm.session ?: break
                        if (cur.finished || cur.focusedBox == null) break
                        vm.tick()
                    }
                }
                TestMeter(vm, s)
                when (s.currentItem!!.part) {
                    TestPart.DICTATION -> DictationPanel(vm, s)
                    TestPart.WRITING -> WritingPanel(vm, s)
                }
            }
        }
    }
}

/** 会话横幅：轮次 · 部分序号 · 判定完成数 · 考察方式。 */
@Composable
private fun TestMeter(vm: TestViewModel, s: TestSession) {
    val judged = s.judgedWords // 口径统一走 TestSession.isJudged()（审查顺手项 #1）
    val modeLabel = when (vm.speechSettings().testMode) {
        "dictation" -> "听写"
        "writing" -> "默写"
        else -> "混合"
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "第 ${s.roundNo} 轮",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "${s.cursor + 1}/${s.queue.size} · 已判定 $judged",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.weight(1f))
        Text(modeLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
    }
}

/** 听写面板：播报区 + 英文/中文双框（2026-09-15 用户口径：英文在上、中文在下；独立倒计时圈 + 批改徽章 + 右侧正确答案 + 评级条）。 */
@Composable
private fun DictationPanel(vm: TestViewModel, s: TestSession) {
    val item = s.currentItem!!
    val (easy, good) = vm.thresholds()
    val zhFocus = remember { FocusRequester() }
    val enFocus = remember { FocusRequester() }

    // 焦点状态以 VM 为准：锁定/批改/回看时把系统焦点拉回对应框（或让开）
    LaunchedEffect(s.cursor, s.focusedBox) {
        when (s.focusedBox) {
            TestBox.ZH -> zhFocus.requestFocus()
            TestBox.EN -> enFocus.requestFocus()
            null -> Unit
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 播报区（PRD §2.5.2：听写只播读音；🔊 手动重听）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔊", fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Text("听写：请听写该词的英文拼写与中文释义", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.replayDictation() }) { Text("重听") }
        }
        TestAnswerBox(
            vm = vm,
            box = TestBox.EN,
            item = item,
            shown = item.enAnswer,
            onText = { vm.type(TestBox.EN, it) },
            hint = "英文拼写",
            correctAnswer = item.word.text,
            judgment = item.enJudgment,
            elapsed = item.enElapsed,
            easy = easy,
            good = good,
            focusRequester = enFocus,
        )
        TestAnswerBox(
            vm = vm,
            box = TestBox.ZH,
            item = item,
            shown = item.zhAnswer,
            onText = { vm.type(TestBox.ZH, it) },
            hint = "中文释义",
            correctAnswer = item.word.translation,
            judgment = item.zhJudgment,
            elapsed = item.zhElapsed,
            easy = easy,
            good = good,
            focusRequester = zhFocus,
        )
        TestFooter(vm, item)
    }
}

/** 默写面板：中文释义展示 + 英文单框（PRD §2.5.3：无播报、回车即提交）。 */
@Composable
private fun WritingPanel(vm: TestViewModel, s: TestSession) {
    val item = s.currentItem!!
    val (easy, good) = vm.thresholds()
    val enFocus = remember { FocusRequester() }

    LaunchedEffect(s.cursor, s.focusedBox) {
        if (s.focusedBox == TestBox.EN) enFocus.requestFocus()
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("默写：写出该释义对应的英文", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
        Text(
            item.word.translation,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start,
        )
        // 词性提示（PRD §2.5.3 流程1：展示中文翻译与词性；ADR-0007 同文本不同词性是不同词条）
        if (item.word.pos.isNotBlank()) {
            Text(item.word.pos, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
        TestAnswerBox(
            vm = vm,
            box = TestBox.EN,
            item = item,
            shown = item.enAnswer,
            onText = { vm.type(TestBox.EN, it) },
            hint = "英文拼写",
            correctAnswer = item.word.text,
            judgment = item.enJudgment,
            elapsed = item.enElapsed,
            easy = easy,
            good = good,
            focusRequester = enFocus,
        )
        TestFooter(vm, item)
    }
}

/**
 * 单框作答行：输入框 + 右侧倒计时圈/批改徽章 + 正确答案槽位（PRD §2.5.2/修订 #11）。
 * 答对绿底；答错红底红边框 + 正确答案深色文字在右侧固定槽位；框内不显示勾叉。
 */
@Composable
private fun TestAnswerBox(
    vm: TestViewModel,
    box: TestBox,
    item: cn.vocabu.core.logic.TestItem,
    shown: String,
    onText: (String) -> Unit,
    hint: String,
    correctAnswer: String,
    judgment: PartJudgment?,
    elapsed: Long,
    easy: Long,
    good: Long,
    focusRequester: FocusRequester,
) {
    val judged = judgment != null
    val locked = if (box == TestBox.ZH) item.zhLocked else item.enLocked
    val borderColor = when {
        judged && judgment!!.correct -> ratingColor(Rating.EASY)
        judged -> ratingColor(Rating.FORGET)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val bg = when {
        judged && judgment!!.correct -> ratingColor(Rating.EASY).copy(alpha = 0.12f)
        judged -> ratingColor(Rating.FORGET).copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surface
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = shown,
            onValueChange = onText,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) vm.focusBox(box) }
                .onPreviewKeyEvent { e ->
                    when {
                        e.type == KeyEventType.KeyDown && e.key == Key.Tab -> {
                            vm.tabNext(); true
                        }
                        e.type == KeyEventType.KeyUp && (e.key == Key.Enter || e.key == Key.NumPadEnter) -> {
                            vm.onEnter(); true
                        }
                        else -> false
                    }
                },
            enabled = !judged,
            readOnly = locked || judged, // 锁定即终局（提交前不可回改）
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = bg,
                unfocusedContainerColor = bg,
                focusedBorderColor = borderColor,
                unfocusedBorderColor = borderColor,
                disabledContainerColor = bg,
                disabledBorderColor = borderColor,
            ),
        )
        // 右侧固定槽位：计时圈/批改徽章 + 答错时的正确答案
        RingBadge(elapsed, easy, good, judgment)
        if (judged && !judgment!!.correct) {
            Text(
                correctAnswer,
                Modifier.width(96.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Spacer(Modifier.width(96.dp))
        }
    }
    // 各部分评级条（评级 = 该部分累计耗时所定，无共用词级条）
    RatingStrip(if (judged) judgment!!.rating else null)
}

/** 倒计时圈（未批改：进度 + 颜色阶段）→ 批改徽章（对号绿/红叉红）。 */
@Composable
private fun RingBadge(elapsed: Long, easy: Long, good: Long, judgment: PartJudgment?) {
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        val j = judgment
        if (j == null) {
            val color = when (RingTimer.stage(elapsed, easy, good)) {
                RingTimer.Stage.GREEN -> ratingColor(Rating.EASY)
                RingTimer.Stage.YELLOW -> ratingColor(Rating.GOOD)
                RingTimer.Stage.ORANGE -> ratingColor(Rating.HARD)
            }
            Canvas(Modifier.size(30.dp)) {
                // 背景圈
                drawCircle(color = Color(0x22000000), radius = size.minDimension / 2 - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
                // 进度弧（从顶部顺时针；走满冻结在 1）
                val sweep = 360f * RingTimer.progress(elapsed, good)
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        } else {
            // 对号绿底 / 红叉红底（PRD §2.5.2 批改徽章）
            val badgeColor = if (j.correct) ratingColor(Rating.EASY) else ratingColor(Rating.FORGET)
            Box(
                Modifier
                    .size(26.dp)
                    .background(badgeColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (j.correct) "✓" else "✗", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 只读四段评级条（考察：评级由该面累计耗时所定，不可手改）。 */
@Composable
private fun RatingStrip(rating: Rating?) {
    Row(
        Modifier
            .width(104.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RATING_ORDER.forEach { r ->
            val lit = rating != null && RATING_ORDER.indexOf(r) <= RATING_ORDER.indexOf(rating)
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .background(if (lit) ratingColor(rating) else Color.Transparent, RoundedCornerShape(3.dp)),
            )
        }
    }
}

/** 底部操作区：上一个 / 提交（判定后变「下一个」，回车同效）。按钮不进 Tab 链（PRD §2.5.2）。 */
@Composable
private fun TestFooter(vm: TestViewModel, item: cn.vocabu.core.logic.TestItem) {
    val judged = item.zhJudgment != null || item.enJudgment != null
    val blockTab = Modifier.onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.Tab }
    val nextFocus = remember { FocusRequester() }
    // 批改后焦点移交「下一个」按钮（兑现 core submit/prev 置 focusedBox=null 的设计）：
    // 输入框 disabled 释放焦点，焦点落按钮后 Enter 走 onClick = next()
    LaunchedEffect(judged) { if (judged) nextFocus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { vm.prev() }, enabled = vm.session?.cursor ?: 0 > 0, modifier = blockTab) { Text("上一个") }
        Spacer(Modifier.weight(1f))
        Button(onClick = { if (judged) vm.next() else vm.submit() }, modifier = blockTab.focusRequester(nextFocus)) {
            Text(if (judged) "下一个" else "提交")
        }
    }
}

/** 空工作集：今日无可考察词条（PRD §2.5.1 工作集为空）。 */
@Composable
private fun EmptyTestPanel(onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("今日无可考察词条", fontSize = 15.sp, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onExit) { Text("返回通览") }
    }
}

/** 会话完成：全部判定且无到期面（PRD §2.5.3 循环结束）。 */
@Composable
private fun FinishedTestPanel(vm: TestViewModel, onExit: () -> Unit) {
    val s = vm.session!!
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("考察完成", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("共 ${s.distinctWordCount} 词 · ${s.totalJudgments} 次判定", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onExit) { Text("返回通览") }
    }
}
