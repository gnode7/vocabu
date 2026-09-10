package cn.vocabu.core.fake

import cn.vocabu.core.model.AppSettings
import cn.vocabu.core.model.DailyStudyLog
import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Word
import cn.vocabu.core.repo.LearningRecordRepository
import cn.vocabu.core.repo.SettingsRepository
import cn.vocabu.core.repo.StudyLogRepository
import cn.vocabu.core.repo.WordRepository

/** 内存版词库仓库：id 自增、(text,pos) 归一化联合唯一（对齐真实库约束，ADR 0007）、中英文包含搜索。 */
class InMemoryWordRepository(
    initialWords: List<Word> = emptyList(),
) : WordRepository {
    private var nextId: Long = 1
    private val words = LinkedHashMap<Long, Word>()

    init {
        initialWords.forEach { add(it) }
    }

    private fun identityKey(text: String, pos: String): Pair<String, String> =
        text.trim().lowercase() to Word.normalizePos(pos)

    private fun keyExists(key: Pair<String, String>, excludeId: Long = -1): Boolean =
        words.values.any { it.id != excludeId && identityKey(it.text, it.pos) == key }

    override fun add(word: Word): Word {
        val key = identityKey(word.text, word.pos)
        require(!keyExists(key)) { "词条已存在: ${word.text}/${word.pos}" }
        val saved = word.copy(id = nextId++)
        words[saved.id] = saved
        return saved
    }

    override fun update(word: Word) {
        require(words.containsKey(word.id)) { "词条不存在: id=${word.id}" }
        require(!keyExists(identityKey(word.text, word.pos), excludeId = word.id)) {
            "词条已存在: ${word.text}/${word.pos}"
        }
        words[word.id] = word
    }

    override fun delete(wordId: Long) {
        words.remove(wordId)
    }

    override fun getById(wordId: Long): Word? = words[wordId]

    override fun getAll(): List<Word> = words.values.toList()

    override fun findByTextAndPos(text: String, pos: String): Word? {
        val key = identityKey(text, pos)
        return words.values.firstOrNull { identityKey(it.text, it.pos) == key }
    }

    override fun search(query: String): List<Word> {
        val q = query.trim()
        if (q.isEmpty()) return getAll()
        return words.values.filter {
            it.text.contains(q, ignoreCase = true) || it.translation.contains(q)
        }
    }
}

/** 内存版学习记录仓库：按 (wordId, facet) 唯一 upsert。 */
class InMemoryLearningRecordRepository : LearningRecordRepository {
    private val records = LinkedHashMap<Pair<Long, Facet>, LearningRecord>()

    override fun find(wordId: Long, facet: Facet): LearningRecord? = records[wordId to facet]

    override fun findAllByWordId(wordId: Long): List<LearningRecord> =
        records.entries.filter { it.key.first == wordId }.map { it.value }

    override fun findAll(): List<LearningRecord> = records.values.toList()

    override fun upsert(record: LearningRecord) {
        records[record.wordId to record.facet] = record
    }

    override fun deleteByWordId(wordId: Long) {
        records.keys.removeAll { it.first == wordId }
    }
}

/** 内存版设置仓库：单行语义。 */
class InMemorySettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private var current = initial

    override fun get(): AppSettings = current

    override fun save(settings: AppSettings) {
        current = settings
    }
}

/** 内存版每日统计仓库：增量落账语义。 */
class InMemoryStudyLogRepository : StudyLogRepository {
    private val logs = LinkedHashMap<String, DailyStudyLog>()

    override fun findByDate(date: String): DailyStudyLog? = logs[date]

    override fun increment(
        date: String,
        newWordsLearned: Int,
        wordsReviewed: Int,
        sessionTimeSeconds: Long,
        correctJudgments: Int,
        totalJudgments: Int,
    ): DailyStudyLog {
        val updated = (logs[date] ?: DailyStudyLog(date))
            .plus(newWordsLearned, wordsReviewed, sessionTimeSeconds, correctJudgments, totalJudgments)
        logs[date] = updated
        return updated
    }
}
