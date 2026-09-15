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

/** Fake 播放器：记录操作序列（enqueue/cancelCurrent/clear/onDrained）供断言，不真正发声。 */
class FakeAudioPlayer : AudioPlayer {
    sealed interface Op {
        data class Enqueue(val audio: TtsAudio, val pauseAfterMillis: Long) : Op
        data object CancelCurrent : Op
        data object Clear : Op
        /** 注册排空通知（ISSUE-008 焦点③编排）。 */
        data object OnDrained : Op
    }

    val ops = mutableListOf<Op>()

    /** 当前 pending 的排空通知（单次语义；clear 取消）。 */
    private var drainAction: (() -> Unit)? = null

    val hasPendingDrain: Boolean get() = drainAction != null

    override fun enqueue(audio: TtsAudio, pauseAfterMillis: Long) {
        ops += Op.Enqueue(audio, pauseAfterMillis)
    }

    override fun cancelCurrent() {
        ops += Op.CancelCurrent
    }

    override fun clear() {
        ops += Op.Clear
        drainAction = null
    }

    override fun onDrained(action: () -> Unit) {
        ops += Op.OnDrained
        drainAction = action
    }

    /** 测试手动驱动：模拟队列自然播空。有 pending 则执行并返回 true，否则 false。 */
    fun simulateDrained(): Boolean {
        val action = drainAction ?: return false
        drainAction = null
        action()
        return true
    }
}
