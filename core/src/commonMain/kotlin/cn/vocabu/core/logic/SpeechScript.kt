package cn.vocabu.core.logic

import cn.vocabu.core.model.AppSettings
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
 * - 单词默认：读音 → 0.5s → 字母拼写（每字母单独一段，字母间 0.3s）
 * - 词组默认：读音 → 0.5s → 中文翻译
 * - 播报内容按设置多选裁剪；全关时返回空脚本（不播）。
 */
object SpeechScriptBuilder {

    /** 段间停顿：各内容段之间 0.5s（PRD §4.3）。 */
    private const val SEGMENT_PAUSE_MS = 500L

    /** 字母间停顿：逐字母拼读 0.3s（PRD §4.2）。 */
    private const val LETTER_PAUSE_MS = 300L

    fun build(word: Word, settings: AppSettings): List<SpeechSegment> {
        return if (word.isPhrase) phraseScript(word, settings) else wordScript(word, settings)
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
