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
     * 中文答案判定：对称拆分（PRD §2.5.2，修订 #20）。
     * 用户输入与标准答案均按中英文标点（，,；;）拆段；
     * 输入的每一段是标准答案中某一释义段的子串即该段命中，全部段命中 → 正确（支持拼接输入）；
     * 仅一段时退化为普通子串匹配。
     */
    fun chinese(expected: String, actual: String): Boolean {
        val inputSegments = splitDefinitions(actual)
        if (inputSegments.isEmpty()) return false
        val definitionSegments = splitDefinitions(expected)
        return inputSegments.all { segment -> definitionSegments.any { it.contains(segment) } }
    }

    /** 按中英文逗号/分号拆段，trim，去空段。 */
    private fun splitDefinitions(expected: String): List<String> =
        expected.split(*DEFINITION_SEPARATORS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun normalizeEnglish(text: String): String =
        text.trim().lowercase().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}
