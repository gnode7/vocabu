package cn.vocabu.data

import cn.vocabu.core.model.Word
import cn.vocabu.core.repo.WordRepository
import cn.vocabu.db.VocabuDatabase
import kotlin.time.Instant

/** 词库仓库实现：SQLDelight 生成代码 → :core 接口。Instant ↔ epoch 秒换算集中在本层。 */
class WordRepositoryImpl(
    private val db: VocabuDatabase,
) : WordRepository {

    override fun add(word: Word): Word = db.wordQueries.transactionWithResult {
        db.wordQueries.insertWord(
            text = word.text,
            isPhrase = if (word.isPhrase) 1L else 0L,
            phonetic = word.phonetic,
            pos = word.pos,
            translation = word.translation,
            createdAt = word.createdAt.epochSeconds,
            updatedAt = word.updatedAt.epochSeconds,
        )
        val id = db.wordQueries.selectLastInsertId().executeAsOne()
        word.copy(id = id)
    }

    override fun update(word: Word) {
        db.wordQueries.updateWord(
            text = word.text,
            isPhrase = if (word.isPhrase) 1L else 0L,
            phonetic = word.phonetic,
            pos = word.pos,
            translation = word.translation,
            updatedAt = word.updatedAt.epochSeconds,
            id = word.id,
        )
    }

    override fun delete(wordId: Long) {
        db.wordQueries.deleteWord(wordId)
    }

    override fun getById(wordId: Long): Word? =
        db.wordQueries.selectWordById(wordId).executeAsOneOrNull()?.toDomain()

    override fun getAll(): List<Word> =
        db.wordQueries.selectAllWords().executeAsList().map { it.toDomain() }

    override fun findByTextAndPos(text: String, pos: String): Word? =
        db.wordQueries.selectWordByTextPos(text.trim(), Word.normalizePos(pos))
            .executeAsOneOrNull()?.toDomain()

    override fun search(query: String): List<Word> {
        val pattern = likePattern(query)
        return db.wordQueries.searchWords(pattern, pattern).executeAsList().map { it.toDomain() }
    }

    /** SQL LIKE 通配符转义 + 前后通配（前缀与包含均命中）。 */
    private fun likePattern(query: String): String =
        "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun cn.vocabu.db.Word.toDomain() = Word(
        id = id,
        text = text,
        isPhrase = isPhrase == 1L,
        phonetic = phonetic,
        pos = pos,
        translation = translation,
        createdAt = Instant.fromEpochSeconds(createdAt),
        updatedAt = Instant.fromEpochSeconds(updatedAt),
    )
}
