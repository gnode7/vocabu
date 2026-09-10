package cn.vocabu.core.model

import kotlin.time.Instant

/** 词条（CONTEXT.md）：单词或词组，词库的基本单元。 */
data class Word(
    val id: Long = 0,
    /** 英文文本（存储时 trim） */
    val text: String,
    /** 词组判定：trim 后含空白字符即为词组，自动派生，用户不可直接设置（CONTEXT.md） */
    val isPhrase: Boolean,
    val phonetic: String? = null,
    val pos: String? = null,
    val translation: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /** 构造词条：自动计算 isPhrase（导入/添加/编辑保存时派生）；词组的 pos 强制 null（PRD §5.1）。 */
        fun of(
            text: String,
            phonetic: String?,
            pos: String?,
            translation: String,
            createdAt: Instant,
            updatedAt: Instant,
            id: Long = 0,
        ): Word {
            val trimmed = text.trim()
            val isPhrase = trimmed.any { it.isWhitespace() }
            return Word(
                id = id,
                text = trimmed,
                isPhrase = isPhrase,
                phonetic = phonetic?.trim()?.ifEmpty { null },
                pos = if (isPhrase) null else pos?.trim()?.ifEmpty { null },
                translation = translation.trim(),
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        }
    }
}
