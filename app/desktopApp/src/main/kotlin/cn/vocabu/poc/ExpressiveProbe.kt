package cn.vocabu.poc

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.graphics.shapes.CornerRounding
import androidx.compose.material3.toPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.graphics.shapes.RoundedPolygon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * M3 Expressive PoC 渲染归因探针（诊断专用，不进样张评审）。
 *
 * 背景：无头 Xvfb 下 PoC 样张上半屏（y<~480）绘制整体丢失，布局占位正常，
 * 唯一幸存者为 ShapeGallery 最后一枚文本标签（circle-ish）。
 * 本探针按单变量原则拆分变体，定位「位置性 vs 组件性 vs 顺序性」：
 *
 * 基础对照：
 * --probe headeronly    窗口顶部仅两行纯文本 → 文本是否也会死
 * --probe bottomstack   完整样张内容贴底排列 → 死区跟随位置还是内容
 * --probe noanim        完整布局去掉 Loading/Wavy 动画组件 → 动画组件嫌疑
 * --probe noshape       完整布局去掉 ShapeGallery → 形状库嫌疑
 * 最小复现：
 * --probe singleLoading 窗口中央单个 LoadingIndicator → 动画组件无辜实证
 * --probe singleText    窗口中央单个 Text → 对照
 * 形状逐个（toShape 路径）：
 * --probe shapeRows     7 形状垂直排列 → 逐个死活
 * --probe poly3/4/5/6/8/12/16  单形状居中 96dp（rounding 同样张）
 * --probe poly3z/poly16z/rectShape  rounding=0 变体（(3,0f)/(16,0f)/(4,0f)）→ 无豁免验证
 * 规避对照（数据层）：
 * --probe pathCanvas    同 poly(3,0.25) 走 toPath+Canvas.drawPath → 数据层无辜实证
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun main(args: Array<String>) = application {
    val shotIdx = args.indexOf("--screenshot")
    val probeIdx = args.indexOf("--probe")
    val probe = if (probeIdx >= 0) args.getOrElse(probeIdx + 1) { "headeronly" } else "headeronly"
    Window(
        onCloseRequest = ::exitApplication,
        title = "Vocabu PoC Probe: $probe",
        state = rememberWindowState(width = 1280.dp, height = 1240.dp, position = WindowPosition(0.dp, 0.dp)),
        undecorated = true,
    ) {
        when (probe) {
            "headeronly" -> ProbeHeaderOnly()
            "bottomstack" -> ProbeBottomStack()
            "noanim" -> ProbeNoAnim()
            "noshape" -> ProbeNoShape()
            "singleLoading" -> ProbeCenter { LoadingIndicator() }
            "singleText" -> ProbeCenter { Text("probe-text", style = MaterialTheme.typography.headlineMedium) }
            "shapeRows" -> ProbeShapeRows()
            "poly3" -> ProbeCenter { SinglePolyBox(3, 0.25f) }
            "poly4" -> ProbeCenter { SinglePolyBox(4, 0.35f) }
            "poly5" -> ProbeCenter { SinglePolyBox(5, 0.25f) }
            "poly6" -> ProbeCenter { SinglePolyBox(6, 0.30f) }
            "poly8" -> ProbeCenter { SinglePolyBox(8, 0.30f) }
            "poly12" -> ProbeCenter { SinglePolyBox(12, 0.40f) }
            "poly16" -> ProbeCenter { SinglePolyBox(16, 0.60f) }
            "poly3z" -> ProbeCenter { SinglePolyBox(3, 0f) }
            "pathCanvas" -> ProbePathCanvas()
            "poly16z" -> ProbeCenter { SinglePolyBox(16, 0f) }
            "rectShape" -> ProbeCenter { SinglePolyBox(4, 0f) }
        }
        if (shotIdx >= 0) {
            val outPath = args.getOrElse(shotIdx + 1) { "/tmp/poc-probe.png" }
            LaunchedEffect(Unit) {
                delay(3000)
                captureScreen(outPath)
                exitApplication()
            }
        }
    }
}

private fun captureScreen(path: String) {
    val size = java.awt.Toolkit.getDefaultToolkit().screenSize
    val robot = Robot()
    val img = robot.createScreenCapture(Rectangle(0, 0, size.width, size.height))
    ImageIO.write(img, "png", File(path))
}

@Composable
private fun ProbeRoot(verticalArrangement: Arrangement.Vertical, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp),
        verticalArrangement = verticalArrangement,
    ) {
        content()
    }
}

@Composable
private fun ProbeHeaderOnly() {
    ProbeRoot(Arrangement.spacedBy(16.dp)) {
        Text("M3 Expressive PoC", style = MaterialTheme.typography.headlineMedium)
        Text("headeronly · 纯文本对照", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProbeCenter(content: @Composable () -> Unit) {
    ProbeRoot(Arrangement.Center) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
    }
}

/** 完整样张内容，整体贴底：判定死区跟随屏幕位置还是内容次序。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProbeBottomStack() {
    ProbeRoot(Arrangement.Bottom) {
        ProbeContent()
    }
}

@Composable
private fun ProbeNoAnim() {
    ProbeRoot(Arrangement.spacedBy(16.dp)) {
        ProbeHeader()
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                SectionLabelP("A1 · Standard 基线（无动画组件版）")
                MotionSchemeDemoP(MotionScheme.standard(), withAnim = false)
            }
            Column(Modifier.weight(1f)) {
                SectionLabelP("A2 · Expressive 基座（无动画组件版）")
                MotionSchemeDemoP(MotionScheme.expressive(), withAnim = false)
            }
        }
        SectionLabelP("B · 形状库保留")
        ShapeGalleryP()
        SectionLabelP("C · 010 三态 × 两基座")
        TriStateMatrixP()
        FootNoteP()
    }
}

@Composable
private fun ProbeNoShape() {
    ProbeRoot(Arrangement.spacedBy(16.dp)) {
        ProbeHeader()
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                SectionLabelP("A1 · Standard 基线（无形状库版）")
                MotionSchemeDemoP(MotionScheme.standard(), withAnim = true)
            }
            Column(Modifier.weight(1f)) {
                SectionLabelP("A2 · Expressive 基座（无形状库版）")
                MotionSchemeDemoP(MotionScheme.expressive(), withAnim = true)
            }
        }
        SectionLabelP("B · 形状库已移除")
        SectionLabelP("C · 010 三态 × 两基座")
        TriStateMatrixP()
        FootNoteP()
    }
}

@Composable
private fun ProbeContent() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ProbeHeader()
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                SectionLabelP("A1 · Standard 基线")
                MotionSchemeDemoP(MotionScheme.standard(), withAnim = true)
            }
            Column(Modifier.weight(1f)) {
                SectionLabelP("A2 · Expressive 基座")
                MotionSchemeDemoP(MotionScheme.expressive(), withAnim = true)
            }
        }
        SectionLabelP("B · MaterialShapes 形状库")
        ShapeGalleryP()
        SectionLabelP("C · 010 三态 × 两基座")
        TriStateMatrixP()
        FootNoteP()
    }
}

@Composable
private fun ProbeHeader() {
    Column {
        Text("M3 Expressive PoC · Probe", style = MaterialTheme.typography.headlineMedium)
        Text(
            "渲染归因探针 · 复用样张组件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabelP(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun FootNoteP() {
    Text(
        "probe footer · 渲染到达则该行可见",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MotionSchemeDemoP(motionScheme: MotionScheme, withAnim: Boolean) {
    MaterialTheme(motionScheme = motionScheme) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .borderP()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {}) { Text("主操作") }
                Text("Button 行", style = MaterialTheme.typography.bodySmall)
            }
            if (withAnim) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LoadingIndicator()
                    ContainedLoadingIndicator()
                    Text("LoadingIndicator / Contained", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LinearWavyProgressIndicator(modifier = Modifier.width(180.dp))
                    CircularWavyProgressIndicator(modifier = Modifier.size(36.dp))
                    Text("Wavy indeterminate", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LinearWavyProgressIndicator(progress = { 0.64f }, modifier = Modifier.width(180.dp))
                    CircularWavyProgressIndicator(progress = { 0.64f }, modifier = Modifier.size(36.dp))
                    Text("Wavy determinate 0.64", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("（动画组件已移除）", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Modifier.borderP(): Modifier =
    this.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShapeGalleryP() {
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
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, polygon.toShape()),
                )
                Text(name, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TriStateMatrixP() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text("C1 · Standard", style = MaterialTheme.typography.labelLarge)
            TriStateRowsP(MotionScheme.standard())
        }
        Column(Modifier.weight(1f)) {
            Text("C2 · Expressive", style = MaterialTheme.typography.labelLarge)
            TriStateRowsP(MotionScheme.expressive())
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TriStateRowsP(motionScheme: MotionScheme) {
    MaterialTheme(motionScheme = motionScheme) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                Triple("apple  /ˈæp.əl/  n. 苹果", "default", null as Float?),
                Triple("brave  /breɪv/  adj. 勇敢的", "hovered", 0.025f),
                Triple("crisp  /krɪsp/  adj. 脆的", "selected", 0.045f),
                Triple("dwell  /dwel/  v. 居住", "selected+rated", 0.045f),
            ).forEach { (label, state, alpha) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            alpha?.let { MaterialTheme.colorScheme.onSurface.copy(alpha = it) } ?: Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(state, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SinglePolyBox(n: Int, rounding: Float) {
    val shape = RoundedPolygon(n, 50f, rounding = CornerRounding(rounding)).toShape()
    Box(
        modifier = Modifier
            .size(96.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, shape),
    )
}

/** 对照：同一 poly(3) 改走 toPath + Canvas drawPath（绕开 toShape 的 ShapeModifier/outline 路径）。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProbePathCanvas() {
    val path = RoundedPolygon(3, 50f, rounding = CornerRounding(0.25f)).toPath()
    val fillColor = MaterialTheme.colorScheme.primaryContainer
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("pathCanvas · toPath + Canvas.drawPath 对照", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Canvas(Modifier.size(96.dp)) {
            scale(size.width / 100f, size.height / 100f) {
                drawPath(path, fillColor)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("若此三角可见而 toShape 版炸 → ShapeModifier/outline 路径问题", style = MaterialTheme.typography.bodySmall)
        Text("footer", style = MaterialTheme.typography.bodySmall)
    }
}

/** 7 形状垂直排列：一行一形状+名字，观察逐个死活。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProbeShapeRows() {
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
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp)) {
        Text("shapeRows · 逐形状死活", style = MaterialTheme.typography.headlineSmall)
        shapes.forEach { (name, polygon) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, polygon.toShape()))
                Text(name, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("footer", style = MaterialTheme.typography.bodySmall)
    }
}

