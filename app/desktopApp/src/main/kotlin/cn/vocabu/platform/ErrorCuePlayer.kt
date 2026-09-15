package cn.vocabu.platform

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

/**
 * 告警音（ISSUE-008 焦点⑤，PRD 无音源规格 → 合成短 beep，无资源文件依赖）：
 * 200ms 正弦 880→660Hz 线性滑频，独立 SourceDataLine；daemon 线程播放防卡 UI。
 * 时序：errorCue 先响，答错回放经 SpeechController startDelay=200ms 让位。
 */
class ErrorCuePlayer {

    /** 立即触发（非阻塞：合成 PCM 极快，播放走 daemon 线程）。 */
    fun play() {
        Thread {
            try {
                val pcm = ShortArray((SAMPLE_RATE * DURATION_SECONDS).toInt())
                for (i in pcm.indices) {
                    val t = i / SAMPLE_RATE.toDouble()
                    val freq = FREQ_START + (FREQ_END - FREQ_START) * (t / DURATION_SECONDS)
                    // 末段淡出防爆音
                    val fade = minOf(1.0, (pcm.size - i) / (SAMPLE_RATE * 0.02))
                    pcm[i] = (sin(2 * PI * freq * t) * AMPLITUDE * fade).toInt().toShort()
                }
                val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
                AudioSystem.getSourceDataLine(format).use { line ->
                    line.open(format)
                    line.start()
                    val bytes = ByteArray(pcm.size * 2)
                    pcm.forEachIndexed { i, s ->
                        bytes[i * 2] = (s.toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = (s.toInt() shr 8).toByte()
                    }
                    line.write(bytes, 0, bytes.size)
                    line.drain()
                    line.close()
                }
            } catch (_: Exception) {
                // 无音频设备等：告警音静默放弃，批改流程不阻断
            }
        }.apply {
            name = "vocabu-error-cue"
            isDaemon = true
        }.start()
    }

    private companion object {
        const val SAMPLE_RATE = 44_100.0
        const val DURATION_SECONDS = 0.2
        const val FREQ_START = 880.0
        const val FREQ_END = 660.0
        const val AMPLITUDE = 8_000
    }
}
