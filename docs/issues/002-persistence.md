# ISSUE-002 持久层（:app:desktopApp）

**前置依赖**：ISSUE-001（仓库接口已定义）
**目标**：SQLDelight 落地四张表 + 仓库实现，域层接口与存储实现焊接。
**权威依据**：PRD §5.1–5.5；ADR-0003（SQLDelight 选型）。

## 范围

1. `.sq` schema（放 `:app:desktopApp`）：
   - `Word`：text UNIQUE COLLATE NOCASE、isPhrase、phonetic、pos、translation、createdAt、updatedAt
   - `LearningRecord`：wordId FK→Word ON DELETE CASCADE、facet（EN2ZH/ZH2EN/AUDIO_SPELLING）、**UNIQUE(wordId, facet)**、SM-2 五参数、totalReviews、totalForgets
   - `AppSettings`：单行表（id=1 固定），全部设置字段（含 facetCatchUpQuota）
   - `DailyStudyLog`：date PK（本地时区 YYYY-MM-DD）、四个计数字段
2. 仓库实现类包装生成代码，实现 `:core` 接口；Instant ↔ epoch 秒换算集中在这一层
3. 数据库文件位置：用户数据目录（PRD §6.3）
4. 新词「按添加顺序」= 按 id 升序

## TDD 关键用例

- 内存驱动（`app.cash.sqldelight:sqlite-driver`）跑仓库 CRUD
- UNIQUE(wordId, facet)：同词同面插入被拒、异面成功
- 删除 Word → 三面记录级联清除
- text 大小写不敏感唯一（Apple / apple 冲突）
- AppSettings 单行 upsert 语义

## 验收

- [ ] 仓库测试全绿
- [ ] 桌面端可启动并创建数据库文件
