package cn.vocabu.core.logic

import cn.vocabu.core.model.AppSettings
import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.Word

/** 播报语言（PRD §4.2：英文语音 / 中文语音）。 */
enum class SpeechLang { EN, ZH }

/**
 * 单个播报段：文本 + 语言 + 段后停顿（毫秒）。
 * 停顿由播放器在播放完该段后执行（AudioPlayer.enqueue 的 pauseAfterMillis）。
 */
data class SpeechSegment(
    val text: String,
    val lang: SpeechLang,
    val pauseAfterMillis: Long,
)

/**
 * 播报脚本生成（纯函数，ISSUE-005；PRD §2.3.3、§4.2、§4.3）：
 * - 单词默认：读音 → 0.5s → 字母拼写（每字母单独一段，字母间无停顿，修订 #29）；「单词播报中文翻译」开启时末尾追加翻译段（0012 C1，默认关）
 * - 词组默认：读音 → 0.5s → 中文翻译
 * - 播报内容按设置多选裁剪；全关时返回空脚本（不播）。
 */
object SpeechScriptBuilder {

    /** 段间停顿：各内容段之间 0.5s（PRD §4.3）。 */
    private const val SEGMENT_PAUSE_MS = 500L

    /** 字母间停顿：0（PRD 修订 #29，2026-09-16 用户反馈拼写节奏慢；字母音频首尾自带静音，背靠背连播即自然拼读感）。 */
    private const val LETTER_PAUSE_MS = 0L

    fun build(word: Word, settings: AppSettings): List<SpeechSegment> {
        return if (word.isPhrase) phraseScript(word, settings) else wordScript(word, settings)
    }

    /**
     * 回忆会话播报（ISSUE-006；PRD §2.4.2、§2.4.3）：
     * - 英→中：单词 = 读音 +（可选）字母拼写；词组 = 读音 +（可选）中文翻译（默认关）。
     * - 中→英：仅当「中→英-默认播报」开启时播英文读音（无拼写、无中文）。
     */
    fun buildRecall(word: Word, facet: Facet, settings: AppSettings): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()
        when (facet) {
            Facet.EN2ZH -> {
                if (word.text.isNotBlank()) {
                    segments += SpeechSegment(word.text, SpeechLang.EN, SEGMENT_PAUSE_MS)
                }
                if (word.isPhrase) {
                    if (settings.recallEn2ZhPhrasePlayTranslation && word.translation.isNotBlank()) {
                        segments += SpeechSegment(word.translation, SpeechLang.ZH, SEGMENT_PAUSE_MS)
                    }
                } else if (settings.recallEn2ZhWordPlaySpelling) {
                    word.text.forEach { ch ->
                        segments += SpeechSegment(ch.uppercaseChar().toString(), SpeechLang.EN, LETTER_PAUSE_MS)
                    }
                }
            }

            Facet.ZH2EN -> {
                if (settings.recallZh2EnAutoPlay && word.text.isNotBlank()) {
                    segments += SpeechSegment(word.text, SpeechLang.EN, SEGMENT_PAUSE_MS)
                }
            }

            Facet.AUDIO_SPELLING -> Unit // 听拼面不参与回忆会话（ADR 0006）
        }
        return segments.trimTailPause()
    }

    /**
     * 听写会话播报（ISSUE-009；PRD §2.5.2）：仅播单词读音。
     * 不拼字母、不播中文——否则直接泄题；计时不因脚本长度变化（计时起点=播报结束，播报失败回退展示时刻）。
     */
    fun buildDictation(word: Word): List<SpeechSegment> =
        if (word.text.isBlank()) emptyList()
        else listOf(SpeechSegment(word.text, SpeechLang.EN, 0))

    /**
     * 考察答错自动回放（ISSUE-009；PRD §2.5.2/§2.5.3 流程3、修订 #11、设置 correctionReplay）：
     * 听写答错 = 读音 + 字母拼写（词组 = 仅读音，PRD 未定义词组拼写回放）；默写答错 = 仅读音（§2.5.3）。
     * （2026-09-15 用户曾提「回放不拼字母」后自行撤回，维持 PRD 口径；遇到说法与 PRD 冲突先向用户确认再动代码。）
     * 多面同错按 中→英 顺序拼接，一次播完。
     */
    fun buildCorrectionReplay(word: Word, wrongFacets: List<Facet>, settings: AppSettings): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()
        wrongFacets.forEach { facet ->
            when (facet) {
                Facet.EN2ZH -> {
                    if (word.translation.isNotBlank()) {
                        segments += SpeechSegment(word.translation, SpeechLang.ZH, SEGMENT_PAUSE_MS)
                    }
                }

                Facet.AUDIO_SPELLING -> { // 听写答错：读音 + 字母拼写（词组仅读音）
                    if (word.text.isNotBlank()) {
                        segments += SpeechSegment(word.text, SpeechLang.EN, SEGMENT_PAUSE_MS)
                        if (!word.isPhrase) {
                            word.text.forEach { ch ->
                                segments += SpeechSegment(ch.uppercaseChar().toString(), SpeechLang.EN, LETTER_PAUSE_MS)
                            }
                        }
                    }
                }

                Facet.ZH2EN -> { // 默写答错：仅读音（PRD §2.5.3 流程3），不拼字母
                    if (word.text.isNotBlank()) {
                        segments += SpeechSegment(word.text, SpeechLang.EN, SEGMENT_PAUSE_MS)
                    }
                }
            }
        }
        return segments.trimTailPause()
    }

    private fun wordScript(word: Word, settings: AppSettings): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()
        if (settings.wordPlayPronunciation && word.text.isNotBlank()) {
            segments += SpeechSegment(word.text, SpeechLang.EN, SEGMENT_PAUSE_MS)
        }
        if (settings.wordPlaySpelling) {
            word.text.forEach { ch ->
                segments += SpeechSegment(ch.uppercaseChar().toString(), SpeechLang.EN, LETTER_PAUSE_MS)
            }
        }
        // 0012 C1：单词播报中文翻译（默认关，PRD 修订 #27）；段序 = 读音 → 拼写 → 翻译
        if (settings.wordPlayTranslation && word.translation.isNotBlank()) {
            segments += SpeechSegment(word.translation, SpeechLang.ZH, SEGMENT_PAUSE_MS)
        }
        return segments.trimTailPause()
    }

    private fun phraseScript(word: Word, settings: AppSettings): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()
        if (settings.phrasePlayPronunciation && word.text.isNotBlank()) {
            segments += SpeechSegment(word.text, SpeechLang.EN, SEGMENT_PAUSE_MS)
        }
        if (settings.phrasePlayTranslation && word.translation.isNotBlank()) {
            segments += SpeechSegment(word.translation, SpeechLang.ZH, SEGMENT_PAUSE_MS)
        }
        return segments.trimTailPause()
    }

    /** 末段无停顿（播完即止）。 */
    private fun MutableList<SpeechSegment>.trimTailPause(): List<SpeechSegment> {
        if (isNotEmpty()) this[lastIndex] = this[lastIndex].copy(pauseAfterMillis = 0)
        return this
    }
}
