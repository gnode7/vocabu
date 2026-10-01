package cn.vocabu

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.vocabu.probe.Cmp10860ProbePage

/**
 * CMP-10860 探针入口（上游互动专用，独立 LAUNCHER = 桌面双图标「Vocabu Probe」）。
 * 主 App / MainActivity 零改动；选择列表页零 toShape（noshape 安全区）。
 * 变体页走独立 Activity（内容树隔离，对齐桌面探针单变体窗口——桌面已实证 toShape
 * 毒性跨节点，选择器不得与变体内容同树）。
 */
class Cmp10860ProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ProbeHome(
                    envLine = "Android SDK ${Build.VERSION.SDK_INT} · ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
                    onOpen = { page ->
                        startActivity(
                            Intent(this, Cmp10860ProbeVariantActivity::class.java)
                                .putExtra(Cmp10860ProbeVariantActivity.EXTRA_PAGE, page),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ProbeHome(envLine: String, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("CMP-10860 probe", style = MaterialTheme.typography.headlineSmall)
        Text(envLine, style = MaterialTheme.typography.bodySmall)
        Text("material3 1.12.0-alpha03 · CMP 1.12.0", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onOpen("P1") }) { Text("P1 · MinimalRepro (issue body as-is)") }
        Button(onClick = { onOpen("P2") }) { Text("P2 · 7 shapes: toShape vs toPath") }
        Button(onClick = { onOpen("P3") }) { Text("P3 · Env badge / token hex") }
        Text(
            "每页截一张图发群。判定参照各页页内说明文字。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 变体页宿主：单变体全屏独立 Activity = 独立内容树（毒性范围观测不含选择器）。 */
class Cmp10860ProbeVariantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(EXTRA_PAGE) ?: "P1"
        val envLine = "Android SDK ${Build.VERSION.SDK_INT} · ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        setContent {
            Cmp10860ProbePage(page = page, envLine = envLine)
        }
    }

    companion object {
        const val EXTRA_PAGE = "page"
    }
}
