package cn.vocabu.data

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.repo.LearningRecordRepository
import cn.vocabu.db.VocabuDatabase
import kotlin.time.Instant

/** 学习记录仓库实现：UNIQUE(wordId, facet) 落库，Instant ↔ epoch 秒换算集中在本层。 */
class LearningRecordRepositoryImpl(
    private val db: VocabuDatabase,
) : LearningRecordRepository {

    override fun find(wordId: Long, facet: Facet): LearningRecord? =
        db.learningRecordQueries.selectRecord(wordId, facet.name).executeAsOneOrNull()?.toDomain()

    override fun findAllByWordId(wordId: Long): List<LearningRecord> =
        db.learningRecordQueries.selectRecordsByWordId(wordId).executeAsList().map { it.toDomain() }

    override fun findAll(): List<LearningRecord> =
        db.learningRecordQueries.selectAllRecords().executeAsList().map { it.toDomain() }

    override fun upsert(record: LearningRecord) {
        val existing = db.learningRecordQueries.selectRecord(record.wordId, record.facet.name).executeAsOneOrNull()
        if (existing == null) {
            db.learningRecordQueries.insertRecord(
                wordId = record.wordId,
                facet = record.facet.name,
                easeFactor = record.easeFactor.toDouble(),
                repetitionCount = record.repetitionCount.toLong(),
                intervalSeconds = record.intervalSeconds,
                nextReviewTime = record.nextReviewTime.epochSeconds,
                lastReviewTime = record.lastReviewTime?.epochSeconds,
                totalReviews = record.totalReviews.toLong(),
                totalForgets = record.totalForgets.toLong(),
            )
        } else {
            db.learningRecordQueries.updateRecord(
                easeFactor = record.easeFactor.toDouble(),
                repetitionCount = record.repetitionCount.toLong(),
                intervalSeconds = record.intervalSeconds,
                nextReviewTime = record.nextReviewTime.epochSeconds,
                lastReviewTime = record.lastReviewTime?.epochSeconds,
                totalReviews = record.totalReviews.toLong(),
                totalForgets = record.totalForgets.toLong(),
                wordId = record.wordId,
                facet = record.facet.name,
            )
        }
    }

    override fun deleteByWordId(wordId: Long) {
        db.learningRecordQueries.deleteRecordsByWordId(wordId)
    }

    private fun cn.vocabu.db.LearningRecord.toDomain() = LearningRecord(
        id = id,
        wordId = wordId,
        facet = Facet.valueOf(facet),
        easeFactor = easeFactor.toFloat(),
        repetitionCount = repetitionCount.toInt(),
        intervalSeconds = intervalSeconds,
        nextReviewTime = Instant.fromEpochSeconds(nextReviewTime),
        lastReviewTime = lastReviewTime?.let { Instant.fromEpochSeconds(it) },
        totalReviews = totalReviews.toInt(),
        totalForgets = totalForgets.toInt(),
    )
}
