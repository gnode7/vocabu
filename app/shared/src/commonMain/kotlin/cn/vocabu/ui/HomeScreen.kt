package cn.vocabu.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.vocabu.core.logic.BrowseSelection
import cn.vocabu.core.logic.TodayListBuilder
import cn.vocabu.core.model.AppSettings
import cn.vocabu.core.model.Word
import cn.vocabu.core.repo.LearningRecordRepository
import cn.vocabu.core.repo.SettingsRepository
import cn.vocabu.core.repo.WordRepository
import kotlin.time.Instant

/** 通览（首页，ISSUE-004/005）：今日词表全量分三组展示 + 选择 + 播报联动。 */
class HomeViewModel(
    private val words: WordRepository,
    private val records: LearningRecordRepository,
    private val settings: SettingsRepository,
    /** 播报词条（脚本组装在装配层，ISSUE-005；真实 TTS 在 ISSUE-008）。 */
    val speak: (Word) -> Unit,
    /** 离开页面取消全部播报（PRD §4.4）。 */
    val stopSpeak: () -> Unit = {},
    private val now: () -> Instant,
) {
    /** 数据版本：数据或设置变化后自增，驱动今日词表与计数重算。 */
    var version by mutableStateOf(0)
        private set

    fun bump() {
        version++
    }

    private fun recordsByWord(): Map<Long, List<cn.vocabu.core.model.LearningRecord>> =
        records.findAll().groupBy { it.wordId }

    /** 今日词表（新词/复习/补查三组，PRD §2.2.2）。 */
    fun todayList(): TodayListBuilder.TodayList {
        val s = settings.get()
        return TodayListBuilder.build(words.getAll(), recordsByWord(), s.dailyNewWordCount, s.facetCatchUpQuota, now())
    }

    /** 播报相关设置快照（事件时刻读取，选中自动播报等，PRD §2.3.3）。 */
    fun speechSettings(): AppSettings = settings.get()

    /** 回忆入口计数 = 当前回忆方向的工作集词数（PRD §2.4.1）。 */
    fun recallCount(): Int {
        val s = settings.get()
        return cn.vocabu.core.logic.EntryCounter.recallCount(words.getAll(), recordsByWord(), s.recallDirection, now())
    }

    /** 考察入口计数 = 当前考察方式的并集工作集词数（PRD §2.5.1）。 */
    fun testCount(): Int {
        val s = settings.get()
        return cn.vocabu.core.logic.EntryCounter.testCount(words.getAll(), recordsByWord(), s.testMode, now())
    }
}

/** 通览首页（设计稿屏 1：今日 N 词头 + 新词/复习/补查三组折叠列表 + 选中与播报联动）。 */
@Composable
fun HomeScreen(vm: HomeViewModel) {
    // remember(version)：数据/设置变化后重算今日词表
    val today = remember(vm.version) { vm.todayList() }

    // 分组展开状态：新词默认展开，复习/补查默认收拢（PRD §2.3.1）
    var expanded by remember { mutableStateOf(listOf(true, false, false)) }
    val groups = remember(today) { listOf(today.newWords, today.reviewWords, today.catchUpWords) }
    // 可见条目 = 展开分组的条目平铺（PRD §2.3.2：收拢分组内条目不参与选择）
    val visible = remember(groups, expanded) { BrowseSelection.visibleEntries(groups, expanded) }

    var selectedIndex by remember { mutableStateOf(0) }
    // 分组收拢/展开后钳制选中下标，避免指向隐藏条目
    LaunchedEffect(visible) { selectedIndex = BrowseSelection.clamp(selectedIndex, visible.size) }

    // 播报状态：触发序号 + 播报内容描述（如「读音 → 字母拼写」）
    var playKey by remember { mutableStateOf(0) }
    var playingWordId by remember { mutableStateOf<Long?>(null) }
    var playingLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playKey) {
        if (playKey > 0) {
            kotlinx.coroutines.delay(2_200)
            playingWordId = null
            playingLabel = null
        }
    }

    // 离开页面取消全部播报（PRD §4.4）
    DisposableEffect(Unit) { onDispose { vm.stopSpeak() } }

    // 行号 = 今日词表全量序（设计稿 .t-idx 跨组连续编号）
    val absIndexById = remember(today) { today.all.withIndex().associate { it.value.id to it.index } }

    fun showPlaying(word: Word) {
        val s = vm.speechSettings()
        playingLabel = if (word.isPhrase) {
            listOfNotNull(
                if (s.phrasePlayPronunciation) "读音" else null,
                if (s.phrasePlayTranslation) "中文翻译" else null,
            ).joinToString(" → ").ifEmpty { null }
        } else {
            listOfNotNull(
                if (s.wordPlayPronunciation) "读音" else null,
                if (s.wordPlaySpelling) "字母拼写" else null,
            ).joinToString(" → ").ifEmpty { null }
        }
        playingWordId = word.id
        playKey++
    }

    fun speakWord(word: Word) {
        vm.speak(word)
        showPlaying(word)
    }

    // 键盘选中切换：开启「选中时自动播报」时触发（PRD §2.3.3、§4.4 切换取消前一个）
    fun moveSelection(delta: Int) {
        val next = BrowseSelection.clamp(selectedIndex + delta, visible.size)
        if (next == selectedIndex) return
        selectedIndex = next
        val word = visible.getOrNull(next)
        if (word != null && vm.speechSettings().autoPlayOnSelect) speakWord(word)
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            // 键盘：↑↓ 在可见条目间移动，空格播报当前选中（PRD §2.3.2/§2.3.3）
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> {
                        moveSelection(1); true
                    }
                    Key.DirectionUp -> {
                        moveSelection(-1); true
                    }
                    Key.Spacebar -> {
                        visible.getOrNull(selectedIndex)?.let { speakWord(it) }; true
                    }
                    else -> false
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // 今日 N 词头（设计稿 .today-sum）+ 播报中提示
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("今日 ${today.all.size} 词", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                "新词 ${today.newWords.size} · 复习 ${today.reviewWords.size} · 补查 ${today.catchUpWords.size}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline(),
            )
            playingLabel?.let {
                Text(
                    "播报中：$it",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }

        GroupSection(
            title = "新词",
            entries = today.newWords,
            tag = TagStyle.New,
            expanded = expanded[0],
            onToggle = { expanded = listOf(!expanded[0], expanded[1], expanded[2]) },
            visible = visible,
            selectedIndex = selectedIndex,
            absIndexById = absIndexById,
            playingWordId = playingWordId,
            onSelectRow = { i ->
                // 点击条目 = 选中并播报（PRD §2.3.3 触发方式）
                selectedIndex = i
                visible.getOrNull(i)?.let { speakWord(it) }
            },
            onSpeak = ::speakWord,
        )
        GroupSection(
            title = "复习",
            entries = today.reviewWords,
            tag = TagStyle.Review,
            expanded = expanded[1],
            onToggle = { expanded = listOf(expanded[0], !expanded[1], expanded[2]) },
            visible = visible,
            selectedIndex = selectedIndex,
            absIndexById = absIndexById,
            playingWordId = playingWordId,
            onSelectRow = { i ->
                selectedIndex = i
                visible.getOrNull(i)?.let { speakWord(it) }
            },
            onSpeak = ::speakWord,
        )
        GroupSection(
            title = "补查",
            entries = today.catchUpWords,
            tag = TagStyle.CatchUp,
            expanded = expanded[2],
            onToggle = { expanded = listOf(expanded[0], expanded[1], !expanded[2]) },
            visible = visible,
            selectedIndex = selectedIndex,
            absIndexById = absIndexById,
            playingWordId = playingWordId,
            onSelectRow = { i ->
                selectedIndex = i
                visible.getOrNull(i)?.let { speakWord(it) }
            },
            onSpeak = ::speakWord,
        )
        Spacer(Modifier.padding(bottom = 16.dp))
    }
}

/** 单个分组：组标题（词数 + 收拢箭头）+ 卡片条目列表（设计稿 .group-label / .sec-toggle / .today-list）。 */
@Composable
private fun GroupSection(
    title: String,
    entries: List<Word>,
    tag: TagStyle,
    expanded: Boolean,
    onToggle: () -> Unit,
    visible: List<Word>,
    selectedIndex: Int,
    absIndexById: Map<Long, Int>,
    playingWordId: Long?,
    onSelectRow: (Int) -> Unit,
    onSpeak: (Word) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                entries.size.toString(),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "▾" else "▸", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
        }

        when {
            !expanded -> {}
            entries.isEmpty() -> Text(
                "（空）",
                Modifier.padding(start = 8.dp, bottom = 8.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            else -> Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column {
                    entries.forEachIndexed { i, word ->
                        val visiblePos = visible.indexOfFirst { it.id == word.id }
                        BrowseRow(
                            word = word,
                            rowNo = (absIndexById[word.id] ?: 0) + 1,
                            tag = tag,
                            selected = visiblePos >= 0 && visiblePos == selectedIndex,
                            playing = playingWordId == word.id,
                            onSelect = if (visiblePos >= 0) {
                                { onSelectRow(visiblePos) }
                            } else {
                                {}
                            },
                            onSpeak = { onSpeak(word) },
                        )
                        if (i != entries.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

/** 通览条目行（设计稿 .t-row：指示条 | 序号 | 英文 | 词性+音标 | 中文 | 标签+播报按钮）。 */
@Composable
private fun BrowseRow(
    word: Word,
    rowNo: Int,
    tag: TagStyle,
    selected: Boolean,
    playing: Boolean,
    onSelect: () -> Unit,
    onSpeak: () -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    val highlight = selected || hovered
    val bg = if (highlight) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f) else MaterialTheme.colorScheme.surface

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(bg)
            .hoverable(hoverInteraction)
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中指示条：3dp 深色（设计稿 .t-row.selected 左侧 inset）
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
            Text(
                word.text,
                modifier = Modifier.weight(1.2f),
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(Modifier.weight(0.5f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (word.pos.isNotEmpty()) {
                    Text(
                        word.pos,
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                word.phonetic?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                word.translation,
                modifier = Modifier.weight(1.6f),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.width(110.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TagPill(tag)
                Spacer(Modifier.width(6.dp))
                SpeakButton(playing, onSpeak)
            }
        }
    }
}

/** 组标签胶囊（设计稿 .tag：新词=实底，复习=红描边，补查=灰描边）。 */
@Composable
private fun TagPill(tag: TagStyle) {
    val shape = RoundedCornerShape(99)
    val text = when (tag) {
        TagStyle.New -> "新词"
        TagStyle.Review -> "复习"
        TagStyle.CatchUp -> "补查"
    }
    when (tag) {
        TagStyle.New -> Box(Modifier.background(MaterialTheme.colorScheme.onSurface, shape).padding(horizontal = 7.dp, vertical = 2.dp)) {
            Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.surface)
        }
        TagStyle.Review -> Box(
            Modifier.border(1.dp, MaterialTheme.colorScheme.error, shape).padding(horizontal = 7.dp, vertical = 1.dp),
        ) {
            Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
        TagStyle.CatchUp -> Box(
            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(horizontal = 7.dp, vertical = 1.dp),
        ) {
            Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 播报按钮（设计稿 .spk：播放中高亮 + 脉动）。 */
@Composable
private fun SpeakButton(playing: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "spk")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "spkAlpha",
    )
    val color = if (playing) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    Text(
        "▶",
        Modifier
            .then(if (playing) Modifier.graphicsLayer(alpha = alpha) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(2.dp),
        fontSize = 13.sp,
        color = color,
    )
}

private enum class TagStyle { New, Review, CatchUp }
