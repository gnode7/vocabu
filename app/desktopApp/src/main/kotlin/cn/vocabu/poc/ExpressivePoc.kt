package cn.vocabu.poc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO

/**
 * M3 Expressive PoC 样张（界面优化前置可行性验证）
 *
 * 目的：在 CMP 1.12.0 + Kotlin 2.4.20 + material3 1.12.0-alpha03（独立火车）桌面上，
 * 实测 Expressive 基座（MotionScheme.expressive + 招牌组件）的可用性与观感，
 * 并验证 010 自绘三态卡的相对 token 取色（onSurface.copy(alpha)）在 Expressive
 * 主题下不被破坏——「基座管工程，脸面自绘」的融合论。
 *
 * 范围纪律：独立入口独立文件，零侵入生产 UI；只验形态，不做色板收敛（那是
 * 《设计规范 v0》的活）。ButtonGroup/FloatingToolbar 已编译确认存在，样张未覆盖
 * （API 重参数化，PoC 收益低）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun main(args: Array<String>) = application {
    val shotIdx = args.indexOf("--screenshot")
    Window(
        onCloseRequest = ::exitApplication,
        title = "Vocabu · M3 Expressive PoC (CMP 1.12.0 / K2.4.20 / m3 1.12.0-alpha03)",
        state = rememberWindowState(width = 1280.dp, height = 1240.dp, position = WindowPosition(0.dp, 0.dp)),
        undecorated = true,
    ) {
        PocRoot()
        if (shotIdx >= 0) {
            val outPath = args.getOrElse(shotIdx + 1) { "/tmp/poc-expressive.png" }
            LaunchedEffect(Unit) {
                delay(3000) // 等首帧渲染 + 指示器动画进入稳态
                captureScreen(outPath)
                exitApplication()
            }
        }
    }
}

/** Xvfb 无头环境下整屏捕获（配合 --screenshot 参数产出评审用图）。 */
private fun captureScreen(path: String) {
    val size = java.awt.Toolkit.getDefaultToolkit().screenSize
    val robot = Robot()
    val img = robot.createScreenCapture(Rectangle(0, 0, size.width, size.height))
    ImageIO.write(img, "png", File(path))
}

@Composable
fun PocRoot() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PocHeader()
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("A1 · Standard 基线（= 当前生产形态）")
                MotionSchemeDemo(MotionScheme.standard())
            }
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("A2 · Expressive 基座（MotionScheme.expressive）")
                MotionSchemeDemo(MotionScheme.expressive())
            }
        }
        SectionLabel("B · MaterialShapes 形状库（品牌自绘可借用的轮廓语言）")
        ShapeGallery()
        SectionLabel("C · 010 自绘三态 × 两基座（相对 token onSurface.copy(alpha) 是否被 Expressive 破坏）")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                SubLabel("C1 · Standard 基座")
                TriStateMatrix(MotionScheme.standard())
            }
            Column(modifier = Modifier.weight(1f)) {
                SubLabel("C2 · Expressive 基座")
                TriStateMatrix(MotionScheme.expressive())
            }
        }
        Text(
            "样张仅证形态与融合度：色板/间距/字阶收敛、状态矩阵补全归《设计规范 v0》。" +
                "LoadingIndicator/Wavy/形状库在两基座下均渲染正常即 PoC 通过。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PocHeader() {
    Column {
        Text("M3 Expressive PoC", style = MaterialTheme.typography.headlineMedium)
        Text(
            "CMP 1.12.0 · Kotlin 2.4.20 · material3 1.12.0-alpha03 独立火车 · API 均标注 @ExperimentalMaterial3ExpressiveApi",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SubLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MotionSchemeDemo(motionScheme: MotionScheme) {
    MaterialTheme(motionScheme = motionScheme) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {}) { Text("主操作") }
                Text(
                    "Button → 点击感受 motion 差异",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LoadingIndicator()
                ContainedLoadingIndicator()
                Text(
                    "LoadingIndicator / Contained",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LinearWavyProgressIndicator(modifier = Modifier.width(180.dp))
                CircularWavyProgressIndicator(modifier = Modifier.size(36.dp))
                Text(
                    "Wavy indeterminate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LinearWavyProgressIndicator(progress = { 0.64f }, modifier = Modifier.width(180.dp))
                CircularWavyProgressIndicator(progress = { 0.64f }, modifier = Modifier.size(36.dp))
                Text(
                    "Wavy determinate 0.64",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShapeGallery() {
    // 兼容矩阵实测：CMP 独立火车 material3 1.12.0-alpha03 的 MaterialShapes.Companion
    // 形状常量全部 internal，第三方不可引用；公有入口仅 MaterialShapesKt.toShape/toPath 桥接。
    // 故此处用 graphics-shapes 公有构造器自建形状，验证桥接链路。
    // 09-26 规避改造（实机终验三后端同丢拍板）：toShape() 桌面端触发大面积绘制丢失
    // （归因报告 docs/2.ui/poc-render-attribution-20260926.md §4.2 + poc-final-pack 四图），
    // 形状改走同一份 poly 数据的 toPath + Canvas.drawPath（探针 pathCanvas 已验证全绿）。
    // poly 数据不动；上游修复后可回退 background(color, polygon.toShape()) 原样。
    fun poly(n: Int, rounding: Float) = RoundedPolygon(n, 50f, rounding = CornerRounding(rounding))
    val shapes = listOf(
        "triangle" to poly(3, 0.25f),
        "square" to poly(4, 0.35f),
        "pentagon" to poly(5, 0.25f),
        "hexagon" to poly(6, 0.30f),
        "octagon" to poly(8, 0.30f),
        "dodecagon" to poly(12, 0.40f),
        "circle-ish" to poly(16, 0.60f),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        shapes.forEach { (name, polygon) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val path = polygon.toPath()
                val fillColor = MaterialTheme.colorScheme.primaryContainer
                Canvas(modifier = Modifier.size(64.dp)) {
                    scale(size.width / 100f, size.height / 100f) {
                        drawPath(path, fillColor)
                    }
                }
                Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 010 三态静态矩阵复刻：default / hovered / selected / selected+rated 四态。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TriStateMatrix(motionScheme: MotionScheme) {
    MaterialTheme(motionScheme = motionScheme) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TriStateRow(label = "apple  /ˈæp.əl/  n. 苹果", state = "default", bgAlpha = null, rated = false, indicator = false)
            TriStateRow(label = "brave  /breɪv/  adj. 勇敢的", state = "hovered", bgAlpha = 0.025f, rated = false, indicator = false)
            TriStateRow(label = "crisp  /krɪsp/  adj. 脆的", state = "selected", bgAlpha = 0.045f, rated = false, indicator = true)
            TriStateRow(label = "dwell  /dwel/  v. 居住", state = "selected+rated", bgAlpha = 0.045f, rated = true, indicator = true)
        }
    }
}

@Composable
private fun TriStateRow(label: String, state: String, bgAlpha: Float?, rated: Boolean, indicator: Boolean) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (bgAlpha != null) onSurface.copy(alpha = bgAlpha) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(22.dp)
                .background(if (indicator) onSurface else Color.Transparent),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (rated) onSurface.copy(alpha = 0.45f) else Color.Unspecified,
            modifier = Modifier.weight(1f),
        )
        Text(
            state,
            style = MaterialTheme.typography.labelSmall,
            color = onSurface.copy(alpha = 0.45f),
        )
    }
}
