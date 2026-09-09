package cn.vocabu.core.audio

/**
 * TTS 接缝（ADR-0005）：:core 只依赖接口，真实实现（有道 dictvoice + 两级缓存）在 ISSUE-008。
 */
enum class TtsVoice {
    AMERICAN,
    BRITISH,
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
 * 真实实现（javax.sound + 0.3/0.5s 间隔调度）在 ISSUE-008。
 */
interface AudioPlayer {
    /** 追加一段音频，pauseAfterMillis 为该段播完后的停顿（毫秒）。 */
    fun enqueue(audio: TtsAudio, pauseAfterMillis: Long = 0)

    /** 取消当前正在播放的一段（队列保留）。 */
    fun cancelCurrent()

    /** 停止当前并清空队列（离开页面 / 重新触发播报时用）。 */
    fun clear()
}
