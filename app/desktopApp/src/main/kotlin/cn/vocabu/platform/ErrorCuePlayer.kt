package cn.vocabu.platform

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.sin

/**
 * 告警音（ISSUE-008 焦点⑤，PRD 无音源规格 → 合成短 beep，无资源文件依赖）：
 * 200ms 正弦 880→660Hz 线性滑频，独立 SourceDataLine；daemon 线程播放防卡 UI。
 * 时序：errorCue 先响，答错回放经 SpeechController startDelay=200ms 让位。
 *
 * 打开重试（2026-09-15 用户反馈①）：批改回放常紧随自动读音播报结束触发，
 * TTS 线刚关闭时音频子系统（PipeWire/ALSA stream 生命周期）可能短暂拒绝新 Line，
 * 表现为「读音播放中提交告警音响、播完后提交不响」的间歇静默——打开阶段失败按
 * 50ms 间隔重试至多 3 次；写入/排空阶段失败不重试（避免重复出声），静默放弃不阻断批改。
 */
class ErrorCuePlayer {

    /** 立即触发（非阻塞：合成 PCM 极快，播放走 daemon 线程）。 */
    fun play() {
        Thread {
            val bytes = renderBytes()
            var attempt = 0
            while (true) {
                attempt++
                val line = openLine()
                if (line == null) {
                    if (attempt >= MAX_ATTEMPTS) {
                        System.err.println("[vocabu] error-cue 打开音频线失败（已重试 ${attempt - 1} 次），放弃")
                        return@Thread
                    }
                    if (!sleepBeforeRetry()) return@Thread
                    continue
                }
                try {
                    line.use {
                        it.write(bytes, 0, bytes.size)
                        it.drain()
                    }
                } catch (_: Exception) {
                    // 写入/排空中断：静默放弃（不重试，避免重复出声），批改流程不阻断
                }
                return@Thread
            }
        }.apply {
            name = "vocabu-error-cue"
            isDaemon = true
        }.start()
    }

    /** 打开 + 启动音频线；失败返回 null（打开阶段可重试，见类注释）。 */
    private fun openLine(): SourceDataLine? = try {
        AudioSystem.getSourceDataLine(FORMAT).also {
            it.open(FORMAT)
            it.start()
        }
    } catch (_: Exception) {
        // 无音频设备 / Line 短暂不可用（TTS 线刚关闭的竞态窗口）等：返回 null 走重试
        null
    }

    private fun sleepBeforeRetry(): Boolean = try {
        Thread.sleep(RETRY_DELAY_MS)
        true
    } catch (_: InterruptedException) {
        false
    }

    /** 合成 200ms 滑频 beep PCM（16bit 小端，末段淡出防爆音）。 */
    private fun renderBytes(): ByteArray {
        val pcm = ShortArray((SAMPLE_RATE * DURATION_SECONDS).toInt())
        for (i in pcm.indices) {
            val t = i / SAMPLE_RATE.toDouble()
            val freq = FREQ_START + (FREQ_END - FREQ_START) * (t / DURATION_SECONDS)
            // 末段淡出防爆音
            val fade = minOf(1.0, (pcm.size - i) / (SAMPLE_RATE * 0.02))
            pcm[i] = (sin(2 * PI * freq * t) * AMPLITUDE * fade).toInt().toShort()
        }
        val bytes = ByteArray(pcm.size * 2)
        pcm.forEachIndexed { i, s ->
            bytes[i * 2] = (s.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }
        return bytes
    }

    private companion object {
        val FORMAT = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        const val SAMPLE_RATE = 44_100.0
        const val DURATION_SECONDS = 0.2
        const val FREQ_START = 880.0
        const val FREQ_END = 660.0
        const val AMPLITUDE = 8_000
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 50L
    }
}
