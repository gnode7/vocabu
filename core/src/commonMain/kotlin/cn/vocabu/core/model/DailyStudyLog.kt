package cn.vocabu.core.model

/** 每日学习统计（PRD §5.3），由评级处静默写入（修订 #14），无独立 UI。 */
data class DailyStudyLog(
    /** 日期（本地时区），格式 YYYY-MM-DD */
    val date: String,
    /** 新学单词数（当日首次创建学习记录的词数） */
    val newWordsLearned: Int = 0,
    /** 复习单词数 */
    val wordsReviewed: Int = 0,
    /** 学习时长（秒） */
    val sessionTimeSeconds: Long = 0,
    /** 正确率（0.0~1.0，Forget 计不正确） */
    val accuracy: Float = 0.0f,
)
