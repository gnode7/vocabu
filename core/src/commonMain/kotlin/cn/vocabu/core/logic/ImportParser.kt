package cn.vocabu.core.logic

/**
 * Excel 导入解析（PRD §2.1.1 v1.2）：纯函数，不做 IO、不查重。
 * 列映射 A=单词（必填） B=词性（可空） C=中文翻译（必填） D=音标（可空，v1.1 #12）。
 * 首行表头无条件跳过（v1.2 格式规范）；空行静默跳过；A/C 缺失的行记为格式错误并保留原行号。
 */
object ImportParser {

    /** 一行解析结果。 */
    sealed interface RowOutcome {
        /** 原文件中的行号（1 起，表头=1，数据从 2 起）。 */
        val lineNo: Int

        /** 解析成功的词条数据（尚未查重、尚未落库）。 */
        data class Entry(
            override val lineNo: Int,
            val text: String,
            val pos: String?,
            val phonetic: String?,
            val translation: String,
        ) : RowOutcome

        /** 格式错误的行（不中断导入，计入报告）。 */
        data class Invalid(override val lineNo: Int, val reason: String) : RowOutcome
    }

    /**
     * @param rows 表格内容（含首行表头），每行为 A/B/C/D 列的原始单元格文本
     */
    fun parse(rows: List<List<String?>>): List<RowOutcome> =
        rows.drop(1).withIndex().mapNotNull { (index, cells) ->
            val lineNo = index + 2 // 表头占第 1 行
            val text = cells.getOrNull(0)?.trim().orEmpty()
            val pos = cells.getOrNull(1)?.trim()?.ifEmpty { null }
            val phonetic = cells.getOrNull(3)?.trim()?.ifEmpty { null }
            val translation = cells.getOrNull(2)?.trim().orEmpty()

            if (cells.all { it.isNullOrBlank() }) {
                return@mapNotNull null // 空行静默跳过
            }
            when {
                text.isEmpty() -> RowOutcome.Invalid(lineNo, "单词为空")
                translation.isEmpty() -> RowOutcome.Invalid(lineNo, "翻译为空")
                else -> RowOutcome.Entry(lineNo, text, pos, phonetic, translation)
            }
        }
}
