package cn.vocabu.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.vocabu.core.io.ExcelReader
import cn.vocabu.core.io.FilePicker
import cn.vocabu.core.logic.ImportReport
import cn.vocabu.core.logic.ImportTemplate
import cn.vocabu.core.logic.TodayListBuilder
import cn.vocabu.core.logic.WordbookImporter
import cn.vocabu.core.model.Word
import cn.vocabu.core.repo.LearningRecordRepository
import cn.vocabu.core.repo.SettingsRepository
import cn.vocabu.core.repo.WordRepository
import kotlin.time.Instant

/** 排序方式（PRD §2.1.2：默认字母序，可切换按添加时间）。 */
enum class WordSortMode { ALPHABETICAL, ADDED_TIME }

/** 添加/编辑弹窗的词性候选项（设计稿 prototype.html）。 */
private val POS_OPTIONS = listOf("", "n.", "v.", "adj.", "adv.", "prep.", "phr.")

/**
 * 词库管理页状态（ISSUE-003）。同步调用仓库；时间由 [now] 注入保证可测。
 */
class WordbookViewModel(
    private val words: WordRepository,
    private val records: LearningRecordRepository,
    private val settings: SettingsRepository,
    private val importer: WordbookImporter,
    private val excelReader: ExcelReader,
    private val filePicker: FilePicker,
    val now: () -> Instant,
) {
    /** 数据版本号：任何增删改/导入后自增，驱动列表与计数重算。 */
    var version by mutableStateOf(0)
        private set

    /** 词库总词数（计数行「共 N 词」）。 */
    var totalCount by mutableStateOf(0)
        private set

    /** 今日进入学习的词数（今日词表规模，PRD §2.1.2 计数行）。 */
    var todayCount by mutableStateOf(0)
        private set

    var query by mutableStateOf("")
    var sortMode by mutableStateOf(WordSortMode.ALPHABETICAL)
    var posFilter by mutableStateOf<String?>(null)
    var selectedIds by mutableStateOf(emptySet<Long>())
        private set

    /** 编辑对话框：null=关闭；id==0 表示新建，否则为编辑预填。 */
    var editing by mutableStateOf<Word?>(null)

    /** 待确认删除的词条（单个或批量）。 */
    var pendingDelete by mutableStateOf(emptyList<Word>())
        private set

    var importReport by mutableStateOf<ImportReport?>(null)
        private set

    /** 操作提示（如重复添加拦截）；展示后由 UI 调 [clearMessage] 关闭。 */
    var message by mutableStateOf<String?>(null)
        private set

    init {
        refreshStats()
    }

    private fun refreshStats() {
        val allWords = words.getAll()
        totalCount = allWords.size
        val recordsByWord = records.findAll().groupBy { it.wordId }
        val s = settings.get()
        todayCount = TodayListBuilder.build(
            allWords,
            recordsByWord,
            s.dailyNewWordCount,
            s.facetCatchUpQuota,
            now(),
        ).all.size
    }

    private fun bump() {
        version++
        refreshStats()
    }

    fun clearMessage() {
        message = null
    }

    /** 新建草稿：搜索词带入（设计稿：回车/空态「添加该词条」）。 */
    fun newDraft(text: String): Word = Word.of(text, null, null, "", now(), now())

    fun toggleSelected(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun requestDelete(items: List<Word>) {
        pendingDelete = items
    }

    fun cancelDelete() {
        pendingDelete = emptyList()
    }

    /** 确认删除：级联清除学习记录由仓库负责（PRD §5.1）。 */
    fun confirmDelete() {
        pendingDelete.forEach { words.delete(it.id) }
        pendingDelete = emptyList()
        selectedIds = emptySet()
        bump()
    }

    /** 新建/编辑保存（PRD §2.1.2）：单词与翻译必填；按归一化 (text, pos) 联合判重（ADR 0007）。统一经 Word.of 重派生。 */
    fun save(text: String, pos: String, phonetic: String, translation: String) {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty() || translation.isBlank()) {
            message = "请填写英文和中文释义（已保留你输入的内容）"
            return
        }
        val editingWord = editing
        val rebuilt = Word.of(
            text = trimmedText,
            phonetic = phonetic,
            pos = pos,
            translation = translation,
            createdAt = editingWord?.takeIf { it.id != 0L }?.createdAt ?: now(),
            updatedAt = now(),
            id = editingWord?.takeIf { it.id != 0L }?.id ?: 0,
        )
        val existing = words.findByTextAndPos(rebuilt.text, rebuilt.pos)
        val duplicate = when {
            existing == null -> false
            editingWord == null || editingWord.id == 0L -> true // 新建且同键已存在
            else -> existing.id != editingWord.id // 编辑撞上另一个同键词条
        }
        if (duplicate) {
            message = if (rebuilt.pos.isEmpty()) "该词已存在" else "该词（同词性）已存在"
            return
        }
        if (editingWord != null && editingWord.id != 0L) {
            words.update(rebuilt)
        } else {
            words.add(rebuilt)
        }
        editing = null
        bump()
    }

    /** 导入：选文件 → 读工作簿 → 解析查重落库 → 报告。取消选择则无动作。 */
    fun startImport() {
        val path = filePicker.pickExcelFile() ?: return
        val rows = excelReader.read(path)
        if (rows == null) {
            message = "无法读取该 Excel 文件"
            return
        }
        importReport = importer.import(rows, now())
        bump()
    }

    fun dismissImportReport() {
        importReport = null
    }

    /** 模板下载（v1.2 #25）：另存为对话框（默认 vocabu-import-template.xlsx）→ 运行时生成；取消则无动作。 */
    fun downloadTemplate() {
        val path = filePicker.saveImportTemplate(ImportTemplate.DEFAULT_FILE_NAME) ?: return
        message = "模板已保存：$path，可直接填写内容后导入"
    }

    /** 当前可见词条：搜索（空=全部）→ 词性筛选 → 排序（PRD §2.1.2）。 */
    fun currentList(): List<Word> {
        val base = if (query.isBlank()) words.getAll() else words.search(query.trim())
        val comparator = when (sortMode) {
            WordSortMode.ALPHABETICAL -> compareBy<Word> { it.text.lowercase() }
            WordSortMode.ADDED_TIME -> compareByDescending<Word> { it.createdAt }
        }
        return base
            .filter { posFilter == null || it.pos == posFilter }
            .sortedWith(comparator)
    }

    /** 词性筛选候选项（当前词库中出现的全部归一化词性，排除空词性）。 */
    fun availablePos(): List<String> = words.getAll().map { it.pos }.filter { it.isNotEmpty() }.distinct().sorted()
}

/** 词库管理页（ISSUE-003，按设计稿 prototype.html 对齐）。 */
@Composable
fun WordbookScreen(vm: WordbookViewModel) {
    val list = remember(vm.version, vm.query, vm.sortMode, vm.posFilter) { vm.currentList() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索英文或中文释义") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (vm.query.isNotBlank()) vm.editing = vm.newDraft(vm.query.trim())
                    },
                ),
            )
            SortButton(vm)
            PosFilterButton(vm)
            OutlinedButton(onClick = { vm.downloadTemplate() }) { Text("下载模板") }
            Button(onClick = { vm.startImport() }) { Text("导入 Excel") }
            Button(onClick = { vm.editing = vm.newDraft("") }) { Text("添加单词") }
        }

        // 计数行（设计稿 wbCount）
        Text(
            if (vm.query.isBlank()) {
                "共 ${vm.totalCount} 词 · 今日 ${vm.todayCount} 词进入学习"
            } else {
                "筛选到 ${list.size} 条"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (vm.selectedIds.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已选 ${vm.selectedIds.size} 个")
                Button(onClick = {
                    vm.requestDelete(list.filter { it.id in vm.selectedIds })
                }) { Text("删除所选") }
            }
        }

        if (list.isEmpty()) {
            EmptyStateCard(vm)
        } else {
            TableHeader()
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.id }) { word ->
                    WordRow(vm, word)
                    HorizontalDivider()
                }
            }
        }
    }

    vm.editing?.let { editingWord -> EditDialog(vm, editingWord) }
    vm.importReport?.let { report -> ImportReportDialog(vm, report) }
    if (vm.pendingDelete.isNotEmpty()) DeleteConfirmDialog(vm)
    vm.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearMessage() },
            confirmButton = { TextButton(onClick = { vm.clearMessage() }) { Text("好的") } },
            title = { Text("提示") },
            text = { Text(msg) },
        )
    }
}

/** 列头（设计稿 wb-table thead：英文|词性|音标|中文释义|操作）。 */
@Composable
private fun TableHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(48.dp))
        Text("英文", Modifier.weight(3f), style = MaterialTheme.typography.labelMedium)
        Text("词性", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
        Text("音标", Modifier.width(110.dp), style = MaterialTheme.typography.labelMedium)
        Text("中文释义", Modifier.weight(3f), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(96.dp))
    }
}

/** 空状态卡（设计稿 wbEmpty：没找到关键词 → 引导添加）。 */
@Composable
private fun EmptyStateCard(vm: WordbookViewModel) {
    val keyword = vm.query.trim()
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (keyword.isEmpty()) "词库还是空的" else "没有找到「$keyword」",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "换个关键词试试，或者把它添加进词库。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { vm.editing = vm.newDraft(keyword) }) { Text("添加该词条") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordRow(vm: WordbookViewModel, word: Word) {
    Row(
        // 双击行 = 编辑（v1.2 §2.1.2「点击编辑按钮或双击行」）
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = {}, onDoubleClick = { vm.editing = word })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = word.id in vm.selectedIds,
            onCheckedChange = { vm.toggleSelected(word.id) },
        )
        Text(
            word.text,
            Modifier.weight(3f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        Text(word.pos.ifEmpty { "—" }, Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
        Text(
            word.phonetic ?: "—",
            Modifier.width(110.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(word.translation, Modifier.weight(3f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { vm.editing = word }) { Text("编辑") }
        TextButton(onClick = { vm.requestDelete(listOf(word)) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SortButton(vm: WordbookViewModel) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }) {
        Text(if (vm.sortMode == WordSortMode.ALPHABETICAL) "按字母" else "按添加时间")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("按字母顺序") },
            onClick = { vm.sortMode = WordSortMode.ALPHABETICAL; expanded = false },
        )
        DropdownMenuItem(
            text = { Text("按添加时间") },
            onClick = { vm.sortMode = WordSortMode.ADDED_TIME; expanded = false },
        )
    }
}

@Composable
private fun PosFilterButton(vm: WordbookViewModel) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }) {
        Text(vm.posFilter ?: "全部词性")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("全部词性") },
            onClick = { vm.posFilter = null; expanded = false },
        )
        vm.availablePos().forEach { pos ->
            DropdownMenuItem(
                text = { Text(pos) },
                onClick = { vm.posFilter = pos; expanded = false },
            )
        }
    }
}

@Composable
private fun EditDialog(vm: WordbookViewModel, word: Word) {
    var text by remember(word.id) { mutableStateOf(word.text) }
    var pos by remember(word.id) { mutableStateOf(word.pos) }
    var phonetic by remember(word.id) { mutableStateOf(word.phonetic ?: "") }
    var translation by remember(word.id) { mutableStateOf(word.translation) }
    var posMenuExpanded by remember(word.id) { mutableStateOf(false) }

    // 含空格 → 词组：pos 归一化为空串（ADR 0007），下拉禁用
    val isPhrase = text.trim().any { it.isWhitespace() }

    AlertDialog(
        onDismissRequest = { vm.editing = null },
        title = { Text(if (word.id == 0L) "添加单词 / 词组" else "编辑词条") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("英文（必填）") },
                    placeholder = { Text("如 apple 或 look forward to") },
                    supportingText = { Text("含空格自动判定为词组") },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = { posMenuExpanded = true },
                    enabled = !isPhrase,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isPhrase) "词组 · 无词性" else if (pos.isEmpty()) "（无）" else pos)
                }
                DropdownMenu(expanded = posMenuExpanded, onDismissRequest = { posMenuExpanded = false }) {
                    POS_OPTIONS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.ifEmpty { "（无）" }) },
                            // 选中即归一化存储（单点收敛，ADR 0007）
                            onClick = { pos = Word.normalizePos(option); posMenuExpanded = false },
                        )
                    }
                }
                OutlinedTextField(
                    value = phonetic,
                    onValueChange = { phonetic = it },
                    label = { Text("音标（选填）") },
                    placeholder = { Text("/ˈæpl/") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = translation,
                    onValueChange = { translation = it },
                    label = { Text("中文释义（必填，多条用逗号/分号分隔）") },
                )
            }
        },
        confirmButton = {
            Button(onClick = { vm.save(text, pos, phonetic, translation) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { vm.editing = null }) { Text("取消") } },
    )
}

@Composable
private fun DeleteConfirmDialog(vm: WordbookViewModel) {
    val single = vm.pendingDelete.size == 1
    AlertDialog(
        onDismissRequest = { vm.cancelDelete() },
        title = { Text("删除词条") },
        text = {
            Text(
                if (single) {
                    "确定删除「${vm.pendingDelete[0].text}」吗？其三条考核面学习记录将一并删除，不可恢复。"
                } else {
                    "确定删除选中的 ${vm.pendingDelete.size} 个单词？其学习记录将一并删除，不可恢复。"
                },
            )
        },
        confirmButton = {
            Button(onClick = { vm.confirmDelete() }) { Text("删除") }
        },
        dismissButton = { TextButton(onClick = { vm.cancelDelete() }) { Text("取消") } },
    )
}

@Composable
private fun ImportReportDialog(vm: WordbookViewModel, report: ImportReport) {
    AlertDialog(
        onDismissRequest = { vm.dismissImportReport() },
        title = { Text("导入结果") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("成功导入 ${report.successCount} 个 · 重复跳过 ${report.duplicatedCount} 个 · 失败 ${report.failureCount} 行")
                if (report.duplicatedCount > 0) {
                    Text(
                        "重复：${report.duplicatedTexts.take(10).joinToString("、")}${if (report.duplicatedCount > 10) "…" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                report.failures.take(10).forEach {
                    Text("失败行：第 ${it.lineNo} 行 ${it.reason}", style = MaterialTheme.typography.bodySmall)
                }
                if (report.failureCount > 10) Text("…", style = MaterialTheme.typography.bodySmall)
                Text(
                    "新导入的词将按添加顺序进入每日新词队列。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { vm.dismissImportReport() }) { Text("知道了") } },
    )
}
