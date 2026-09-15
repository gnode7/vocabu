package cn.vocabu.platform

import cn.vocabu.core.audio.AudioPlayer
import cn.vocabu.core.audio.TtsAudio
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.JavaLayerException
import javazoom.jl.decoder.SampleBuffer
import java.io.ByteArrayInputStream
import java.util.concurrent.LinkedBlockingQueue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * 桌面真实播放器（ISSUE-008 焦点①，javax.sound + JLayer）：
 * - 单播放 daemon 线程 + LinkedBlockingQueue 排队；段后停顿（0.5s/0.3s）由本线程 sleep 承载
 * - MP3 原始字节 → JLayer 解码为 PCM → SourceDataLine（采样率/声道按解码输出逐段开合，
 *   覆盖 MPEG-1 48k/44.1k 与 MPEG-2 LSF 24k 全矩阵——spike 实测）
 * - [cancelCurrent] 中断当前段播放与段后停顿，队列保留；被取消段按「已消费」处理
 * - [clear] 中断当前 + 清空队列 + 取消 pending 排空通知
 * - [onDrained] 单次：队列自然播空且无在播段时执行（播报结束计时联动的触发源，焦点③）
 * - 播/解失败静默跳段（PRD §4.4），不阻断后续段
 */
class DesktopAudioPlayer : AudioPlayer {

    private sealed interface Job {
        data class Segment(val audio: TtsAudio, val pauseAfterMillis: Long) : Job
    }

    private val queue = LinkedBlockingQueue<Job>()

    /** 在播段句柄：取消标志（@Volatile 跨线程可见，播放循环逐块检查）。 */
    @Volatile
    private var currentCancelled: Segment? = null

    /** pending 排空通知（单次；clear 取消）。 */
    @Volatile
    private var drainAction: (() -> Unit)? = null

    @Volatile
    private var shutdown = false

    private val worker = Thread {
        while (!shutdown) {
            val job = try {
                queue.take()
            } catch (_: InterruptedException) {
                break
            }
            if (job !is Job.Segment) continue
            playSegment(job)
            if (shutdown) break
            val action = drainAction
            if (queue.isEmpty() && action != null) {
                drainAction = null
                action()
            }
        }
    }.apply {
        name = "vocabu-audio"
        isDaemon = true
        start()
    }

    override fun enqueue(audio: TtsAudio, pauseAfterMillis: Long) {
        queue.put(Job.Segment(audio, pauseAfterMillis))
    }

    override fun cancelCurrent() {
        currentCancelled?.cancelled = true
    }

    override fun clear() {
        drainAction = null // 先撤通知：clear 期间播空的段不得再触发排空回调
        queue.clear()
        currentCancelled?.cancelled = true
    }

    override fun onDrained(action: () -> Unit) {
        drainAction = action
    }

    /** 播放单段：解码 → 写 Line → 段后停顿（均响应取消）。失败静默跳段（PRD §4.4）。 */
    private fun playSegment(seg: Job.Segment) {
        val cancelled = Segment()
        currentCancelled = cancelled
        var line: SourceDataLine? = null
        try {
            val stream = Bitstream(ByteArrayInputStream(seg.audio.data))
            try {
                val decoder = Decoder()
                val byteBuf = java.io.ByteArrayOutputStream(BUFFER_BYTES)
                while (true) {
                    if (cancelled.cancelled) break
                    val frame = stream.readFrame() ?: break
                    val output = decoder.decodeFrame(frame, stream) as SampleBuffer
                    stream.closeFrame()
                    val l = line ?: openLine(output.sampleFrequency, output.channelCount).also { line = it }
                    // SampleBuffer 输出 16bit 小端 PCM（short[]），转 byte[] 后写 Line
                    shortToLittleEndian(output.buffer, output.bufferLength, byteBuf)
                    val bytes = byteBuf.toByteArray()
                    l.write(bytes, 0, bytes.size)
                    byteBuf.reset()
                }
                line?.let {
                    // 取消分支 flush 丢弃 Line 内部缓冲（≤64KB，drain 会播完残音 ≤0.4s——复审顺带项）
                    if (cancelled.cancelled) it.flush() else it.drain()
                }
            } finally {
                line?.close()
                try {
                    stream.close()
                } catch (_: JavaLayerException) {
                    // 流关闭失败无需处理
                }
            }
            if (!cancelled.cancelled) pauseInterruptible(seg.pauseAfterMillis, cancelled)
        } catch (_: Exception) {
            line?.close() // Line 开启/写入失败等：静默跳段，不阻断队列
        } finally {
            currentCancelled = null
        }
    }

    /** 16bit PCM short[] → 小端 byte[]（复用缓冲）。 */
    private fun shortToLittleEndian(pcm: ShortArray, length: Int, out: java.io.ByteArrayOutputStream) {
        for (i in 0 until length) {
            val s = pcm[i]
            out.write(s.toInt() and 0xFF)
            out.write(s.toInt() shr 8 and 0xFF)
        }
    }

    private fun openLine(sampleRate: Int, channels: Int): SourceDataLine {
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format, BUFFER_BYTES)
        line.start()
        return line
    }

    /** 分段休眠（每步可感知取消）。 */
    private fun pauseInterruptible(millis: Long, cancelled: Segment) {
        var remaining = millis
        while (remaining > 0 && !cancelled.cancelled && !shutdown) {
            val step = minOf(PAUSE_STEP_MS, remaining)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            remaining -= step
        }
    }

    private class Segment {
        @Volatile
        var cancelled: Boolean = false
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val PAUSE_STEP_MS = 50L
    }
}
