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
 *
 * Windows 无声修复（0013，2026-09-15 用户复测）：9f6518b 重试后用户侧仍是「读音播完
 * 后提交必无声」且无打开失败留痕——open 成功、无异常、听不到，取证盲区在写入/排空段。
 * 匠人定性（22:06）：Windows 音频设备在最后一个客户端关闭后进 suspend，重新激活耗时
 * 超过原 150ms 重试窗口——「读音播放中提交能响（设备 active）、播完后提交不响（设备刚挂起）」。
 * 对策三件套（证据导向）：重试窗口 150ms→1s（对冲 suspend 重激活）+ 显式小缓冲开线
 * （降低短音滞留面）+ drain 后补偿等待再关线（对冲 Windows drain 提前返回丢缓冲）。
 * 全路径留痕——drain 耗时是关键诊断位：正常 ≈ 缓冲内音频时长扣 write 期消耗（16KB 小缓冲
 * 下 write 已耗 ~14ms，即 ≈170-190ms/200ms 音频），若 <20ms
 * 即坐实 drain 提前返回；open 每次失败即时留痕（区分竞态与必败）。
 * 留痕标签英文化（0013）：Windows GBK 控制台下中文标签乱码毁取证口径，结构字段全 ASCII。
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
                    System.err.println(
                        "[vocabu] error-cue line open FAIL (attempt=$attempt${if (attempt >= MAX_ATTEMPTS) ", abandon" else ", retry"})",
                    )
                    if (attempt >= MAX_ATTEMPTS) return@Thread
                    if (!sleepBeforeRetry()) return@Thread
                    continue
                }
                try {
                    line.use {
                        it.write(bytes, 0, bytes.size)
                        val drainStart = System.nanoTime()
                        it.drain()
                        val drainMs = (System.nanoTime() - drainStart) / 1_000_000
                        // 关键诊断位：正常 ≈170-190ms（200ms 音频扣 write 期 ~14ms）；<20ms 即 drain 提前返回实锤
                        System.err.println("[vocabu] error-cue played, drain=${drainMs}ms")
                        Thread.sleep(POST_DRAIN_MS) // 补偿等待：确保设备消耗完缓冲再 close（0013）
                    }
                } catch (e: Exception) {
                    // 写入/排空中断：不重试（避免重复出声），批改流程不阻断；0013 起留痕不再静默
                    System.err.println("[vocabu] error-cue write/drain FAIL (cls=${e.javaClass.simpleName}): ${asciiEscape(e.message ?: "null")}")
                }
                return@Thread
            }
        }.apply {
            name = "vocabu-error-cue"
            isDaemon = true
        }.start()
    }

    /** 打开 + 启动音频线（显式小缓冲，0013：降低短音全量滞留缓冲的面）；失败返回 null（可重试）。 */
    private fun openLine(): SourceDataLine? = try {
        AudioSystem.getSourceDataLine(FORMAT).also {
            it.open(FORMAT, LINE_BUFFER_BYTES)
            it.start()
        }
    } catch (e: Exception) {
        // 无音频设备 / Line 短暂不可用（TTS 线刚关闭的竞态窗口）等：留痕原因后走重试
        System.err.println("[vocabu] error-cue line open reason (cls=${e.javaClass.simpleName}): ${asciiEscape(e.message ?: "null")}")
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
        /** 0013：10 次 × 100ms = 1s 重试窗，对冲 Windows 设备 suspend 重激活耗时。 */
        const val MAX_ATTEMPTS = 10
        const val RETRY_DELAY_MS = 100L

        /** 显式开线缓冲（0013）：16KB ≈ 186ms@44.1kHz/16bit/mono，小于 beep 时长防全量滞留。 */
        const val LINE_BUFFER_BYTES = 16 * 1024

        /** drain 后补偿等待（0013）：close 前给设备消耗缓冲的时间，对冲 Windows drain 提前返回。 */
        const val POST_DRAIN_MS = 250L
    }
}
