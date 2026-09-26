# vocabu 设计规范 v0

> 版本：v0（转正基线）｜日期：2026-09-26｜作者：设计匠人
> 效力：M3 Expressive 转正后的**首份 UI 验收基准**。v0 = 每条规则都有 PoC 实证或既有文档出处，条条可追溯；后续页面实现以本规范为准，规范未覆盖处遵循 Material 3 官方规范并在 PRD 层补充。
> 修订：规则变更须出修订记录（日期+条目+依据），v0→v1 由用户拍板升级。

---

## 1. 定位

- 本项目 UI = **Compose Multiplatform + material3 1.12.0-alpha03（Expressive）+ M3 框架内收敛**。不引设计系统级第三方库（调研结论见 `research-cmp-ui-libs.md`，2026-09-17）。
- 规范三层结构：**形状语言 → 交互态与语义色 token → 组件边界与动效**。形状语言是品牌层（自绘），token 是收敛层（消灭散落数值），边界是风险层（桌面端缺口）。

## 2. 形状语言（品牌层）

### 2.1 形状清单（已验证参数，可直接照抄）

构造口径：`RoundedPolygon(n, 50f, rounding = CornerRounding(rounding))`——n 边正多边形，半径 50（100 单位归一坐标系），rounding 为圆角比例。

| 名称 | n | rounding | 用途定位 |
|---|---|---|---|
| triangle | 3 | 0.25 | 锐利强调（警示/差评） |
| square | 4 | 0.35 | 稳定容器（卡片基底候选） |
| pentagon | 5 | 0.25 | 中性强调 |
| hexagon | 6 | 0.30 | 信息单元（词条徽章候选） |
| octagon | 8 | 0.30 | 中间态 |
| dodecagon | 12 | 0.40 | 近圆过渡 |
| circle-ish | 16 | 0.60 | 近圆（头像/徽章候选） |

依据：`ExpressivePoc.kt` ShapeGallery（7 份 poly 数据，云端三重验证全绿）。
新增形状须走本表扩展（参数从 rounding 0.25~0.60 已验证区间内取值，区间外未验证）。

### 2.2 toPath 绘制口径（**硬规则，违者验收打回**）

**禁用** `RoundedPolygon.toShape()` 作为 `background(shape)` / ShapeModifier 路径绘制——material3 1.12.0-alpha03 桌面端真 bug，软件渲染与 GPU 直连均触发大面积绘制丢失（归因报告 `poc-render-attribution-20260926.md` §3.3，三后端死亡名单一致实锤）。

**唯一合规画法**（PoC 验证形态，照抄即可）：

```kotlin
val path = polygon.toPath()
val fillColor = MaterialTheme.colorScheme.primaryContainer
Canvas(modifier = Modifier.size(64.dp)) {
    scale(size.width / 100f, size.height / 100f) {
        drawPath(path, fillColor)
    }
}
```

要点：
- `scale(size/100f)` 不可省——RoundedPolygon 是 100 单位归一坐标系，漏 scale 会画成 100px 死块或不可见。
- fillColor 必须在 Canvas 外取（composable 作用域），Canvas 内非 composable。
- 尺寸自适应：shape 尺寸变化走 `Modifier.size()`，scale 表达式不动。

**回退条款**：上游（jetbrains-compose/material3）修复 toShape 后，可恢复 `background(color, polygon.toShape())` 触发态做对照验证，确认修复再切回。回退开关留在一个函数内，禁止散落多处。

**背景色副作用备忘**：toShape 死亡模式会污染窗口背景为 primaryContainer（#EADDFF），正常态 light surface 为 #FFFEF7——排障时先看背景色可快速识别本 bug（归因报告 §4.3）。

### 2.3 MaterialShapes 边界

alpha03 `MaterialShapes.Companion` 形状常量全部 **internal**，第三方不可引用；公有入口仅 `toShape/toPath` 桥接。品牌形状一律走 §2.1 构造器自建，不依赖官方预设常量（升级 alpha 版本时此边界可能变化，升级须复核）。

## 3. 交互态与语义色 token

### 3.1 列表/卡片三态（收敛散落值 → token）

现状：`onSurface.copy(alpha = 0.025f/0.045f)` 散落于 HomeScreen / RecallScreen（010 引入）。收敛为：

| token | 值 | 用途 |
|---|---|---|
| `HoverOverlayAlpha` | onSurface @ 2.5% | 悬停态背景 |
| `SelectedOverlayAlpha` | onSurface @ 4.5% | 选中态背景 |

硬规则：新页面禁止内联 alpha 魔法数，一律引 token（实现建议：shared ui 层单文件 `VocabuTokens.kt`，010 存量两处迁移随下批走查顺手做）。

### 3.2 评级四色（语义色，色值待拍板）

PRD §2.4 口径（L274）：评级 1→4 = 红（Forget）/ 橘（Hard）/ 黄（Good）/ 绿（Easy），点亮即整条填充该色。

v0 建议起点（M3 基准色板，**待用户拍板后锁定**）：

| 评级 | 建议值（light） | 来源 |
|---|---|---|
| 1 Forget | error `#B3261E` | M3 baseline error |
| 2 Hard | `#E8710A`（M3 tertiary 橙系候选） | 待拍板 |
| 3 Good | `#F9AB00`（amber，深色文字场景注意对比度） | 待拍板 |
| 4 Easy | `#146C2E`（green 系候选） | 待拍板 |

dark 模式本项目暂无（PRD 无此需求），色值仅 light。

### 3.3 计时三色

PRD 口径（L473）：倒计时圈 绿→黄→走满橙。色值随 §3.2 拍板一并定（建议同族：绿 `#146C2E` / 黄 `#F9AB00` / 橙 `#E8710A`），一个色族养两个语义，避免五颜六色。

### 3.4 状态矩阵（v0 声明范围）

v0 仅锁 hover / selected 两态（010 已踩坑区 + PoC 已验证）。pressed / focused / disabled 三态随具体组件实现逐个核对补入——**新增组件必须过五态核对单**（新组件走查清单标准项），v1 收口。

## 4. 组件边界（风险层）

**自绘区**（官方缺口，无库可救，实现须走查重点关照）：
- DataTable（词库管理表格视图）——官方至今没有，自绘或 Swing 互操作
- 滚动条样式 / 右键菜单 / tooltip——compose.desktop 原生 API + 自绘，010 ripple 同类高危区
- 图标——已 vector 自绘，不引图标库

**引库白名单**（超出白名单引库须走调研评审）：
- compottie（微动效：空词表引导/完成庆祝，需先出视觉稿）
- koalaplot-core（二期统计图表立项时）

**禁入**：设计系统级第三方库（调研报告结论：Jewel 归档、Cupertino 风格不符、Lumo 单点维护风险）。

## 5. 动效规格

- **双基座**：`MotionScheme.standard()`（常规交互）/ `MotionScheme.expressive()`（品牌时刻：完成庆祝、评级点亮）。PoC A 区实测双基座可用且三态矩阵双基座融合无冲突。
- 时长/曲线：v0 不自造刻度，**直接用 MotionScheme 提供的 tokens**（`MaterialTheme.motionScheme` 作用域内取），禁止内联 tween 时长魔法数（现状唯一存量：HomeScreen L497 `tween(500)` 呼吸动画，属装饰性微动效，随下批迁移 token 化）。
- 形状动效（morph）alpha03 桌面未验证，v0 不开放，待验证后出补充条款。

## 6. 验收基准引用

本规范即界面批次验收基准：走查/验收逐条对照 §2.2（toPath 硬规则）、§3.1（token 引用）、§4（白名单）。违规项 = 验收打回，无例外。

---

## 附：出处索引

- 形状数据/toPath 口径：`ExpressivePoc.kt` ShapeGallery（dd1f6f3 规避改造，云端三重验证 + 匠人独立复跑 7aae09b）
- toShape 禁用依据：归因报告 `poc-render-attribution-20260926.md` §3.3/§4.2/§4.3（三后端一致实锤）
- 三态 alpha：010 walkthrough 修复批次（HomeScreen/RecallScreen 现状）
- 评级四色/计时色：PRD §2.4（L274）/ §2.5（L473）
- 组件边界/引库白名单：调研报告 `research-cmp-ui-libs.md`（2026-09-17）
- MotionScheme 双基座：PoC A 区/C 区实测（noshape 实验全绿）
