package cn.vocabu.core.model

/** 每日学习统计（PRD §5.3），由评级处静默增量落账（修订 #14、#19「提交即落账」）。 */
data class DailyStudyLog(
    /** 日期（本地时区），格式 YYYY-MM-DD */
    val date: String,
    /** 新学单词数（当日首次创建学习记录的词数） */
    val newWordsLearned: Int = 0,
    /** 复习单词数 */
    val wordsReviewed: Int = 0,
    /** 学习时长（秒） */
    val sessionTimeSeconds: Long = 0,
    /** 判定总数累计（听写一次提交 +2，默写 +1）——正确率分母（PRD 1.2 #21） */
    val totalJudgments: Int = 0,
    /** 判定正确数累计（Forget = 不正确）——正确率分子（PRD 1.2 #21） */
    val totalCorrect: Int = 0,
) {
    /** 正确率（派生值，不落库构造；分母 0 → 0.0）。 */
    val accuracy: Float
        get() = if (totalJudgments <= 0) 0.0f else totalCorrect.toFloat() / totalJudgments

    /** 纯增量：在当日统计上累计一组事件，返回新对象。 */
    fun plus(
        newWordsLearned: Int = 0,
        wordsReviewed: Int = 0,
        sessionTimeSeconds: Long = 0,
        correctJudgments: Int = 0,
        totalJudgments: Int = 0,
    ): DailyStudyLog = copy(
        newWordsLearned = this.newWordsLearned + newWordsLearned,
        wordsReviewed = this.wordsReviewed + wordsReviewed,
        sessionTimeSeconds = this.sessionTimeSeconds + sessionTimeSeconds,
        totalJudgments = this.totalJudgments + totalJudgments,
        totalCorrect = this.totalCorrect + correctJudgments,
    )
}
