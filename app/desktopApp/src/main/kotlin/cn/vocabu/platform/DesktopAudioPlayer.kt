package cn.vocabu.platform

import cn.vocabu.core.audio.AudioFormatSniff
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
 * - WAV（RIFF/WAVE）→ 手写 RIFF chunk 解析（ISSUE-011，2026-09-16：有道对大写 G/P 返回
 *   WAV 却谎报 audio/mpeg 头，JLayer 对 WAV 静默空播 → 拼写丢字母）；一期仅 PCM 16bit
 *   （[parseWav] + [playWav]），复用 openLine 直写 data chunk，与 JLayer 输出路径同构
 * - [cancelCurrent] 中断当前段播放与段后停顿，队列保留；被取消段按「已消费」处理
 * - [clear] 中断当前 + 清空队列 + 取消 pending 排空通知
 * - [onDrained] 单次：队列自然播空且无在播段时执行（播报结束计时联动的触发源，焦点③）
 * - 播/解失败静默跳段（PRD §4.4），不阻断后续段；失败留痕 stderr（ISSUE-011 顺手项：
 *   此前播放层零留痕，丢字母排障只能靠外部实测）
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

    /**
     * 播放单段：容器嗅探分流（core [AudioFormatSniff.isWav]，不信任响应头）→ 播放 →
     * 段后停顿（均响应取消）。解码失败静默跳段（PRD §4.4）并留痕 stderr（0013 ASCII 口径）。
     */
    private fun playSegment(seg: Job.Segment) {
        val cancelled = Segment()
        currentCancelled = cancelled
        try {
            if (AudioFormatSniff.isWav(seg.audio.data)) {
                playWav(seg, cancelled)
            } else {
                playMp3(seg, cancelled)
            }
            if (!cancelled.cancelled) pauseInterruptible(seg.pauseAfterMillis, cancelled)
        } catch (e: Exception) {
            // Line 开启/写入/解码异常：静默跳段不阻断队列，留痕供取证（ISSUE-011 顺手项）
            System.err.println(
                "[vocabu-audio] segment decode FAIL bytes=${seg.audio.data.size} " +
                    "wav=${AudioFormatSniff.isWav(seg.audio.data)} " +
                    asciiEscape("${e.javaClass.simpleName}: ${e.message ?: "no message"}"),
            )
        } finally {
            currentCancelled = null
        }
    }

    /** MP3 播放：JLayer 解码为 PCM → 写 Line（原 ISSUE-008 路径）。 */
    private fun playMp3(seg: Job.Segment, cancelled: Segment) {
        var line: SourceDataLine? = null
        var decodedFrames = 0
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
                decodedFrames++
            }
            // 零有效帧（坏数据特征：截断/垃圾字节/伪装 MP3）——与 ISSUE-011 死亡链路同型，留痕不再哑吞
            if (decodedFrames == 0 && !cancelled.cancelled) {
                System.err.println(
                    "[vocabu-audio] segment decode FAIL bytes=${seg.audio.data.size} reason=zero-valid-mp3-frame head=${headHex(seg.audio.data)}",
                )
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
    }

    /**
     * WAV 播放（ISSUE-011 一期）：[parseWav] 抽取 fmt/data → 仅 PCM 16bit 可播，
     * 复用 openLine 直写 data chunk（16bit 小端，与 JLayer 输出同构）；
     * 其余格式（float=3/extensible=0xFFFE/8bit）或结构异常：留痕跳段，观察实际面再扩。
     */
    private fun playWav(seg: Job.Segment, cancelled: Segment) {
        val info = parseWav(seg.audio.data)
        if (info == null) {
            System.err.println(
                "[vocabu-audio] wav parse FAIL bytes=${seg.audio.data.size} head=${headHex(seg.audio.data)}",
            )
            return
        }
        if (!info.pcm16bit) {
            System.err.println(
                "[vocabu-audio] wav SKIP unsupported fmt=${info.audioFormat} bits=${info.bitsPerSample} bytes=${seg.audio.data.size}",
            )
            return
        }
        val line = openLine(info.sampleRate, info.channels)
        try {
            val end = info.dataOffset + info.dataSize
            val buf = ByteArray(minOf(BUFFER_BYTES, info.dataSize))
            var pos = info.dataOffset
            while (pos < end) {
                if (cancelled.cancelled) break
                val n = minOf(buf.size, end - pos)
                System.arraycopy(seg.audio.data, pos, buf, 0, n)
                line.write(buf, 0, n)
                pos += n
            }
            if (cancelled.cancelled) line.flush() else line.drain()
        } finally {
            line.close()
        }
    }

    /** 前 8 字节 hex（留痕定位用）。 */
    private fun headHex(data: ByteArray): String =
        data.take(8).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

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

    companion object {
        /** RIFF/WAVE 解析结果（fmt + data 定位；pcm16bit = 一期可播边界）。 */
        internal data class WavInfo(
            val audioFormat: Int,
            val channels: Int,
            val sampleRate: Int,
            val bitsPerSample: Int,
            val dataOffset: Int,
            val dataSize: Int,
        ) {
            val pcm16bit: Boolean get() = audioFormat == 1 && bitsPerSample == 16
        }

        /**
         * 遍历 RIFF chunk 抽取 fmt/data（纯函数，可单测）：
         * chunk 布局 [4B id][4B size][payload]，size 奇数含 1B pad；fmt 内
         * audioFormat(0-1)/channels(2-3)/sampleRate(4-7)/bitsPerSample(14-15)。
         * 非 WAV / 缺 fmt 或 data / chunk size 超界 → null。
         */
        internal fun parseWav(bytes: ByteArray): WavInfo? {
            if (!AudioFormatSniff.isWav(bytes)) return null
            var format = -1
            var channels = -1
            var sampleRate = -1
            var bits = -1
            var dataOffset = -1
            var dataSize = -1
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = u32le(bytes, pos + 4)
                val payload = pos + 8
                if (size < 0 || payload.toLong() + size > bytes.size) return null // 超界防御
                when (id) {
                    "fmt " -> {
                        if (size < 16) return null
                        format = u16le(bytes, payload)
                        channels = u16le(bytes, payload + 2)
                        sampleRate = u32le(bytes, payload + 4)
                        bits = u16le(bytes, payload + 14)
                    }
                    "data" -> {
                        dataOffset = payload
                        dataSize = size
                    }
                }
                if (format >= 0 && dataOffset >= 0) break
                pos = payload + size + (size and 1) // 偶对齐 pad
            }
            if (format < 0 || dataOffset < 0) return null
            return WavInfo(format, channels, sampleRate, bits, dataOffset, dataSize)
        }

        private fun u16le(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun u32le(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        private const val BUFFER_BYTES = 64 * 1024
        private const val PAUSE_STEP_MS = 50L
    }
}
