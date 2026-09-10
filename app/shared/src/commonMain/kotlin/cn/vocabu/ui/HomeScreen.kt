package cn.vocabu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.vocabu.core.logic.EntryCounter
import cn.vocabu.core.logic.TodayListBuilder
import cn.vocabu.core.model.Word
import cn.vocabu.core.repo.LearningRecordRepository
import cn.vocabu.core.repo.SettingsRepository
import cn.vocabu.core.repo.WordRepository
import kotlin.time.Instant

/** 通览（首页，ISSUE-004）：今日词表全量分三组展示 + 三入口计数 + 条目播报。 */
class HomeViewModel(
    private val words: WordRepository,
    private val records: LearningRecordRepository,
    private val settings: SettingsRepository,
    val speak: (Word) -> Unit,
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

    /** 回忆入口计数 = 当前回忆方向的工作集词数（PRD §2.4.1）。 */
    fun recallCount(): Int {
        val s = settings.get()
        return EntryCounter.recallCount(words.getAll(), recordsByWord(), s.recallDirection, now())
    }

    /** 考察入口计数 = 当前考察方式的并集工作集词数（PRD §2.5.1）。 */
    fun testCount(): Int {
        val s = settings.get()
        return EntryCounter.testCount(words.getAll(), recordsByWord(), s.testMode, now())
    }
}

/** 通览首页（设计稿屏 1：今日 N 词头 + 新词/复习/补查三组折叠列表）。 */
@Composable
fun HomeScreen(vm: HomeViewModel) {
    // remember(version)：数据/设置变化后重算今日词表
    val today = remember(vm.version) { vm.todayList() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("今日 ${today.all.size} 词", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("新词 ${today.newWords.size}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("复习 ${today.reviewWords.size}", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("补查 ${today.catchUpWords.size}", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
        }

        CollapsibleGroup("新词", today.newWords, defaultExpanded = true) { vm.speak(it) }
        CollapsibleGroup("复习", today.reviewWords, defaultExpanded = false) { vm.speak(it) }
        CollapsibleGroup("补查", today.catchUpWords, defaultExpanded = false) { vm.speak(it) }
    }
}

/** 分组折叠（设计稿 .sec-toggle：新词默认展开，复习/补查默认收拢，点组标题切换）。 */
@Composable
private fun CollapsibleGroup(
    title: String,
    entries: List<Word>,
    defaultExpanded: Boolean,
    onSpeak: (Word) -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(defaultExpanded) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$title ${entries.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Text(if (expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            if (entries.isEmpty()) {
                Text(
                    "（空）",
                    Modifier.padding(start = 8.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Card(Modifier.fillMaxWidth()) {
                    LazyColumn {
                        items(entries, key = { "${title}-${it.id}" }) { entry ->
                            WordEntryRow(entry, onSpeak)
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** 通览条目：英文、词性、音标、中文翻译 + 播报按钮（完整播报脚本在 ISSUE-005，此处先播词条读音）。 */
@Composable
private fun WordEntryRow(word: Word, onSpeak: (Word) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(word.text, Modifier.weight(3f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Text(word.pos.ifEmpty { "—" }, Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
        Text(word.phonetic ?: "—", Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(word.translation, Modifier.weight(3f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onSpeak(word) }) { Text("播报") }
    }
}
