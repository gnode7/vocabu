package cn.vocabu.core.fake

import cn.vocabu.core.audio.AudioPlayer
import cn.vocabu.core.audio.TtsAudio
import cn.vocabu.core.audio.TtsClient
import cn.vocabu.core.audio.TtsVoice

/**
 * Fake TTS：返回定长假音频；[failOnText] 命中的文本返回 null 模拟失败；
 * 记录全部请求供断言。
 */
class FakeTtsClient(
    private val failOnText: Set<String> = emptySet(),
) : TtsClient {
    val requests = mutableListOf<Pair<String, TtsVoice>>()

    override fun fetch(text: String, voice: TtsVoice): TtsAudio? {
        requests += text to voice
        if (text in failOnText) return null
        return TtsAudio(ByteArray(8) { it.toByte() }, "audio/mpeg")
    }
}

/** Fake 播放器：记录操作序列（enqueue/cancelCurrent/clear）供断言，不真正发声。 */
class FakeAudioPlayer : AudioPlayer {
    sealed interface Op {
        data class Enqueue(val audio: TtsAudio, val pauseAfterMillis: Long) : Op
        data object CancelCurrent : Op
        data object Clear : Op
    }

    val ops = mutableListOf<Op>()

    override fun enqueue(audio: TtsAudio, pauseAfterMillis: Long) {
        ops += Op.Enqueue(audio, pauseAfterMillis)
    }

    override fun cancelCurrent() {
        ops += Op.CancelCurrent
    }

    override fun clear() {
        ops += Op.Clear
    }
}
