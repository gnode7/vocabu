package cn.vocabu.probe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon

/**
 * CMP-10860 上游互动探针（技术验证工具，不适用设计规范与验收红线）。
 *
 * 上游问题（kropp @ YouTrack CMP-10860）："Does this API work in the same way on Android?"
 * 本探针 = 同一份 commonMain 代码交 Android 渲染栈跑 issue 场景，与桌面 09-26 归因结果对表
 * （docs/2.ui/poc-render-attribution-20260926.md）：
 *   桌面基准：toShape 触发大面积绘制丢失（形状 box + 无关 sibling/ancestor 的 Text/Card/Button
 *             全部从帧中消失，仅尾部内容部分幸存 —— 层/帧级中断而非单节点失效）
 *           + 背景污染（背景呈 primaryContainer #EADDFF，主题 surface 正确值 #FEF7FF——
 *             09-26 报告原记 #FFFEF7 系笔误，1001 探针运行时 hex 直读勘正，§4.3）
 *           + toPath + Canvas.drawPath 全绿（poly 数据与 shape 数学无辜，锁定 toShape 桥接路径）
 *
 * 三页单变体（每变体独立内容树，避免桌面已实证的跨节点毒性污染跨页观测）：
 *   P1 MinimalRepro   issue 正文结构原样（Header + 96dp toShape Box + 4×8 观测网格 + footer）
 *   P2 ShapeCompare   七形状对照：左列 toShape / 右列 toPath+Canvas.drawPath（同 poly 数据）
 *   P3 EnvBadge       环境徽标（envLine 由各端入口注入）+ 主题 token 运行时 hex（污染判定锚点）
 *
 * CMP-10860 探针专用——文件内 toShape 均为故意违反 §验收红线以复现 bug，非正式写法；
 * 上游修复后整包删除本文件，勿回填生产代码。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Cmp10860ProbePage(page: String, envLine: String) {
    MaterialTheme {
        when (page) {
            "P1" -> PageMinimalRepro()
            "P2" -> PageShapeCompare()
            "P3" -> PageEnvBadge(envLine)
        }
    }
}

/** P1 · issue 正文 MinimalRepro 结构原样 + 4×8 观测网格（放大丢失范围判定面）。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PageMinimalRepro() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // issue 正文原样：Box 上方的 Header，桌面死亡模式下随 Box 一同丢失
        Text("Header", style = MaterialTheme.typography.headlineMedium)
        // CMP-10860 探针专用——故意违反 §验收红线以复现 bug，非正式写法。
        // issue 正文最小复现原样：Box + background(color, RoundedPolygon(3,50f,rounding=0.25).toShape())
        Box(
            Modifier
                .size(96.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedPolygon(3, 50f, rounding = CornerRounding(0.25f)).toShape(),
                ),
        )
        // 观测网格（issue 正文无此段，仅放大丢失范围观测面）：4 行 × 8 列 Text/Button/Card 轮换
        listOf("Text", "Button", "Card", "Text").forEachIndexed { rowIdx, kind ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(8) { colIdx ->
                    val label = "r${rowIdx}c${colIdx}"
                    when (kind) {
                        "Button" -> Button(onClick = {}) { Text(label) }
                        "Card" -> Card(onClick = {}) { Text(label, Modifier.padding(8.dp)) }
                        else -> Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        // issue 正文原样：尾部内容（桌面死亡模式下部分幸存的参照位）
        Text("footer", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * P2 · 七形状对照：同 poly 数据，左列 toShape / 右列 toPath+Canvas.drawPath。
 * poly 数据同源复制自 app/desktopApp/.../poc/ExpressivePoc.kt ShapeGallery。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PageShapeCompare() {
    fun poly(n: Int, rounding: Float) = RoundedPolygon(n, 50f, rounding = CornerRounding(rounding))
    val shapes = listOf(
        "triangle(3,0.25)" to poly(3, 0.25f),
        "square(4,0.35)" to poly(4, 0.35f),
        "pentagon(5,0.25)" to poly(5, 0.25f),
        "hexagon(6,0.30)" to poly(6, 0.30f),
        "octagon(8,0.30)" to poly(8, 0.30f),
        "dodecagon(12,0.40)" to poly(12, 0.40f),
        "circle-ish(16,0.60)" to poly(16, 0.60f),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("P2 · 7 shapes: toShape vs toPath", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("A · toShape()", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                shapes.forEach { (name, polygon) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // CMP-10860 探针专用——故意违反 §验收红线以复现 bug，非正式写法。
                        Box(
                            Modifier
                                .size(64.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, polygon.toShape()),
                        )
                        Text(name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("B · toPath + drawPath", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                shapes.forEach { (name, polygon) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val path = polygon.toPath()
                        val fill = MaterialTheme.colorScheme.primaryContainer
                        Canvas(Modifier.size(64.dp)) {
                            scale(size.width / 100f, size.height / 100f) { drawPath(path, fill) }
                        }
                        Text(name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text(
            "桌面基准：A 列任一形状触发 → 全页丢失（含 B 列与页头）。" +
                "判定 A/B 存活组合 = Android 与桌面行为差异的关键信号。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** P3 · 环境徽标 + 主题 token 运行时 hex（背景污染判定锚点）。 */
@Composable
private fun PageEnvBadge(envLine: String) {
    val surface = MaterialTheme.colorScheme.surface
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("P3 · Env badge", style = MaterialTheme.typography.titleMedium)
        Text(envLine, style = MaterialTheme.typography.bodyMedium)
        Text("material3 1.12.0-alpha03 (org.jetbrains.compose.material3)", style = MaterialTheme.typography.bodyMedium)
        Text(
            "CMP 1.12.0 · Kotlin 2.4.20 · graphics-shapes 1.1.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        TokenRow("surface (expected page bg)", surface)
        TokenRow("primaryContainer (corruption suspect)", primaryContainer)
        Text(
            "判定：P1/P2 页背景实测若呈 primaryContainer 色 → 背景污染复现（桌面 §4.3 信号）。" +
                "hex 为运行时取值非硬编码；P1/P2 页文本幸存时其页背景值可与本页对照。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TokenRow(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).background(color, RoundedCornerShape(8.dp)))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(colorHex(color), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** commonMain 纯 Kotlin：ARGB Int → #RRGGBB。 */
private fun colorHex(color: Color): String =
    "#" + color.toArgb().toUInt().toString(16).padStart(8, '0').takeLast(6).uppercase()
