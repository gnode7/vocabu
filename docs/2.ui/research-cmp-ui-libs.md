# 调研：Compose Multiplatform 成熟 UI 组件库盘点

> 时间：2026-09-17 ｜ 调研人：设计匠人 ｜ 服务于：界面优化前置决策
> 数据来源：GitHub API 实时活跃度核验（★/最近推送/是否归档）+ 多源搜索交叉（kotlinlang、jetpackcompose.app、klibs.io、Kotlinlang Slack、Reddit、Medium）
> 结论口径：★ 数与更新日期为 2026-09-17 快照；「成熟」以维护活跃度与生产采用为准，不以 star 唯一论。

---

## TL;DR（结论先行）

1. **CMP 生态没有 Android Compose 那种量级的第三方组件库**（不存在 MUI / Ant Design 对应物）。官方 **Material 2/3（compose.material3）就是事实标准**，本项目已在用。
2. **桌面端组件生态比移动端更稀疏**。多数第三方库是移动优先（触控交互、移动导航），对 Windows 桌面应用适用性要打折看。
3. **「换设计系统」方向没有可选项**：最接近的 Jewel（IntelliJ 风格）已归档移仓；Cupertino 系是 iOS 风格；其余新库生态太小。
4. 因此本项目界面优化的正确路径是 **M3 框架内打磨**（设计 tokens、间距/字阶/色阶收敛、动效与状态规格），而非引库换骨。可增量引入的只有「功能组件」级别的小件（图标/动效/图片加载），且当前均无硬需求。

---

## 一、设计系统级候选（换骨选项）——逐个排除

| 库 | 活跃度快照 | 判定 |
|---|---|---|
| **Jewel**（JetBrains，IntelliJ Int UI 风格） | ★862，**archived=True**（2025-04 最后推送，标题明示 MOVED TO IJ PLATFORM） | **排除**。已并入 IntelliJ Platform，定位转向 IDE 插件开发；独立桌面应用引用归档库 = 无维护保障。风格也与本项目不符。 |
| **Compose Cupertino**（alexzhirkevich/compose-cupertino） | ★1653，2025-10 更新，未归档 | **风格排除**。iOS 视觉语言，Windows 桌面应用不适用；但维护质量在 CMP 第三方 UI 库里属头部，若未来做 iOS 客户端（PRD §6.4）可复选。 |
| **Lumo UI**（nomanr/lumo-ui） | ★594，2026-05 更新 | **观察名单**。理念先进（M3 约束上的组件库 + Gradle 插件构建主题），但体量小、生产案例少，现在引入是把项目 UI 押在单点维护者上。 |
| **czan**（Tweener/czan） | ★82，2026-07 更新 | **排除**。体量不足以承载生产项目。 |

> 旁证：Medium/extensionbooster 等榜单文（2025-07、2026-05）给出的「CMP 必用库」清单，UI 类几乎都只指向官方 M3 与 Cupertino 系——第三方设计系统级库在榜单层面也缺席。

## 二、功能组件级候选（增量选项）——活跃可用，按需引入

| 库 | 用途 | 活跃度快照 | 对 vocabu 的适用性 |
|---|---|---|---|
| **Coil 3**（coil-kt/coil） | 图片加载/缓存，CMP 全平台 | ★11.9k，2026-09 仍日更 | 当前无图片需求。⚠️ 注意 GitHub 上存在可疑仿冒 repo（`coilkt/coil`，0★、描述异常），认准 `coil-kt/coil`。 |
| **Kamel**（Kamel-Media/Kamel） | 图片加载/缓存（KMP 原生） | ★796，2026-09 活跃 | 同上，与 Coil 二选一即可，现阶段都不需要。 |
| **compottie**（alexzhirkevich/compottie） | Lottie 动画渲染（纯 Kotlin） | ★682，2026-09 活跃 | 唯一有想象空间的：空词表引导、学习完成庆祝等**微动效**若要升级，这是首选。可接受 JSON 动画资源后按需引入。 |
| **compose-icons**（DevSrSouza/compose-icons） | 主流开源图标包（FontAwesome/Material/Tabs 等） | ★859，2024-09 后未更新 | 图标库性质稳定，停更不致命。但本项目图标少且已 vector 自绘，引入收益低；若界面优化要扩图标量再考虑。 |
| **koalaplot-core**（KoalaPlot/koalaplot-core） | CMP 图表/绘图 | ★784，2026-08 活跃 | **二期储备**。PRD §6.4 的学习统计图表/活跃度热力图落地时首选候选（CMP 图表库里维护最好）；次选 AAY-chart（★681，2026-02）。 |
| **multiplatform-markdown-renderer**（mikepenz） | Markdown 渲染 | ★1073，2026-09 活跃 | 当前无需求，不引入。 |
| **Decompose**（arkivanov/Decompose） | 生命周期/导航/状态（架构级） | ★2.9k，2026-09 活跃 | 属架构组件非 UI 组件。本项目单窗口三屏、页面栈极浅，现有状态管理足够，引入=过度设计。 |

## 三、桌面端原生缺口（引库解决不了的部分）

- **DataTable**：官方至今没有（JetBrains issue 自 2021 挂起至今），词库管理若要做表格视图需自绘或 Swing 互操作（SwingPanel + JTable）。
- **桌面特有交互**：滚动条样式、菜单栏、右键菜单、tooltip——部分靠 `compose.desktop` 原生 API + M3 的桌面适配，部分需自绘。这块没有第三方成熟方案，正是 010 ripple 教训同类的高危区（组件默认行为叠加）。

---

## 四、对「界面优化」的路径建议（我的判断）

**不推荐**：为优化而引库。CMP 桌面生态撑不起一次「组件库升级」，任何设计系统级替换都是高风险低收益。

**推荐路径——M3 框架内做设计系统收敛**（零新依赖）：
1. **设计 tokens 收敛**：色板（已有三态色规范，扩展为完整 light 色阶）、间距刻度、圆角刻度、字阶——落成单一 `Theme` 文件，消灭散落的魔法数（010 的 0.025/0.045 就是这类散落值）。
2. **组件状态规格补全**：hover/selected/pressed/focused/disabled 五态矩阵逐组件核对——010 ripple 缺陷的根因正是状态矩阵有盲区。
3. **动效规格**：转场/选中切换/列表反馈的时长与曲线统一（M3 easing 规范直接可用）。
4. **微动效升级（可选，唯一候选引库点）**：空词表引导、学习完成庆祝——用 compottie + 少量 JSON 动画，需先出视觉再谈引入。
5. **二期触发器**：统计图表立项时再引 koalaplot-core，届时一并做图表视觉规范。

**下一步建议**：如果认可路径 1-3，我先出一份《vocabu 设计规范 v0》（tokens + 状态矩阵 + 动效规格，落到 docs/2.ui/），经你拍板后作为界面优化批次的验收基准，再派单阿斯克米逐屏落地。

---

## 附：核验记录

- GitHub API 直查 repo：JetBrains/jewel、alexzhirkevich/compottie、alexzhirkevich/compose-cupertino、coil-kt/coil、Kamel-Media/Kamel、DevSrSouza/compose-icons、arkivanov/Decompose、mikepenz/multiplatform-markdown-renderer、nomanr/lumo-ui、Tweener/czan、KoalaPlot/koalaplot-core、TheChance101/AAY-chart（star/最近推送/归档状态均为 API 原始返回）。
- 搜索源：kotlinlang.org、jetpackcompose.app（CMP 库目录）、klibs.io、github.com/terrakok/kmp-awesome、Kotlinlang Slack（Jewel standalone 讨论、图表库讨论）、Reddit r/androiddev（Lumo UI 线索）、Medium（2025-07 榜单，渲染不完整仅作旁证）。
- 排除源：extensionbooster 2026-05 文章 URL 失效（404），其摘要要点（Cupertino/M3 为最强选项）已由其他源交叉。
