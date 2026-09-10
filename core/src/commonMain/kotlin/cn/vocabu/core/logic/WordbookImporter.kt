package cn.vocabu.core.logic

import cn.vocabu.core.model.Word
import cn.vocabu.core.repo.WordRepository
import kotlin.time.Instant

/**
 * Excel 导入用例（PRD §2.1.1）：解析 → 查重（忽略大小写，重复跳过不算错误）→ 落库 → 报告。
 */
class WordbookImporter(private val words: WordRepository) {

    fun import(rows: List<List<String?>>, now: Instant): ImportReport {
        var successCount = 0
        val duplicatedTexts = mutableListOf<String>()
        val failures = mutableListOf<RowFailure>()

        for (outcome in ImportParser.parse(rows)) {
            when (outcome) {
                is ImportParser.RowOutcome.Entry -> {
                    val word = Word.of(
                        text = outcome.text,
                        phonetic = outcome.phonetic,
                        pos = outcome.pos,
                        translation = outcome.translation,
                        createdAt = now,
                        updatedAt = now,
                    )
                    // 归一化 (text, pos) 联合判重（ADR 0007）：同键跳过不算错误，异 pos 共存
                    if (words.findByTextAndPos(word.text, word.pos) != null) {
                        duplicatedTexts += word.text
                    } else {
                        words.add(word)
                        successCount++
                    }
                }

                is ImportParser.RowOutcome.Invalid ->
                    failures += RowFailure(outcome.lineNo, outcome.reason)
            }
        }

        return ImportReport(successCount, duplicatedTexts, failures)
    }
}

/** 导入结果反馈（PRD §2.1.1：成功 N、跳过重复 M、忽略格式错误 K）。 */
data class ImportReport(
    val successCount: Int,
    val duplicatedTexts: List<String>,
    val failures: List<RowFailure>,
) {
    val duplicatedCount: Int get() = duplicatedTexts.size
    val failureCount: Int get() = failures.size
}

/** 一条格式错误：原文件行号 + 原因。 */
data class RowFailure(val lineNo: Int, val reason: String)
