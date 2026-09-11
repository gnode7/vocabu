package cn.vocabu.core.audio

import cn.vocabu.core.logic.SpeechSegment

/**
 * 播控（ISSUE-005；PRD §4.4 播报控制，ADR-0005 接缝）：
 * - 触发播报 = 先清空队列再按脚本入队（重复触发/切换选中 = 取消当前重来）
 * - 离开页面 = [stop] 全取消
 * - TTS 失败段静默跳过，不阻断后续段（PRD §4.4）
 * - 段间停顿由 [AudioPlayer.enqueue] 的 pauseAfterMillis 承载（真实计时在 ISSUE-008 播放器）
 */
class SpeechController(
    private val tts: TtsClient,
    private val player: AudioPlayer,
) {
    /** 触发播报：取消当前（清空），按脚本顺序入队。 */
    fun speak(segments: List<SpeechSegment>) {
        player.clear()
        segments.forEach { seg ->
            // 失败静默：fetch 返回 null 则跳过该段（含其停顿），继续后续段
            tts.fetch(seg.text, TtsVoice.AMERICAN)?.let { audio ->
                player.enqueue(audio, seg.pauseAfterMillis)
            }
        }
    }

    /** 离开页面 / 显式取消：停止并清空全部播报。 */
    fun stop() {
        player.clear()
    }
}
