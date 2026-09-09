# ISSUE-001 领域内核（:core）

**前置依赖**：无
**目标**：在 `:core` 实现全部纯领域逻辑——无 Compose、无 IO、无时钟直接调用（时间一律由参数传入，保证可测）。
**权威依据**：PRD §2.2.2、§2.4.1、§2.4.4、§2.4.5、§3、§5.2；ADR-0004（三考核面）；CONTEXT.md（考核面、今日词表、新词定义）。

## 范围

1. **SM-2 更新**（PRD §3.3）：`update(record, quality, now) → record`
   - `EF = EF + (0.1 - (5-q)*(0.08 + (5-q)*0.02))`，下限 1.3
   - quality<3 → repetitionCount=0、intervalSeconds=60
   - repCount==1 → 300s；==2 → 1800s；≥3 → intervalSeconds × EF
2. **评级映射**：Easy=5 / Good=4 / Hard=3 / Forget=0
3. **时间→评级**（考察自动判定，PRD §2.5.2）：正确且 elapsed≤Easy阈值→5；≤Good阈值→4；>Good→3；错误/留空→0
4. **答案判定**（PRD §2.5.2）：英文=忽略大小写、trim、词组内连续空格折叠后全等；中文=输入包含任一释义（答案按 `[,，;；]` 拆分，trim 后 contains）
5. **今日词表生成**（PRD §2.2.2）：新词（三面全无记录，按添加顺序，`min(dailyNewWordCount, 剩余未学)`）+ 复习词（任一面 `nextReviewTime<=now`）+ 补查（有记录且有缺面，按最近评级时间最旧优先，≤facetCatchUpQuota，0=关闭；旧称「缺面补齐」），去重
6. **工作集过滤**（PRD §2.4.1）：模式×考核面 → 词 ∈ 今日词表 且（该面缺记录 或 到期）
7. **混合方向分配**（PRD §2.4.4）：可评方向（缺/到期）中随机，单可评方向则固定
8. **接口定义**：`WordRepository` / `LearningRecordRepository` / `SettingsRepository` / `StudyLogRepository` / `TtsClient` / `AudioPlayer`（仅接口 + Fake 实现，真实实现分属 ISSUE-002/008）

## TDD 关键用例（红绿清单）

- SM-2：EF 下限 1.3、Forget 重置为 60s、repCount 1/2/≥3 三段间隔、连按 Easy 的间隔增长序列
- 判定：大小写、首尾空格、多空格折叠、多释义（逗号/分号/中文标点）、部分包含
- 词表生成：节流（剩余不足取全部）、配额最旧优先、配额=0、三类去重、全空 → 空
- 工作集：新词全模式参与、已建账未到期被过滤、缺面参与
- 混合：双可评 50/50（种子固定验证分布）、单可评固定

## 验收

- [ ] 上述用例全部绿灯，`./gradlew :core:jvmTest` 通过
- [ ] `:core` 无任何平台依赖（纯 Kotlin）
