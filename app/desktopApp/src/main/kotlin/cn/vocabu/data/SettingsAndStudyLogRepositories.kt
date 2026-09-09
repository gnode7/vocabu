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
                memorizeDisplayCount = settings.memorizeDisplayCount.toLong(),
                autoPlayOnSelect = settings.autoPlayOnSelect.toLong(),
                wordPlayPronunciation = settings.wordPlayPronunciation.toLong(),
                wordPlaySpelling = settings.wordPlaySpelling.toLong(),
                phrasePlayPronunciation = settings.phrasePlayPronunciation.toLong(),
                phrasePlayTranslation = settings.phrasePlayTranslation.toLong(),
                recallDisplayCount = settings.recallDisplayCount.toLong(),
                recallEn2ZhWordPlaySpelling = settings.recallEn2ZhWordPlaySpelling.toLong(),
                recallEn2ZhPhrasePlayTranslation = settings.recallEn2ZhPhrasePlayTranslation.toLong(),
                recallZh2EnAutoPlay = settings.recallZh2EnAutoPlay.toLong(),
                dictationEasyThreshold = settings.dictationEasyThreshold.toLong(),
                dictationGoodThreshold = settings.dictationGoodThreshold.toLong(),
                ttsService = settings.ttsService,
            )
        } else {
            db.appSettingsQueries.insertSettings(
                dailyNewWordCount = settings.dailyNewWordCount.toLong(),
                facetCatchUpQuota = settings.facetCatchUpQuota.toLong(),
                memorizeDisplayCount = settings.memorizeDisplayCount.toLong(),
                autoPlayOnSelect = settings.autoPlayOnSelect.toLong(),
                wordPlayPronunciation = settings.wordPlayPronunciation.toLong(),
                wordPlaySpelling = settings.wordPlaySpelling.toLong(),
                phrasePlayPronunciation = settings.phrasePlayPronunciation.toLong(),
                phrasePlayTranslation = settings.phrasePlayTranslation.toLong(),
                recallDisplayCount = settings.recallDisplayCount.toLong(),
                recallEn2ZhWordPlaySpelling = settings.recallEn2ZhWordPlaySpelling.toLong(),
                recallEn2ZhPhrasePlayTranslation = settings.recallEn2ZhPhrasePlayTranslation.toLong(),
                recallZh2EnAutoPlay = settings.recallZh2EnAutoPlay.toLong(),
                dictationEasyThreshold = settings.dictationEasyThreshold.toLong(),
                dictationGoodThreshold = settings.dictationGoodThreshold.toLong(),
                ttsService = settings.ttsService,
            )
        }
    }

    private fun Boolean.toLong() = if (this) 1L else 0L

    private fun cn.vocabu.db.AppSettings.toDomain() = AppSettings(
        dailyNewWordCount = dailyNewWordCount.toInt(),
        facetCatchUpQuota = facetCatchUpQuota.toInt(),
        memorizeDisplayCount = memorizeDisplayCount.toInt(),
        autoPlayOnSelect = autoPlayOnSelect == 1L,
        wordPlayPronunciation = wordPlayPronunciation == 1L,
        wordPlaySpelling = wordPlaySpelling == 1L,
        phrasePlayPronunciation = phrasePlayPronunciation == 1L,
        phrasePlayTranslation = phrasePlayTranslation == 1L,
        recallDisplayCount = recallDisplayCount.toInt(),
        recallEn2ZhWordPlaySpelling = recallEn2ZhWordPlaySpelling == 1L,
        recallEn2ZhPhrasePlayTranslation = recallEn2ZhPhrasePlayTranslation == 1L,
        recallZh2EnAutoPlay = recallZh2EnAutoPlay == 1L,
        dictationEasyThreshold = dictationEasyThreshold.toInt(),
        dictationGoodThreshold = dictationGoodThreshold.toInt(),
        ttsService = ttsService,
    )
}

/** 每日统计仓库实现：按日期 upsert。 */
class StudyLogRepositoryImpl(
    private val db: VocabuDatabase,
) : StudyLogRepository {

    override fun upsert(log: DailyStudyLog) {
        val exists = db.dailyStudyLogQueries.selectLogByDate(log.date).executeAsOneOrNull() != null
        if (exists) {
            db.dailyStudyLogQueries.updateLog(
                newWordsLearned = log.newWordsLearned.toLong(),
                wordsReviewed = log.wordsReviewed.toLong(),
                sessionTimeSeconds = log.sessionTimeSeconds,
                accuracy = log.accuracy.toDouble(),
                date = log.date,
            )
        } else {
            db.dailyStudyLogQueries.insertLog(
                date = log.date,
                newWordsLearned = log.newWordsLearned.toLong(),
                wordsReviewed = log.wordsReviewed.toLong(),
                sessionTimeSeconds = log.sessionTimeSeconds,
                accuracy = log.accuracy.toDouble(),
            )
        }
    }

    override fun findByDate(date: String): DailyStudyLog? =
        db.dailyStudyLogQueries.selectLogByDate(date).executeAsOneOrNull()?.toDomain()

    private fun cn.vocabu.db.DailyStudyLog.toDomain() = DailyStudyLog(
        date = date,
        newWordsLearned = newWordsLearned.toInt(),
        wordsReviewed = wordsReviewed.toInt(),
        sessionTimeSeconds = sessionTimeSeconds,
        accuracy = accuracy.toFloat(),
    )
}
