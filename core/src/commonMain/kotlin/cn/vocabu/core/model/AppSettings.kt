package cn.vocabu.core.model

/** 应用设置（PRD §5.5），默认值即 PRD 规定值。单行存储（id=1），域模型不含 id。 */
data class AppSettings(
    /** 每日新词数（硬界 1~500，生效 = min(设置值, 剩余未学)） */
    val dailyNewWordCount: Int = 20,
    /** 每日缺面补齐数（0 = 关闭，硬界 0~50） */
    val facetCatchUpQuota: Int = 5,
    /** 初步记忆展示条数（硬界 1~今日新词数） */
    val memorizeDisplayCount: Int = 10,
    /** 选中自动播报 */
    val autoPlayOnSelect: Boolean = false,
    /** 单词播报读音 */
    val wordPlayPronunciation: Boolean = true,
    /** 单词播报字母拼写 */
    val wordPlaySpelling: Boolean = true,
    /** 词组播报读音 */
    val phrasePlayPronunciation: Boolean = true,
    /** 词组播报中文翻译 */
    val phrasePlayTranslation: Boolean = true,
    /** 回忆巩固展示条数（硬界 1~今日词数） */
    val recallDisplayCount: Int = 10,
    /** 英译中-单词播报拼写 */
    val recallEn2ZhWordPlaySpelling: Boolean = true,
    /** 英译中-词组播报翻译 */
    val recallEn2ZhPhrasePlayTranslation: Boolean = false,
    /** 中译英-自动播报英文 */
    val recallZh2EnAutoPlay: Boolean = false,
    /** 考察 Easy 阈值（秒，硬界 1~120） */
    val dictationEasyThreshold: Int = 5,
    /** 考察 Good 阈值（秒，硬界 Easy+1~300） */
    val dictationGoodThreshold: Int = 10,
    /** TTS 服务标识 */
    val ttsService: String = "youdao",
)
