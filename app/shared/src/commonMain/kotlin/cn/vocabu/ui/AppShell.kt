package cn.vocabu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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

/** 应用内导航目标（ISSUE-004 导航骨架：通览 ↔ 词库 ↔ 会话屏 ↔ 设置）。 */
sealed interface Screen {
    data object Browse : Screen
    data object Recall : Screen
    data object Test : Screen
    data object Wordbook : Screen
    data object Settings : Screen
}

/**
 * 应用外壳（PRD v1.2 三入口 IA，设计稿 prototype.html 头部）：
 * 左侧分段入口 通览 / 回忆 N / 考察 M（计数 = EntryCounter 实时联动），右侧 词库管理 / 设置 链接。无侧边栏。
 */
@Composable
fun VocabuApp(
    homeViewModel: HomeViewModel,
    wordbookViewModel: WordbookViewModel,
    settingsViewModel: SettingsViewModel,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Browse) }

    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SegButton("通览", screen == Screen.Browse) { screen = Screen.Browse }
                    SegButton("回忆 ${homeViewModel.recallCount()}", screen == Screen.Recall) { screen = Screen.Recall }
                    SegButton("考察 ${homeViewModel.testCount()}", screen == Screen.Test) { screen = Screen.Test }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { screen = Screen.Wordbook }) {
                        Text("词库管理", color = if (screen == Screen.Wordbook) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    TextButton(onClick = { screen = Screen.Settings }) {
                        Text("设置", color = if (screen == Screen.Settings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            when (screen) {
                Screen.Browse -> HomeScreen(homeViewModel)
                Screen.Recall -> SessionPlaceholder("回忆会话", "英→中 / 中→英 / 混合，按设置的回忆方向执行") { screen = Screen.Browse }
                Screen.Test -> SessionPlaceholder("考察会话", "听写 / 默写 / 混合（先听写后默写），按设置的考察方式执行") { screen = Screen.Browse }
                Screen.Wordbook -> WordbookScreen(wordbookViewModel)
                Screen.Settings -> SettingsScreen(settingsViewModel, homeViewModel.todayList().all.size)
            }
        }
    }
}

@Composable
private fun SegButton(label: String, active: Boolean, onClick: () -> Unit) {
    if (active) {
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors()) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

/** 会话屏占位（ISSUE-005/006/007 实现具体流程）。 */
@Composable
private fun SessionPlaceholder(title: String, description: String, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("会话流程将在后续 issue 中实现（初步记忆已并入通览；回忆=006；考察=007）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onExit) { Text("退出") }
    }
}
