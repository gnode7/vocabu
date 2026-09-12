package cn.vocabu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import cn.vocabu.core.logic.BrowseSelection
import cn.vocabu.core.logic.RecallItem
import cn.vocabu.core.logic.RecallSession
import cn.vocabu.core.logic.RecallSessionBuilder
import cn.vocabu.core.logic.RecallSessionOps
import cn.vocabu.core.logic.Sm2
import cn.vocabu.core.logic.TodayListBuilder
import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.Rating
import cn.vocabu.core.model.Word
import cn.vocabu.core.repo.LearningRecordRepository
import cn.vocabu.core.repo.SettingsRepository
import cn.vocabu.core.repo.StudyLogRepository
import cn.vocabu.core.repo.WordRepository
import kotlin.random.Random
import kotlin.time.Instant

/**
 * 回忆会话（ISSUE-006；PRD §2.4）：单一入口按「回忆方向」会话 + 隐藏区偷看 + 四段评级 + SM-2 会话内循环。
 * 纯逻辑在 core（RecallSession 状态机）；本类只做仓储读写与状态推进编排。
 */
class RecallViewModel(
    private val words: WordRepository,
    private val records: LearningRecordRepository,
    private val settings: SettingsRepository,
    private val studyLog: StudyLogRepository,
    /** 播报词条（方向相关脚本在装配层组装，PRD §2.4.2/§2.4.3）。 */
    val speak: (Word, Facet) -> Unit,
    val stopSpeak: () -> Unit = {},
    private val now: () -> Instant,
    /** 本地日期（YYYY-MM-DD），当日统计落账用（PRD §5.3）。 */
    private val today: () -> String,
    private val random: Random = Random.Default,
) {
    /** 会话状态：null = 未开始；空工作集与已完成由 UI 判定展示。 */
    var session by mutableStateOf<RecallSession?>(null)
        private set

    /** 进入回忆屏即组建新会话（工作集按当下时间与设置重算，PRD §2.4.1）。 */
    fun startSession() {
        val s = settings.get()
        val recordsByWord = records.findAll().groupBy { it.wordId }
        val todayList = TodayListBuilder.build(
            words.getAll(), recordsByWord, s.dailyNewWordCount, s.facetCatchUpQuota, now(),
        )
        session = RecallSessionBuilder.build(
            todayList.all, recordsByWord, s.recallDirection, s.recallDisplayCount, random, now(),
        )
    }

    /** 离开会话：取消播报并清空状态（下次进入重新组建）。 */
    fun exit() {
        stopSpeak()
        session = null
    }

    fun speechSettings() = settings.get()

    /**
     * 评级（PRD §2.4.2 难度评级、§2.4.5 SM-2 调度）：
     * SM-2 按分配方向对应考核面即时落库（改评 = 从当前账面再次更新，最终以最后一次为准）；
     * 当日统计增量落账（修订 #19「提交即落账」）；随后推进会话状态（批滑动/轮末循环）。
     */
    fun rate(batchIndex: Int, rating: Rating) {
        val cur = session ?: return
        val globalIndex = cur.batchStart + batchIndex
        val item = cur.queue.getOrNull(globalIndex) ?: return
        val isFirstRating = item.rating == null
        val delta = RecallSessionOps.logDelta(item, isFirstRating, rating)
        val n = now()
        val record = Sm2.update(
            item.word.id, item.facet, records.find(item.word.id, item.facet), rating.quality, n,
        )
        records.upsert(record)
        studyLog.increment(
            today(), delta.newWordsLearned, delta.wordsReviewed, 0L, delta.correctJudgments, delta.totalJudgments,
        )
        session = RecallSessionOps.rate(cur, batchIndex, rating, n, { _, _, _ -> record }, random)
    }

    fun moveSelection(delta: Int) {
        val cur = session ?: return
        val next = BrowseSelection.clamp(cur.selection + delta, cur.batch.size)
        if (next == cur.selection) return
        session = cur.copy(selection = next)
        cur.batch.getOrNull(next)?.let { onSelected(it) }
    }

    fun select(index: Int) {
        val cur = session ?: return
        if (index == cur.selection) return
        session = cur.copy(selection = BrowseSelection.clamp(index, cur.batch.size))
        cur.batch.getOrNull(index)?.let { onSelected(it) }
    }

    /** 选中联动播报（PRD §2.3.3 触发方式与通览相同 + §2.4.3 中→英默认播报）。 */
    private fun onSelected(item: RecallItem) {
        val s = settings.get()
        when {
            item.facet == Facet.EN2ZH && s.autoPlayOnSelect -> speak(item.word, item.facet)
            item.facet == Facet.ZH2EN && s.recallZh2EnAutoPlay -> speak(item.word, item.facet)
        }
    }
}

/** 回忆屏（设计稿屏 2：会话条 + 轮次横幅 + 双列词条卡 + 四段记忆进度条）。 */
@Composable
fun RecallScreen(vm: RecallViewModel, onExit: () -> Unit) {
    // 每次进入组建新会话（PRD §2.4.1：入口即会话，工作集按当下重算）
    LaunchedEffect(Unit) { vm.startSession() }
    // 离开页面取消全部播报（PRD §4.4）
    DisposableEffect(Unit) { onDispose { vm.stopSpeak() } }

    val session = vm.session ?: return

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SessionBar(session, onExit = {
            vm.exit()
            onExit()
        })

        when {
            // 工作集为空：今日该方向无词可回忆（入口计数=0 时进入本屏的兜底）
            session.queue.isEmpty() -> EmptyHint("今日该方向没有需要回忆的词") {
                vm.exit()
                onExit()
            }

            session.finished -> {
                RoundBanner("全部完成 · 本会话共评级 ${session.totalRatingEvents} 次")
                OutlinedButton(
                    onClick = {
                        vm.exit()
                        onExit()
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("返回") }
            }

            else -> RecallList(vm, session)
        }
        Spacer(Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun SessionBar(session: RecallSession, onExit: () -> Unit) {
    val directionLabel = when (session.directionSetting) {
        "en2zh" -> "英→中"
        "zh2en" -> "中→英"
        else -> "混合"
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("回忆巩固", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "$directionLabel · 第 ${session.roundNo} 轮 · 已评 ${session.ratedCount}/${session.queue.size}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onExit) { Text("结束") }
    }
}

@Composable
private fun EmptyHint(message: String, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onExit, modifier = Modifier.padding(top = 12.dp)) { Text("返回") }
    }
}

@Composable
private fun RoundBanner(message: String) {
    Text(
        message,
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

/** 回忆列表：键盘容器 + 表头 + 当前批条目（英左中右固定列位，方向由隐藏侧体现）。 */
@Composable
private fun RecallList(vm: RecallViewModel, session: RecallSession) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 偷看状态：鼠标按住某行隐藏区（行内自持）+ 按住 Q（仅当前选中条目，PRD §2.4.2）
    var pressedPeekGlobal by remember { mutableIntStateOf(-1) }
    var qPeek by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { e ->
                when {
                    e.type == KeyEventType.KeyDown && e.key == Key.Q -> {
                        qPeek = true; true
                    }

                    e.type == KeyEventType.KeyUp && e.key == Key.Q -> {
                        qPeek = false; true
                    }

                    e.type != KeyEventType.KeyDown -> false

                    else -> when (e.key) {
                        Key.DirectionDown -> {
                            vm.moveSelection(1); true
                        }

                        Key.DirectionUp -> {
                            vm.moveSelection(-1); true
                        }

                        Key.One -> {
                            vm.rate(session.selection, Rating.FORGET); true
                        }

                        Key.Two -> {
                            vm.rate(session.selection, Rating.HARD); true
                        }

                        Key.Three -> {
                            vm.rate(session.selection, Rating.GOOD); true
                        }

                        Key.Four -> {
                            vm.rate(session.selection, Rating.EASY); true
                        }

                        else -> false
                    }
                }
            },
    ) {
        MeterHeader()
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            session.batch.forEachIndexed { i, item ->
                val globalIndex = session.batchStart + i
                RecallRow(
                    item = item,
                    rowNo = globalIndex + 1,
                    selected = i == session.selection,
                    revealed = pressedPeekGlobal == globalIndex || (qPeek && i == session.selection),
                    onPeekPress = { pressedPeekGlobal = globalIndex },
                    onPeekRelease = { if (pressedPeekGlobal == globalIndex) pressedPeekGlobal = -1 },
                    onSelect = { vm.select(i) },
                    onSelectAndSpeak = {
                        vm.select(i)
                        vm.speak(item.word, item.facet)
                    },
                    onRate = { r -> vm.rate(i, r) },
                )
            }
            Spacer(Modifier.height(8.dp))
            // 操作提示（设计稿 kbd 条）
            Text(
                "↑ ↓ 选择 · 点击词条播报 · 数字 1-4 评级 · 按住隐藏区或 Q 查看答案 · 评过的词条置灰，可随时改评",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

/** 表头：词条双列 + 「忘 · 熟练程度 · 熟」方向标注（PRD §2.4.2：表头标注，段内无数值）。 */
@Composable
private fun MeterHeader() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp))
        Text(
            "词条",
            Modifier.weight(1.4f).padding(horizontal = 11.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        Text("", Modifier.weight(1.6f), fontSize = 12.sp)
        Text(
            "忘 · 熟练程度 · 熟",
            Modifier.width(104.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

/** 回忆条目行：英左/中右列位固定；隐藏侧即偷看区；右侧四段评级（PRD §2.4.2/§2.4.4）。 */
@Composable
private fun RecallRow(
    item: RecallItem,
    rowNo: Int,
    selected: Boolean,
    revealed: Boolean,
    onPeekPress: () -> Unit,
    onPeekRelease: () -> Unit,
    onSelect: () -> Unit,
    onSelectAndSpeak: () -> Unit,
    onRate: (Rating) -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    // 鼠标悬停 = 选中（PRD §2.4.2 选择与通览相同；Q 偷看作用于当前选中条目）
    LaunchedEffect(hovered) { if (hovered) onSelect() }
    val rated = item.rating != null
    val highlight = selected || hovered
    val bg = if (highlight) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f) else MaterialTheme.colorScheme.surface
    val contentColor = if (rated) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f) else Color.Unspecified

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(bg)
            .hoverable(hoverInteraction)
            .clickable(onClick = onSelectAndSpeak),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中指示条（同通览 .t-row.selected）
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent),
        )
        Row(
            Modifier.weight(1f).padding(horizontal = 11.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                rowNo.toString().padStart(2, '0'),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(26.dp),
            )
            val enModifier = Modifier.weight(1.4f)
            val zhModifier = Modifier.weight(1.6f)
            when (item.facet) {
                // 英→中：展示英文，隐藏中文（右列 = 偷看区）
                Facet.EN2ZH -> {
                    Text(
                        item.word.text,
                        modifier = enModifier,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                    )
                    PeekCell(
                        text = item.word.translation,
                        revealed = revealed,
                        hovered = hovered,
                        contentColor = contentColor,
                        modifier = zhModifier,
                        onPeekPress = onPeekPress,
                        onPeekRelease = onPeekRelease,
                    )
                }
                // 中→英：展示中文，隐藏英文（左列 = 偷看区）
                Facet.ZH2EN -> {
                    PeekCell(
                        text = item.word.text,
                        revealed = revealed,
                        hovered = hovered,
                        contentColor = contentColor,
                        modifier = enModifier,
                        onPeekPress = onPeekPress,
                        onPeekRelease = onPeekRelease,
                    )
                    Text(
                        item.word.translation,
                        modifier = zhModifier,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 听拼面不参与回忆（ADR 0004/0006）
                Facet.AUDIO_SPELLING -> Unit
            }
            RatingMeter(rating = item.rating, onRate = onRate)
        }
    }
}

/**
 * 偷看区：被隐藏的一侧。按住显示内容、松开恢复；悬停显示「偷看 Q」提示（PRD §2.4.2）。
 * 偷看不影响 SM-2（不计入回忆）。
 */
@Composable
private fun PeekCell(
    text: String,
    revealed: Boolean,
    hovered: Boolean,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onPeekPress: () -> Unit,
    onPeekRelease: () -> Unit,
) {
    Box(
        modifier
            .then(
                if (revealed) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onPeekPress()
                                try {
                                    awaitRelease()
                                } finally {
                                    onPeekRelease()
                                }
                            },
                        )
                    }
                },
            ),
    ) {
        if (revealed) {
            Text(
                text,
                fontSize = 15.sp,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            // 隐藏态：＊＊＊＊＊＊ 灰字遮罩 + 悬停「偷看 Q」提示（设计稿 .hidden-side）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "＊＊＊＊＊＊",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (hovered) {
                    Text(
                        " 偷看 Q",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/** 评级色（PRD §2.4.2：红=忘 / 橘=困难 / 黄=记得 / 绿=熟练）。 */
private fun ratingColor(rating: Rating): Color = when (rating) {
    Rating.FORGET -> Color(0xFFDD5A4C)
    Rating.HARD -> Color(0xFFF0993E)
    Rating.GOOD -> Color(0xFFE4C441)
    Rating.EASY -> Color(0xFF59B96A)
}

private val RATING_ORDER = listOf(Rating.FORGET, Rating.HARD, Rating.GOOD, Rating.EASY)

private val RATING_TIP = mapOf(
    Rating.FORGET to "忘记 [1]",
    Rating.HARD to "困难 [2]",
    Rating.GOOD to "记得 [3]",
    Rating.EASY to "熟练 [4]",
)

/**
 * 四段式记忆进度条（PRD §2.4.2）：悬停第 N 段预览点亮（按该段评级色）、按下固定评级
 * → 前 N 段按固定评级的颜色填充；悬停显示「忘记[1]…熟练[4]」tooltip（含快捷键）。
 */
@Composable
private fun RatingMeter(rating: Rating?, onRate: (Rating) -> Unit) {
    var hoverIndex by remember { mutableIntStateOf(-1) }

    Box(
        Modifier
            .width(104.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .padding(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            RATING_ORDER.forEachIndexed { i, r ->
                val segmentInteraction = remember { MutableInteractionSource() }
                val segmentHovered by segmentInteraction.collectIsHoveredAsState()
                LaunchedEffect(segmentHovered) { hoverIndex = if (segmentHovered) i else -1 }

                // 点亮规则：悬停预览点亮 1..N（按悬停段评级色）；否则已评评级色点亮
                val litCount = if (hoverIndex >= 0) hoverIndex + 1 else rating?.let { RATING_ORDER.indexOf(it) + 1 } ?: 0
                val color = when {
                    hoverIndex >= 0 && i <= hoverIndex -> ratingColor(RATING_ORDER[hoverIndex])
                    hoverIndex < 0 && rating != null && i < litCount -> ratingColor(rating)
                    else -> Color.Transparent
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(12.dp)
                        .background(color, RoundedCornerShape(3.dp))
                        .hoverable(segmentInteraction)
                        .clickable(
                            interactionSource = segmentInteraction,
                            indication = null,
                        ) { onRate(r) },
                )
            }
        }
        // 悬停 tooltip：段位名称 + 键帽快捷键（PRD §2.4.2）
        if (hoverIndex >= 0) {
            Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -30)) {
                Text(
                    RATING_TIP.getValue(RATING_ORDER[hoverIndex]),
                    Modifier
                        .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}
