package cn.vocabu.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RIFF chunk 解析（ISSUE-011）：fmt/data 抽取 + 一期 PCM 16bit 边界。 */
class WavRiffParseTest {

    @Test
    fun `标准PCM16bit_WAV解析出fmt与data定位`() {
        // 44 字节标准头 + 1600 样本（48kHz mono 16bit），与有道实测 P 格式同构
        val wav = wavBytes(sampleRate = 48000, channels = 1, audioFormat = 1, bits = 16, dataBytes = 3200)
        val info = DesktopAudioPlayer.parseWav(wav)!!
        assertEquals(1, info.audioFormat)
        assertEquals(1, info.channels)
        assertEquals(48000, info.sampleRate)
        assertEquals(16, info.bitsPerSample)
        assertEquals(44, info.dataOffset)
        assertEquals(3200, info.dataSize)
        assertTrue(info.pcm16bit)
    }

    @Test
    fun `chunk前带LIST元数据也能定位`() {
        // LIST chunk 在 fmt 之前（服务端 Lavf 编码常见），解析需跳过继续遍历
        val list = "LIST".toByteArray() + u32le(8) + "INFO".toByteArray() + "junk".toByteArray()
        val wav = "RIFF".toByteArray() + u32le(0) + "WAVE".toByteArray() +
            list +
            "fmt ".toByteArray() + u32le(16) + u16le(1) + u16le(2) + u32le(44100) + u32le(176400) + u16le(4) + u16le(16) +
            "data".toByteArray() + u32le(4) + byteArrayOf(1, 0, 0, 0)
        val info = DesktopAudioPlayer.parseWav(wav)!!
        assertEquals(44100, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(4, info.dataSize)
    }

    @Test
    fun `非PCM16格式解析出参数但不可播`() {
        // audioFormat=3（IEEE float，issue 列举的留痕跳段场景）
        val wav = wavBytes(sampleRate = 44100, channels = 2, audioFormat = 3, bits = 32, dataBytes = 800)
        val info = DesktopAudioPlayer.parseWav(wav)!!
        assertEquals(3, info.audioFormat)
        assertFalse(info.pcm16bit)
    }

    @Test
    fun `结构异常返回null`() {
        // 非 WAV（RIFF+AVI）、缺 data chunk、chunk size 超界（截断）
        val avi = "RIFF".toByteArray() + u32le(0) + "AVI ".toByteArray() + "fmt ".toByteArray()
        assertNull(DesktopAudioPlayer.parseWav(avi))
        val noData = "RIFF".toByteArray() + u32le(0) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + u32le(16) + u16le(1) + u16le(1) + u32le(48000) + u32le(96000) + u16le(2) + u16le(16)
        assertNull(DesktopAudioPlayer.parseWav(noData))
        val truncated = "RIFF".toByteArray() + u32le(0) + "WAVE".toByteArray() +
            "data".toByteArray() + u32le(9999) + byteArrayOf(0, 0) // data 声称 9999B 实际只有 2B
        assertNull(DesktopAudioPlayer.parseWav(truncated))
    }

    // ---- 合成工具 ----

    private fun wavBytes(sampleRate: Int, channels: Int, audioFormat: Int, bits: Int, dataBytes: Int): ByteArray {
        val blockAlign = channels * bits / 8
        val byteRate = sampleRate * blockAlign
        return "RIFF".toByteArray() + u32le(36 + dataBytes) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + u32le(16) + u16le(audioFormat) + u16le(channels) +
            u32le(sampleRate) + u32le(byteRate) + u16le(blockAlign) + u16le(bits) +
            "data".toByteArray() + u32le(dataBytes) + ByteArray(dataBytes)
    }

    private fun u16le(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), (v ushr 8 and 0xFF).toByte())

    private fun u32le(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        (v ushr 8 and 0xFF).toByte(),
        (v ushr 16 and 0xFF).toByte(),
        (v ushr 24 and 0xFF).toByte(),
    )
}
