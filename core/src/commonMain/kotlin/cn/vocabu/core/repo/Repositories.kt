package cn.vocabu.core.repo

import cn.vocabu.core.model.AppSettings
import cn.vocabu.core.model.DailyStudyLog
import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Word

/** 词库仓库（ISSUE-001 定义接口，ISSUE-002 提供 SQLDelight 实现）。 */
interface WordRepository {
    /** 新增词条，返回分配了 id 的词条。isPhrase 应在保存前派生。 */
    fun add(word: Word): Word

    fun update(word: Word)

    /** 删除词条，其学习记录级联清除（PRD §5.1）。 */
    fun delete(wordId: Long)

    fun getById(wordId: Long): Word?

    /** 全部词条，按添加顺序（id 升序）。 */
    fun getAll(): List<Word>

    /** 按英文文本查找（大小写不敏感），用于重复判定。 */
    fun findByText(text: String): Word?

    /** 中英文模糊搜索（SQL LIKE 前缀 + 包含，英文不区分大小写）。 */
    fun search(query: String): List<Word>
}

/** 学习记录仓库。唯一性约束：UNIQUE(wordId, facet)（ADR-0004）。 */
interface LearningRecordRepository {
    fun find(wordId: Long, facet: Facet): LearningRecord?

    fun findAllByWordId(wordId: Long): List<LearningRecord>

    /** 按 (wordId, facet) 插入或更新。 */
    fun upsert(record: LearningRecord)

    fun deleteByWordId(wordId: Long)
}

/** 应用设置仓库（单行语义，PRD §5.5）。 */
interface SettingsRepository {
    fun get(): AppSettings

    fun save(settings: AppSettings)
}

/** 每日学习统计仓库（静默 upsert，PRD §5.3）。 */
interface StudyLogRepository {
    fun upsert(log: DailyStudyLog)

    fun findByDate(date: String): DailyStudyLog?
}
