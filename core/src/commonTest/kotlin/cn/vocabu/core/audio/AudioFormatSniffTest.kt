package cn.vocabu.core.audio

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 容器格式嗅探（ISSUE-011）：魔数判定，不信任响应头。 */
class AudioFormatSniffTest {

    @Test
    fun `RIFF+WAVE魔数识别为WAV_有道对P G实测口径`() {
        // 真实样本头结构（curl 实测 P.mp3）：RIFF + 4字节尺寸 + WAVE
        val wav = "RIFF".toByteArray() + byteArrayOf(0x24, 0x0D, 0x00, 0x00) + "WAVE".toByteArray() + "fmt ".toByteArray()
        assertTrue(AudioFormatSniff.isWav(wav))
    }

    @Test
    fun `RIFF但非WAVE不误判`() {
        val riff = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "AVI ".toByteArray() + "fmt ".toByteArray()
        assertFalse(AudioFormatSniff.isWav(riff))
    }

    @Test
    fun `MP3识别为非WAV_覆盖ID3与MPEG1_2同步字`() {
        // 字母 E 实测带 ID3v2 头；字母 L 实测为 MPEG-2 LSF（24k）
        assertFalse(AudioFormatSniff.isWav("ID3\u0004".toByteArray()))
        assertFalse(AudioFormatSniff.isWav(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)))
        assertFalse(AudioFormatSniff.isWav(byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x90.toByte(), 0x00)))
        assertFalse(AudioFormatSniff.isWav("OggS".toByteArray()))
    }

    @Test
    fun `短字节流返回false`() {
        assertFalse(AudioFormatSniff.isWav(ByteArray(0)))
        assertFalse(AudioFormatSniff.isWav("RIFF".toByteArray()))
        assertFalse(AudioFormatSniff.isWav(byteArrayOf(0x52, 0x49)))
    }
}
