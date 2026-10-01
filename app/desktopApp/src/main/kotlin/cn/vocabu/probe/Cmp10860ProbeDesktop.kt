package cn.vocabu.probe

import androidx.compose.runtime.LaunchedEffect
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
 * CMP-10860 探针桌面入口（诊断专用，零侵入生产 MainKt）。
 * 与 ExpressiveProbe 同模式：--page=P1|P2|P3 单变体窗口，--screenshot=/path 跑完自动截图退出。
 *
 * 桌面复现成功（死亡名单/背景污染/对照列全绿）= commonMain 探针页自身正确性验证；
 * Android 实机结果须与桌面基准对表（docs/2.ui/poc-render-attribution-20260926.md）。
 */
fun main(args: Array<String>) = application {
    val shotIdx = args.indexOf("--screenshot")
    val pageIdx = args.indexOf("--page")
    val page = if (pageIdx >= 0) args.getOrElse(pageIdx + 1) { "P1" } else "P1"
    val envIdx = args.indexOf("--env")
    val env = if (envIdx >= 0) args.getOrElse(envIdx + 1) { desktopEnv() } else desktopEnv()
    Window(
        onCloseRequest = ::exitApplication,
        title = "Vocabu · CMP-10860 probe ($page)",
        state = rememberWindowState(width = 1280.dp, height = 1240.dp, position = WindowPosition(0.dp, 0.dp)),
        undecorated = true,
    ) {
        Cmp10860ProbePage(page = page, envLine = env)
        if (shotIdx >= 0) {
            val outPath = args.getOrElse(shotIdx + 1) { "/tmp/cmp10860-probe.png" }
            LaunchedEffect(Unit) {
                delay(3000) // 等首帧渲染稳态（与既有探针节奏一致）
                captureScreen(outPath)
                exitApplication()
            }
        }
    }
}

private fun desktopEnv(): String =
    "Desktop JVM · ${System.getProperty("os.name")} ${System.getProperty("os.version")} · " +
        "JDK ${System.getProperty("java.version")} · ${System.getProperty("os.arch")}"

/** Xvfb 无头环境整屏捕获（与既有 PoC/Probe 同实现）。 */
private fun captureScreen(path: String) {
    val size = java.awt.Toolkit.getDefaultToolkit().screenSize
    val img = Robot().createScreenCapture(Rectangle(0, 0, size.width, size.height))
    ImageIO.write(img, "png", File(path))
}
