# M3 Expressive PoC 上半屏渲染丢失 · 归因报告

- 日期：2026-09-26
- 执行：阿斯克米（写代码/跑自测），待匠人走查
- 环境：Linux x86_64 无头 Xvfb(:98, 1280×1280×24) / JDK21 / Gradle 9.7.1 / CMP 1.12.0 / Kotlin 2.4.20 / material3 1.12.0-alpha03
- 渲染路径：Skiko `[SKIKO] warn: Fallback to next API` → `RenderException: Cannot create Linux GL context` 后回落软件渲染（Xvfb 无 GLX）
- **判定终锤（09-26 21:48 实机终验闭环）**：alpha03 `toShape()` 桌面端真 bug——三后端（Xvfb 软渲染 / 实机 GPU direct / 实机 SOFTWARE）死亡名单完全一致；待用户拍板 toPath 规避改造（§4.1）

---

## 1. 现象

PoC 样张（`ExpressivePoc.kt`）无头截图：窗口上半部（内容前段）整体空白（原 surface 底色），布局占位正常（由 C 区起点 y 反推 A 区卡占位 ≈254px、LoadingIndicator 占位正常）。三份历史截图（poc_expressive / poc_noscroll / poc_undec，2026-09-20）与 2026-09-26 复现截图（poc_e0）症状完全一致。

幸存/死亡边界（完整样张）：
- 死：PocHeader（纯文本）、A 区双卡（border/Button/动画组件/文本全无）、B 区 SectionLabel、7 个形状 Box、前 6 个形状标签
- 活：ShapeGallery 最后一个标签 `circle-ish`、C 区 010 三态矩阵全部、尾注

## 2. 实验矩阵（单变量，探针 ExpressiveProbe.kt，`runExpressiveProbe` task；共 10 行，探针 18 变体全覆盖归因链）

| 实验 | 变量 | 结果 | 结论 |
|---|---|---|---|
| E0 复现 | 原样 | 复现（与 09-20 一致） | 稳定可复现，非偶发 |
| headeronly | 仅两行纯文本 | 全部渲染 | 文本/基础布局无辜；死区不随屏幕位置 |
| bottomstack | 完整内容贴底（Arrangement.Bottom） | 死亡名单不变（Header/A/B 标签/形状仍死，circle-ish 标签在 y≈922 仍活） | **死区跟随内容/组件，与屏幕位置无关** |
| noanim | 去 LoadingIndicator/Wavy×4，保留形状库 | 仍死（同名单） | **动画组件无辜**（noshape 中它们渲染完美） |
| noshape | 去 ShapeGallery，保留动画组件 | **全部渲染**（Header/A 卡/Button/Loading/Wavy/B 标签/C 区/footer 全绿） | **诱因锁定 ShapeGallery** |
| shapeRows | 7 形状垂直排列（仅形状+标题+footer） | 7 个 Box 全灭、前 6 标签灭、`circle-ish(16,0.60)` 标签与 footer 活 | 模式完整复刻样张 |
| poly3/poly4/poly5/poly6/poly8/poly12/poly16 | 单形状居中（96dp） | 全部无形状块（仅 surface 底色） | **无参数豁免：顶点数 3~16、rounding 0.25~0.60 全灭** |
| poly3z / poly16z / rectShape | rounding=0 变体：(3,0f) / (16,0f) / (4,0f) 单体 | 全部无形状块 | **rounding=0 不豁免**（含完全无圆角的退化方形） |
| singleLoading | 单 `LoadingIndicator` 居中 | 紫色 blob 动画形态正常渲染 | 动画组件无辜的独立实证（Expressive 标志 blob 形态完好） |
| pathCanvas | 同一 `RoundedPolygon(3,0.25)` 改走 `toPath()` + `Canvas.drawPath` | **三角正常渲染**，标题/说明/footer 全活 | **数据层无辜，问题在 `toShape()` 绘制路径** |

## 3. 归因结论

1. **诱因**：`RoundedPolygon.toShape()`（material3 1.12.0-alpha03 desktop，`MaterialShapesKt`）作为 `background(shape)` / ShapeModifier+outline 路径绘制时，在软件渲染 fallback 下触发大面积绘制丢失——该 op 自身不渲染，且与其同层/同帧的前序部分组件一并丢失（丢失集合与组件树结构相关，边界案例：最后一个 toShape 后的 Text 幸存）。
2. **数据层无辜**：同一 polygon 经 `toPath()` 取路径后 `drawPath` 渲染正常（pathCanvas 实验）。
3. **环境条件（实机终验已闭环，21:48）**：三后端死亡名单完全一致——云端 Xvfb 软渲染 fallback / 用户实机 GPU direct / 用户实机 `SKIKO_RENDER_API=SOFTWARE`，四图（poc_e0 + 实机三图）8 个内容条带逐对位 ±5px（DPI 级吻合），连 `circle-ish` 标签残迹（~8px 文字条带，主色 onSurfaceVariant）都同位同色。**与渲染后端彻底无关，非无头伪影**。附注：实机两张自动截图含 AWT 弹窗「Can't create an ImageOutputStream!」（`C:\temp` 目录不存在致 `ImageIO.write` 失败，弹窗本身是 AWT 层渲染，反证 Compose 场景外渲染正常；弹窗入图亦证明截图时刻 ≥3s 稳态，时序变量天然排除）。
4. **影响面切割干净**：与 010 自绘三态（C 区）、Expressive 动画组件（LoadingIndicator/Wavy）、MotionScheme 双基座融合全部无关——这些在 noshape 实验中全绿。**PoC 核心结论（C 区三态×双基座融合）不受本问题阻塞**，形状库仅影响 PoC 展示区的完整性。

## 4. 决策与建议

1. **PoC 形状区规避方案（已验证可行，暂缓实施）**：形状展示改用 `toPath()` + `Canvas.drawPath` 绘制，绕开 `toShape()`。改造量小（ShapeGallery 一个函数）。**时序采纳匠人意见：样张包保留 toShape 原样先行实机终验**——实机正常则无头伪影坐实（toShape 可留用，规避改造仅在「确认真 bug 且需要无头产物」时做）；实机同样丢失则 alpha03 桌面端真 bug 实锤（走规避 + 上游 issue）。
2. **实机终验（已执行，判定落锤）**：用户 Windows 实机三图（手动稳态截图 / GPU direct / 强制 SOFTWARE）与无头死亡名单完全一致——§4.1 预设决策条件达成：**alpha03 桌面端真 bug 实锤**，走规避 + 上游 issue。证据归档 `shared/poc-final-pack-20260926/poc-machine-{manual,gpu,soft}.png`。
3. **上游 issue 素材已备**：本报告实验矩阵 + 最小复现（poly3 单体窗口）可直接用于向 jetbrains-compose / material3 报 issue。
4. **设计规范 v0 无影响**：形状库的轮廓语言（B 区观察目标）经 toPath 依旧可得，品牌自绘借用路径不受阻。

## 5. 证据清单（agents/6aa04c07085696c790b45ccc/poc/）

- `poc_e0.png` 复现基线（09-26）；`poc_expressive.png` / `poc_noscroll.png` / `poc_undec.png` 历史对照（09-20）
- `poc_probe_headeronly.png` / `poc_probe_bottomstack.png`（位置反证）
- `poc_probe_noanim.png` / `poc_probe_noshape.png`（组件二分，后者全绿）
- `poc_probe_shapeRows.png` / `poc_probe_poly3.png` / `poc_probe_poly12.png` / `poc_probe_poly16.png`（逐形状/单体）
- `poc_probe_pathCanvas.png`（toPath 对照，三角正常）
- 实机终验三图（归档 `shared/poc-final-pack-20260926/`）：`poc-machine-manual.png`（用户手动稳态截图）/ `poc-machine-gpu.png`（GPU direct 自动截图，含 ImageOutputStream 弹窗）/ `poc-machine-soft.png`（强制 SOFTWARE 自动截图，同弹窗）——三图死亡名单与无头完全一致

## 6. 诊断资产（随本报告一并交付走查）

- `app/desktopApp/src/main/kotlin/cn/vocabu/poc/ExpressiveProbe.kt`：探针（--probe 变体开关），诊断专用，不进样张评审
- `app/desktopApp/build.gradle.kts`：新增 `runExpressiveProbe` task（与既有 `runExpressivePoc` 同构；-PpocProbe/-PpocShot/-PpocDisplay/-PpocX11Lib）
- 复跑命令：`./gradlew :app:desktopApp:runExpressiveProbe -PpocShot=/tmp/x.png -PpocDisplay=:98 -PpocX11Lib=/tmp/x11lib/usr/lib/x86_64-linux-gnu -PpocProbe=<variant>`
