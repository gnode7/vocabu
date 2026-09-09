package cn.vocabu.core.logic

/**
 * 答案判定与考察自动评级（PRD §2.5.2）。
 * 纯函数，无状态。
 */
object AnswerJudge {

    /**
     * 考察场景：正确性 + 完成时长 → quality。
     * 正确且 elapsed ≤ Easy 阈值 → 5；≤ Good 阈值 → 4；> Good 阈值 → 3；错误/留空 → 0。
     */
    fun qualityFromTiming(
        correct: Boolean,
        elapsedSeconds: Long,
        easyThresholdSeconds: Long,
        goodThresholdSeconds: Long,
    ): Int = when {
        !correct -> 0
        elapsedSeconds <= easyThresholdSeconds -> 5
        elapsedSeconds <= goodThresholdSeconds -> 4
        else -> 3
    }

    /** 多释义分隔符：英文逗号、中文逗号、英文分号、中文分号 */
    private val DEFINITION_SEPARATORS = charArrayOf(',', '，', ';', '；')

    /**
     * 英文答案判定：忽略大小写、trim、词组内连续空白折叠为单个空格后全等。
     */
    fun english(expected: String, actual: String): Boolean =
        normalizeEnglish(expected) == normalizeEnglish(actual)

    /**
     * 中文答案判定：标准答案按分隔符拆出多个释义（trim、去空项），
     * 用户输入包含任一释义即正确。
     */
    fun chinese(expected: String, actual: String): Boolean {
        if (actual.isBlank()) return false
        return splitDefinitions(expected).any { actual.contains(it) }
    }

    private fun splitDefinitions(expected: String): List<String> =
        expected.split(*DEFINITION_SEPARATORS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun normalizeEnglish(text: String): String =
        text.trim().lowercase().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}
