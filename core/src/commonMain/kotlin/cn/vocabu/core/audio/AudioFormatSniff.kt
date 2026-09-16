package cn.vocabu.core.audio

/**
 * 容器格式嗅探（ISSUE-011，2026-09-16）：
 * 有道 dictvoice 对大写 G/P 返回 RIFF/WAVE body 且 Content-Type 谎报 audio/mpeg，
 * 响应头不可信——播放层按字节魔数自判容器，WAV 走专用解析，MP3 走 JLayer。
 *
 * 魔数口径：offset 0-3 = "RIFF" 且 offset 8-11 = "WAVE"；不足 12 字节返回 false
 * （短字节流/空数据一律按非 WAV 交由 MP3 路径兜底失败留痕）。
 */
object AudioFormatSniff {

    fun isWav(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()
    }
}
