package cn.vocabu.core.logic

/**
 * 导入模板唯一口径（v1.2 #25，PRD §2.1.1）：
 * 模板列结构与格式规范严格同源——列顺序/表头文案只在此定义，ImportParser 按同一下标取列
 * （A=单词/词组 B=词性 C=中文翻译 D=音标）。导入格式演进时本文件与 ImportParser 同步更新，
 * 不允许出现第二套列口径。
 */
object ImportTemplate {
    /** 另存为对话框默认文件名。 */
    const val DEFAULT_FILE_NAME = "vocabu-import-template.xlsx"

    /** 表头行（首行，导入时无条件跳过）。 */
    val HEADER = listOf("单词/词组", "词性", "中文翻译", "音标")

    /** 示例行：1) 带词性多释义的单词 2) 无词性无音标的词组。 */
    val SAMPLE_ROWS: List<List<String>> = listOf(
        listOf("apple", "n.", "苹果；苹果树", "ˈæpl"),
        listOf("look up", "", "查阅；查找", ""),
    )
}
