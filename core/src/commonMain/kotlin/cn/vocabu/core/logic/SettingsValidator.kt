package cn.vocabu.core.logic

/**
 * 设置范围校验（PRD §2.6）：数字输入越界**钳到界内并提示**（ISSUE-004 TDD 用例）。
 * 纯函数，无状态。
 */
object SettingsValidator {

    const val DAILY_NEW_MIN = 1
    const val DAILY_NEW_MAX = 500
    const val CATCHUP_MIN = 0
    const val CATCHUP_MAX = 50
    const val EASY_MIN = 1
    const val EASY_MAX = 120
    const val GOOD_MAX = 300
    const val RECALL_DISPLAY_MIN = 1

    /** 钳制结果：[value] 为界内生效值；[warning] 非空表示发生了钳制，应展示给用户。 */
    data class Clamped(val value: Int, val warning: String?)

    fun dailyNewWordCount(input: Int): Clamped =
        clamp(input, DAILY_NEW_MIN, DAILY_NEW_MAX, "超出范围：需在 $DAILY_NEW_MIN–$DAILY_NEW_MAX 之间")

    fun facetCatchUpQuota(input: Int): Clamped =
        clamp(input, CATCHUP_MIN, CATCHUP_MAX, "超出范围：需在 $CATCHUP_MIN–$CATCHUP_MAX 之间")

    fun dictationEasyThreshold(input: Int): Clamped =
        clamp(input, EASY_MIN, EASY_MAX, "超出范围：需在 $EASY_MIN–$EASY_MAX 秒之间")

    /** Good 阈值下限动态依赖 Easy 阈值：Easy+1 ~ 300（PRD §2.6）。 */
    fun dictationGoodThreshold(input: Int, easy: Int): Clamped =
        clamp(input, easy + 1, GOOD_MAX, "Good 需大于 Easy 阈值（$EASY_MAX 秒封顶）")

    /** 回忆展示条数：1 ~ 今日全部词数（动态上限）；今日无词时下限保护为 1。 */
    fun recallDisplayCount(input: Int, todayTotal: Int): Clamped =
        clamp(input, RECALL_DISPLAY_MIN, maxOf(todayTotal, 1), "范围 1–今日词数($todayTotal)")

    private fun clamp(input: Int, min: Int, max: Int, warning: String): Clamped = when {
        input < min -> Clamped(min, warning)
        input > max -> Clamped(max, warning)
        else -> Clamped(input, null)
    }
}
