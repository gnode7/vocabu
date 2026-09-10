# ISSUE-008 TTS + 桌面装配收口

**前置依赖**：ISSUE-005/006/009（消费 Fake 播报的界面全部就位；007 已废弃）
**目标**：真实 TTS 接入 + 替换全部 Fake + 端到端闭环验证。
**权威依据**：PRD §4、§6.1–6.3；ADR-0005。

## 范围

1. **TtsClient 实现**（desktopApp）：
   - 有道 dictvoice 接口：`https://dict.youdao.com/dictvoice?audio={text}&type={0|1}`（英文按设置选音；中文文本直接传给接口读中文）
   - 超时与失败静默（不抛出到 UI 层，返回失败信号）
2. **两级音频缓存**（磁盘，用户数据目录）：
   - 字母音频永久缓存（26 字母 + 需要的变体，key=text+voice）
   - 整词/词组 LRU 缓存上限 200 条，超限淘汰
   - 缓存命中零网络；首次未命中联网取
3. **AudioPlayer 实现**：javax.sound（Compose Desktop JVM），支持排队播报段（段间 0.5s、字母间 0.3s，PRD §4.3）、取消当前、清空队列
4. **装配**：导航骨架与依赖图已在 ISSUE-004 就位（VocabuApp：DB → 仓库 → 领域服务 → UI ViewModel）；本步**仅替换 Fake**——TtsClient/AudioPlayer 从 Fake 换为真实实现（main.kt 单点替换），并校验批改后自动回放（correctionReplay，ISSUE-009）在真实链路上生效
5. **端到端冒烟**：PRD §2.1–2.6 各节验收标准整表过一遍；性能抽查（PRD §6.1：万词列表、导入 1000 词 <5s、播报响应 <2s）；统计闭环抽查（DailyStudyLog 经 increment 增量落账，分母=判定数）

## TDD 关键用例

- 缓存策略纯逻辑（LRU 容量、字母永久、key 生成）单测
- 网络与播放器用 Fake 接口边界，不强测真实网络

## 验收

- [ ] 全应用无 Fake 残留，真机跑通「导入 → 通览 → 回忆 → 考察 → 次日复习」闭环
- [ ] 断网时：缓存命中正常播报、未命中静默跳过且流程不阻断
