package cn.vocabu.core.audio

/**
 * 有道 dictvoice URL 构建（ISSUE-008 焦点 TDD 划界：纯函数可单测）。
 *
 * type 参数映射（2026-09-15 curl 实测，修正 issue 文档；证据 shared/vocabu-patches/issue008-spike-evidence.md）：
 * - AMERICAN → type=0，BRITISH → type=1，MANDARIN → type=1
 * - 中文必须显式带 type=1，type 缺省直接 HTTP 500（issue 原文「中文直接传」写法不成立）
 * - accent 方向（0=美音 1=英音）待用户耳验，反了一行改映射
 */
object YoudaoTtsUrl {

    private const val BASE = "https://dict.youdao.com/dictvoice"

    /** 构建 dictvoice 请求 URL：audio 参数 UTF-8 percent-encode，type 按音色映射。 */
    fun build(text: String, voice: TtsVoice): String =
        "$BASE?audio=${encode(text)}&type=${typeParam(voice)}"

    private fun typeParam(voice: TtsVoice): Int = when (voice) {
        TtsVoice.AMERICAN -> 0
        TtsVoice.BRITISH -> 1
        TtsVoice.MANDARIN -> 1
    }

    /**
     * RFC 3986 percent-encode（纯 Kotlin，跨平台）：
     * unreserved（字母数字与 -._~）原样，其余按 UTF-8 字节逐字节 %XX——
     * 空格 → %20、中文逐字节编码，与 java URLEncoder 的表单风格（+ 等）不同但对该接口等价可用。
     */
    internal fun encode(text: String): String {
        val sb = StringBuilder()
        for (byte in text.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            when {
                b in 'a'.code..'z'.code || b in 'A'.code..'Z'.code || b in '0'.code..'9'.code ||
                    b == '-'.code || b == '.'.code || b == '_'.code || b == '~'.code ->
                    sb.append(b.toChar())
                else -> {
                    sb.append('%')
                    sb.append(HEX[b ushr 4 and 0xF])
                    sb.append(HEX[b and 0xF])
                }
            }
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
