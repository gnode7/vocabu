package cn.vocabu.data

import cn.vocabu.core.model.AppSettings
import cn.vocabu.core.model.DailyStudyLog
import cn.vocabu.core.repo.SettingsRepository
import cn.vocabu.core.repo.StudyLogRepository
import cn.vocabu.db.VocabuDatabase

/** 设置仓库实现：单行（id=1）语义；首次读取落默认值。布尔以 0/1 存储，换算集中在本层。 */
class SettingsRepositoryImpl(
    private val db: VocabuDatabase,
) : SettingsRepository {

    override fun get(): AppSettings =
        db.appSettingsQueries.selectSettings().executeAsOneOrNull()?.toDomain()
            ?: AppSettings().also { save(it) }

    override fun save(settings: AppSettings) {
        val exists = db.appSettingsQueries.selectSettings().executeAsOneOrNull() != null
        if (exists) {
            db.appSettingsQueries.updateSettings(
                dailyNewWordCount = settings.dailyNewWordCount.toLong(),
                facetCatchUpQuota = settings.facetCatchUpQuota.toLong(),
                autoPlayOnSelect = settings.autoPlayOnSelect.bit(),
                wordPlayPronunciation = settings.wordPlayPronunciation.bit(),
                wordPlaySpelling = settings.wordPlaySpelling.bit(),
                phrasePlayPronunciation = settings.phrasePlayPronunciation.bit(),
                phrasePlayTranslation = settings.phrasePlayTranslation.bit(),
                recallDirection = settings.recallDirection,
                recallDisplayCount = settings.recallDisplayCount.toLong(),
                recallEn2ZhWordPlaySpelling = settings.recallEn2ZhWordPlaySpelling.bit(),
                recallEn2ZhPhrasePlayTranslation = settings.recallEn2ZhPhrasePlayTranslation.bit(),
                recallZh2EnAutoPlay = settings.recallZh2EnAutoPlay.bit(),
                testMode = settings.testMode,
                correctionReplay = settings.correctionReplay.bit(),
                dictationEasyThreshold = settings.dictationEasyThreshold.toLong(),
                dictationGoodThreshold = settings.dictationGoodThreshold.toLong(),
                ttsService = settings.ttsService,
            )
        } else {
            db.appSettingsQueries.insertSettings(
                dailyNewWordCount = settings.dailyNewWordCount.toLong(),
                facetCatchUpQuota = settings.facetCatchUpQuota.toLong(),
                autoPlayOnSelect = settings.autoPlayOnSelect.bit(),
                wordPlayPronunciation = settings.wordPlayPronunciation.bit(),
                wordPlaySpelling = settings.wordPlaySpelling.bit(),
                phrasePlayPronunciation = settings.phrasePlayPronunciation.bit(),
                phrasePlayTranslation = settings.phrasePlayTranslation.bit(),
                recallDirection = settings.recallDirection,
                recallDisplayCount = settings.recallDisplayCount.toLong(),
                recallEn2ZhWordPlaySpelling = settings.recallEn2ZhWordPlaySpelling.bit(),
                recallEn2ZhPhrasePlayTranslation = settings.recallEn2ZhPhrasePlayTranslation.bit(),
                recallZh2EnAutoPlay = settings.recallZh2EnAutoPlay.bit(),
                testMode = settings.testMode,
                correctionReplay = settings.correctionReplay.bit(),
                dictationEasyThreshold = settings.dictationEasyThreshold.toLong(),
                dictationGoodThreshold = settings.dictationGoodThreshold.toLong(),
                ttsService = settings.ttsService,
            )
        }
    }

    /** 布尔 → SQLite INTEGER 0/1。 */
    private fun Boolean.bit(): Long = if (this) 1L else 0L

    private fun cn.vocabu.db.AppSettings.toDomain() = AppSettings(
        dailyNewWordCount = dailyNewWordCount.toInt(),
        facetCatchUpQuota = facetCatchUpQuota.toInt(),
        autoPlayOnSelect = autoPlayOnSelect == 1L,
        wordPlayPronunciation = wordPlayPronunciation == 1L,
        wordPlaySpelling = wordPlaySpelling == 1L,
        phrasePlayPronunciation = phrasePlayPronunciation == 1L,
        phrasePlayTranslation = phrasePlayTranslation == 1L,
        recallDirection = recallDirection,
        recallDisplayCount = recallDisplayCount.toInt(),
        recallEn2ZhWordPlaySpelling = recallEn2ZhWordPlaySpelling == 1L,
        recallEn2ZhPhrasePlayTranslation = recallEn2ZhPhrasePlayTranslation == 1L,
        recallZh2EnAutoPlay = recallZh2EnAutoPlay == 1L,
        testMode = testMode,
        correctionReplay = correctionReplay == 1L,
        dictationEasyThreshold = dictationEasyThreshold.toInt(),
        dictationGoodThreshold = dictationGoodThreshold.toInt(),
        ttsService = ttsService,
    )
}

/** 每日统计仓库实现：增量落账（读→加→写），accuracy 为派生值随计数一并写库（PRD 5.3 v1.2）。 */
class StudyLogRepositoryImpl(
    private val db: VocabuDatabase,
) : StudyLogRepository {

    override fun findByDate(date: String): DailyStudyLog? =
        db.dailyStudyLogQueries.selectLogByDate(date).executeAsOneOrNull()?.toDomain()

    override fun increment(
        date: String,
        newWordsLearned: Int,
        wordsReviewed: Int,
        sessionTimeSeconds: Long,
        correctJudgments: Int,
        totalJudgments: Int,
    ): DailyStudyLog {
        val current = findByDate(date)
        val updated = (current ?: DailyStudyLog(date))
            .plus(newWordsLearned, wordsReviewed, sessionTimeSeconds, correctJudgments, totalJudgments)

        if (current == null) {
            db.dailyStudyLogQueries.insertLog(
                date = date,
                newWordsLearned = updated.newWordsLearned.toLong(),
                wordsReviewed = updated.wordsReviewed.toLong(),
                sessionTimeSeconds = updated.sessionTimeSeconds,
                totalJudgments = updated.totalJudgments.toLong(),
                totalCorrect = updated.totalCorrect.toLong(),
                accuracy = updated.accuracy.toDouble(),
            )
        } else {
            db.dailyStudyLogQueries.updateLog(
                newWordsLearned = updated.newWordsLearned.toLong(),
                wordsReviewed = updated.wordsReviewed.toLong(),
                sessionTimeSeconds = updated.sessionTimeSeconds,
                totalJudgments = updated.totalJudgments.toLong(),
                totalCorrect = updated.totalCorrect.toLong(),
                accuracy = updated.accuracy.toDouble(),
                date = date,
            )
        }
        return updated
    }

    private fun cn.vocabu.db.DailyStudyLog.toDomain() = DailyStudyLog(
        date = date,
        newWordsLearned = newWordsLearned.toInt(),
        wordsReviewed = wordsReviewed.toInt(),
        sessionTimeSeconds = sessionTimeSeconds,
        totalJudgments = totalJudgments.toInt(),
        totalCorrect = totalCorrect.toInt(),
    )
}
