package cn.vocabu.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import cn.vocabu.core.io.ExcelReader
import cn.vocabu.core.io.FilePicker
import cn.vocabu.core.logic.ImportReport
import cn.vocabu.core.logic.WordbookImporter
import cn.vocabu.core.model.Word
import cn.vocabu.core.repo.WordRepository
import kotlin.time.Instant

/** 排序方式（PRD §2.1.2：默认字母序，可切换按添加时间）。 */
enum class WordSortMode { ALPHABETICAL, ADDED_TIME }

/**
 * 词库管理页状态（ISSUE-003）。同步调用仓库；时间由 [now] 注入保证可测。
 */
class WordbookViewModel(
    private val words: WordRepository,
    private val importer: WordbookImporter,
    private val excelReader: ExcelReader,
    private val filePicker: FilePicker,
    val now: () -> Instant,
) {
    /** 数据版本号：任何增删改/导入后自增，驱动列表重算。 */
    var version by mutableStateOf(0)
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

    fun clearMessage() {
        message = null
    }

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
        version++
    }

    /** 新建/编辑保存（PRD §2.1.2）：单词与翻译必填；重复（忽略大小写）拦截。 */
    fun save(text: String, pos: String, phonetic: String, translation: String) {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty() || translation.isBlank()) {
            message = "单词和翻译不能为空"
            return
        }
        val existing = words.findByText(trimmedText)
        val editingWord = editing
        val duplicate = when {
            existing == null -> false
            editingWord == null || editingWord.id == 0L -> true // 新建且已存在
            else -> existing.id != editingWord.id // 编辑成另一个已存在的词
        }
        if (duplicate) {
            message = "该词已存在"
            return
        }
        if (editingWord != null && editingWord.id != 0L) {
            words.update(
                editingWord.copy(
                    text = trimmedText,
                    pos = pos.trim().ifEmpty { null },
                    phonetic = phonetic.trim().ifEmpty { null },
                    translation = translation.trim(),
                    updatedAt = now(),
                ),
            )
        } else {
            words.add(
                Word.of(
                    text = trimmedText,
                    phonetic = phonetic,
                    pos = pos,
                    translation = translation,
                    createdAt = now(),
                    updatedAt = now(),
                ),
            )
        }
        editing = null
        version++
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
        version++
    }

    fun dismissImportReport() {
        importReport = null
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

    /** 词性筛选候选项（当前词库中出现的全部词性）。 */
    fun availablePos(): List<String> = words.getAll().mapNotNull { it.pos }.distinct().sorted()
}

/** 词库管理页（ISSUE-003）。 */
@Composable
fun WordbookScreen(vm: WordbookViewModel) {
    val list = remember(vm.version, vm.query, vm.sortMode, vm.posFilter) { vm.currentList() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                modifier = Modifier.weight(1f),
                label = { Text("搜索中英文") },
                singleLine = true,
            )
            SortButton(vm)
            PosFilterButton(vm)
            Button(onClick = { vm.startImport() }) { Text("导入 Excel") }
            Button(onClick = { vm.editing = Word.of("", null, null, "", vm.now(), vm.now()) }) {
                Text("添加单词")
            }
        }

        if (vm.selectedIds.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已选 ${vm.selectedIds.size} 个")
                Button(onClick = {
                    vm.requestDelete(list.filter { it.id in vm.selectedIds })
                }) { Text("删除所选") }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.id }) { word ->
                WordRow(vm, word)
                HorizontalDivider()
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

@Composable
private fun WordRow(vm: WordbookViewModel, word: Word) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = word.id in vm.selectedIds,
            onCheckedChange = { vm.toggleSelected(word.id) },
        )
        Column(Modifier.weight(3f)) {
            Text(word.text, style = MaterialTheme.typography.titleMedium)
            val phonetic = word.phonetic
            if (phonetic != null) {
                Text(phonetic, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(word.pos ?: "", Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
        Text(word.translation, Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { vm.editing = word }) { Text("编辑") }
        TextButton(onClick = { vm.requestDelete(listOf(word)) }) { Text("删除") }
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
    var pos by remember(word.id) { mutableStateOf(word.pos ?: "") }
    var phonetic by remember(word.id) { mutableStateOf(word.phonetic ?: "") }
    var translation by remember(word.id) { mutableStateOf(word.translation) }

    AlertDialog(
        onDismissRequest = { vm.editing = null },
        title = { Text(if (word.id == 0L) "添加单词" else "编辑单词") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("单词/词组（必填）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pos,
                    onValueChange = { pos = it },
                    label = { Text("词性（选填，词组自动忽略）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = phonetic,
                    onValueChange = { phonetic = it },
                    label = { Text("音标（选填）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = translation,
                    onValueChange = { translation = it },
                    label = { Text("中文翻译（必填，多个释义用逗号/分号分隔）") },
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
    AlertDialog(
        onDismissRequest = { vm.cancelDelete() },
        title = { Text("删除确认") },
        text = { Text("确定删除选中的 ${vm.pendingDelete.size} 个单词？此操作不可撤销。") },
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
        title = { Text("导入完成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("成功导入 ${report.successCount} 个")
                Text(
                    "跳过重复 ${report.duplicatedCount} 个：${report.duplicatedTexts.take(10).joinToString("、")}${if (report.duplicatedCount > 10) "…" else ""}",
                )
                Text("忽略格式错误 ${report.failureCount} 行")
                report.failures.take(10).forEach {
                    Text("第 ${it.lineNo} 行：${it.reason}", style = MaterialTheme.typography.bodySmall)
                }
                if (report.failureCount > 10) Text("…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { vm.dismissImportReport() }) { Text("好的") } },
    )
}
