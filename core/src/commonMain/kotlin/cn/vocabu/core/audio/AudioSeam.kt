package cn.vocabu.core.audio

/**
 * TTS 接缝（ADR-0005）：:core 只依赖接口。
 * ISSUE-008 真实实现（有道 dictvoice + 两级缓存）落 desktopApp/platform。
 */
enum class TtsVoice {
    AMERICAN,
    BRITISH,
    /** 中文段语义音色（SpeechLang.ZH → MANDARIN）。实测映射 type=1（见 [YoudaoTtsUrl]）。 */
    MANDARIN,
}

/** 合成结果音频。 */
class TtsAudio(val data: ByteArray, val mimeType: String)

interface TtsClient {
    /**
     * 合成语音。失败静默（返回 null），不抛异常到 UI 层（PRD §4.4）。
     */
    fun fetch(text: String, voice: TtsVoice): TtsAudio?
}

/**
 * 播放器接缝：排队播报、间隔、取消。
 * 真实实现（JLayer 解码 + 单播放线程）在 desktopApp/platform DesktopAudioPlayer。
 */
interface AudioPlayer {
    /** 追加一段音频，pauseAfterMillis 为该段播完后的停顿（毫秒）。 */
    fun enqueue(audio: TtsAudio, pauseAfterMillis: Long = 0)

    /** 取消当前正在播放的一段（队列保留）。 */
    fun cancelCurrent()

    /** 停止当前并清空队列（离开页面 / 重新触发播报时用）；同时取消 pending 的排空通知。 */
    fun clear()

    /**
     * 注册排空通知（ISSUE-008 焦点③）：单次——队列自然播空且无在播段时执行 action；
     * [clear] 取消 pending。调用方须在 enqueue 之前注册。
     */
    fun onDrained(action: () -> Unit)
}
