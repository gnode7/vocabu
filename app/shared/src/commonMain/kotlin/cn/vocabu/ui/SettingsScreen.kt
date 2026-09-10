package cn.vocabu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.vocabu.core.logic.SettingsValidator
import cn.vocabu.core.repo.SettingsRepository

/** 设置页（ISSUE-004）：PRD §2.6 全部条目 + 范围校验（钳制并提示）+ 显式保存。 */
class SettingsViewModel(private val settings: SettingsRepository) {
    private val s = settings.get()

    // 文本型数字输入（保存时经 SettingsValidator 钳制）
    var dailyNewWordCountText by mutableStateOf(s.dailyNewWordCount.toString())
    var facetCatchUpQuotaText by mutableStateOf(s.facetCatchUpQuota.toString())
    var recallDisplayCountText by mutableStateOf(s.recallDisplayCount.toString())
    var easyThresholdText by mutableStateOf(s.dictationEasyThreshold.toString())
    var goodThresholdText by mutableStateOf(s.dictationGoodThreshold.toString())

    // 单选（en2zh/zh2en/mixed、dictation/writing/mixed，存 PRD 5.5 词表值）
    var recallDirection by mutableStateOf(s.recallDirection)
    var testMode by mutableStateOf(s.testMode)
    var ttsService by mutableStateOf(s.ttsService)

    // 开关/多选
    var autoPlayOnSelect by mutableStateOf(s.autoPlayOnSelect)
    var correctionReplay by mutableStateOf(s.correctionReplay)
    var wordPlayPronunciation by mutableStateOf(s.wordPlayPronunciation)
    var wordPlaySpelling by mutableStateOf(s.wordPlaySpelling)
    var phrasePlayPronunciation by mutableStateOf(s.phrasePlayPronunciation)
    var phrasePlayTranslation by mutableStateOf(s.phrasePlayTranslation)
    var recallEn2ZhWordPlaySpelling by mutableStateOf(s.recallEn2ZhWordPlaySpelling)
    var recallEn2ZhPhrasePlayTranslation by mutableStateOf(s.recallEn2ZhPhrasePlayTranslation)
    var recallZh2EnAutoPlay by mutableStateOf(s.recallZh2EnAutoPlay)

    /** 校验提示（字段名 → 警告文案），保存时刷新。 */
    val warnings = mutableStateMapOf<String, String>()

    var savedMessage by mutableStateOf<String?>(null)
        private set

    /** 保存：全部数字输入钳到界内并提示（PRD 口径），随后写入单行表。 */
    fun save(todayTotal: Int) {
        val daily = SettingsValidator.dailyNewWordCount(dailyNewWordCountText.toIntOrNull() ?: -1)
        val catchup = SettingsValidator.facetCatchUpQuota(facetCatchUpQuotaText.toIntOrNull() ?: -1)
        val recallDisplay = SettingsValidator.recallDisplayCount(recallDisplayCountText.toIntOrNull() ?: -1, todayTotal)
        val easy = SettingsValidator.dictationEasyThreshold(easyThresholdText.toIntOrNull() ?: -1)
        val good = SettingsValidator.dictationGoodThreshold(goodThresholdText.toIntOrNull() ?: -1, easy.value)

        warnings.clear()
        daily.warning?.let { warnings["dailyNewWordCount"] = it }
        catchup.warning?.let { warnings["facetCatchUpQuota"] = it }
        recallDisplay.warning?.let { warnings["recallDisplayCount"] = it }
        easy.warning?.let { warnings["easyThreshold"] = it }
        good.warning?.let { warnings["goodThreshold"] = it }

        dailyNewWordCountText = daily.value.toString()
        facetCatchUpQuotaText = catchup.value.toString()
        recallDisplayCountText = recallDisplay.value.toString()
        easyThresholdText = easy.value.toString()
        goodThresholdText = good.value.toString()

        settings.save(
            cn.vocabu.core.model.AppSettings(
                dailyNewWordCount = daily.value,
                facetCatchUpQuota = catchup.value,
                autoPlayOnSelect = autoPlayOnSelect,
                wordPlayPronunciation = wordPlayPronunciation,
                wordPlaySpelling = wordPlaySpelling,
                phrasePlayPronunciation = phrasePlayPronunciation,
                phrasePlayTranslation = phrasePlayTranslation,
                recallDirection = recallDirection,
                recallDisplayCount = recallDisplay.value,
                recallEn2ZhWordPlaySpelling = recallEn2ZhWordPlaySpelling,
                recallEn2ZhPhrasePlayTranslation = recallEn2ZhPhrasePlayTranslation,
                recallZh2EnAutoPlay = recallZh2EnAutoPlay,
                testMode = testMode,
                correctionReplay = correctionReplay,
                dictationEasyThreshold = easy.value,
                dictationGoodThreshold = good.value,
                ttsService = ttsService,
            ),
        )
        savedMessage = "已保存（越界项已钳到界内）"
    }
}

/** 设置屏（设计稿屏 6：分组卡片 + 显式保存按钮）。 */
@Composable
fun SettingsScreen(vm: SettingsViewModel, todayTotal: Int) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Button(onClick = { vm.save(todayTotal) }) { Text("保存") }
        }
        vm.savedMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        // ---- 学习计划 ----
        SettingsGroup("学习计划") {
            NumberSetting(
                label = "每日新词数量",
                desc = "范围 1–500",
                value = vm.dailyNewWordCountText,
                onValueChange = { vm.dailyNewWordCountText = it },
                warning = vm.warnings["dailyNewWordCount"],
                unit = null,
            )
            NumberSetting(
                label = "每日补查数",
                desc = "范围 0–50，0 = 关闭补查",
                value = vm.facetCatchUpQuotaText,
                onValueChange = { vm.facetCatchUpQuotaText = it },
                warning = vm.warnings["facetCatchUpQuota"],
                unit = null,
            )
        }

        // ---- 通览 ----
        SettingsGroup("通览") {
            SwitchSetting("选中时自动播报", "选中条目时自动触发播报", vm.autoPlayOnSelect) { vm.autoPlayOnSelect = it }
        }

        // ---- 播报设置 ----
        SettingsGroup("播报设置") {
            SwitchSetting("单词-播报读音", null, vm.wordPlayPronunciation) { vm.wordPlayPronunciation = it }
            SwitchSetting("单词-播报字母拼写", null, vm.wordPlaySpelling) { vm.wordPlaySpelling = it }
            SwitchSetting("词组-播报读音", null, vm.phrasePlayPronunciation) { vm.phrasePlayPronunciation = it }
            SwitchSetting("词组-播报中文翻译", null, vm.phrasePlayTranslation) { vm.phrasePlayTranslation = it }
        }

        // ---- 回忆 ----
        SettingsGroup("回忆") {
            SegSetting(
                label = "回忆方向",
                options = listOf("mixed" to "混合", "en2zh" to "英→中", "zh2en" to "中→英"),
                selected = vm.recallDirection,
                onSelect = { vm.recallDirection = it },
            )
            NumberSetting(
                label = "同时展示条数",
                desc = "范围 1–今日词数",
                value = vm.recallDisplayCountText,
                onValueChange = { vm.recallDisplayCountText = it },
                warning = vm.warnings["recallDisplayCount"],
                unit = null,
            )
            SwitchSetting("英→中-单词播报字母拼写", null, vm.recallEn2ZhWordPlaySpelling) { vm.recallEn2ZhWordPlaySpelling = it }
            SwitchSetting("英→中-词组播报翻译", null, vm.recallEn2ZhPhrasePlayTranslation) { vm.recallEn2ZhPhrasePlayTranslation = it }
            SwitchSetting("中→英-自动播报英文读音", null, vm.recallZh2EnAutoPlay) { vm.recallZh2EnAutoPlay = it }
        }

        // ---- 考察 ----
        SettingsGroup("考察") {
            SegSetting(
                label = "考察方式",
                options = listOf("mixed" to "混合", "dictation" to "听写", "writing" to "默写"),
                selected = vm.testMode,
                onSelect = { vm.testMode = it },
            )
            SwitchSetting("批改后自动回放", "英文 = 读音 + 字母拼写，中文 = 释义", vm.correctionReplay) { vm.correctionReplay = it }
            NumberSetting(
                label = "Easy 时间阈值",
                desc = "正确且不超过该秒数 → Easy",
                value = vm.easyThresholdText,
                onValueChange = { vm.easyThresholdText = it },
                warning = vm.warnings["easyThreshold"],
                unit = "秒",
            )
            NumberSetting(
                label = "Good 时间阈值",
                desc = "正确且不超过该秒数 → Good，超过 → Hard",
                value = vm.goodThresholdText,
                onValueChange = { vm.goodThresholdText = it },
                warning = vm.warnings["goodThreshold"],
                unit = "秒",
            )
        }

        // ---- TTS ----
        SettingsGroup("TTS") {
            TtsServiceSetting(vm)
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        content()
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
private fun NumberSetting(label: String, desc: String?, value: String, onValueChange: (String) -> Unit, warning: String?, unit: String?) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.width(120.dp),
                    singleLine = true,
                )
                unit?.let {
                    Spacer(Modifier.width(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SwitchSetting(label: String, desc: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SegSetting(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (value, display) ->
                if (value == selected) {
                    Button(onClick = { onSelect(value) }) { Text(display) }
                } else {
                    OutlinedButton(onClick = { onSelect(value) }) { Text(display) }
                }
            }
        }
    }
}

@Composable
private fun TtsServiceSetting(vm: SettingsViewModel) {
    var expanded by mutableStateOf(false)
    Column(Modifier.fillMaxWidth()) {
        Text("TTS 服务", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { expanded = true }) {
            Text(if (vm.ttsService == "youdao") "有道词典" else vm.ttsService)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("有道词典") },
                onClick = { vm.ttsService = "youdao"; expanded = false },
            )
            DropdownMenuItem(
                text = { Text("Edge TTS（后续）", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = {}, // MVP 不可选（PRD §2.6：Edge TTS 后续）
                enabled = false,
            )
        }
    }
}
