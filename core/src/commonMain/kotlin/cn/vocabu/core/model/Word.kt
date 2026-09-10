package cn.vocabu.core.model

import kotlin.time.Instant

/** 词条（CONTEXT.md）：单词或词组，词库的基本单元。词条身份 = (text, pos)（ADR 0007）。 */
data class Word(
    val id: Long = 0,
    /** 英文文本（存储时 trim） */
    val text: String,
    /** 词组判定：trim 后含空白字符即为词组，自动派生，用户不可直接设置（CONTEXT.md） */
    val isPhrase: Boolean,
    val phonetic: String? = null,
    /** 词性：归一化存储（trim/小写/去尾点，如 n）；空词性 = 空串，禁 NULL（ADR 0007）；词组恒为空串 */
    val pos: String = "",
    val translation: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /**
         * 词性归一化：trim、小写、去尾部点号（如 ` N. ` → `n`）。
         * 单点收敛（ADR 0007）：导入/添加/编辑的判重与存储都走这里，勿散落手写。
         */
        fun normalizePos(pos: String?): String =
            pos?.trim()?.trimEnd('.')?.lowercase().orEmpty()

        /** 构造词条：isPhrase 自动派生；词组 pos 归一化为空串；单词 pos 归一化存储。 */
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
                pos = if (isPhrase) "" else normalizePos(pos),
                translation = translation.trim(),
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        }
    }
}
