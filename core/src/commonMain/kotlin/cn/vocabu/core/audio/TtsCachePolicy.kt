package cn.vocabu.core.audio

/**
 * SHA-256（FIPS 180-4，纯 Kotlin 跨平台）——缓存文件名哈希用。
 * key 不落地原文（防文件系统非法字符/中文超长），16 字节摘要 hex 32 字符。
 */
internal object Sha256 {

    fun digest(data: ByteArray): ByteArray {
        val h = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
        )
        // 消息填充：原数据 + 0x80 + 零 + 64bit 大端位长（卡在 56 mod 64）
        val paddedLen = ((data.size + 8) / 64 + 1) * 64
        val padded = ByteArray(paddedLen)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        val bits = data.size.toLong() * 8
        for (i in 0 until 8) padded[paddedLen - 1 - i] = (bits ushr (i * 8)).toByte()

        val w = IntArray(64)
        var block = 0
        while (block < paddedLen) {
            for (t in 0 until 16) {
                val o = block + t * 4
                w[t] = (padded[o].toInt() and 0xFF) shl 24 or
                    ((padded[o + 1].toInt() and 0xFF) shl 16) or
                    ((padded[o + 2].toInt() and 0xFF) shl 8) or
                    (padded[o + 3].toInt() and 0xFF)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
            block += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (h[i] ushr 24).toByte()
            out[i * 4 + 1] = (h[i] ushr 16).toByte()
            out[i * 4 + 2] = (h[i] ushr 8).toByte()
            out[i * 4 + 3] = h[i].toByte()
        }
        return out
    }

    private val K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )
}

/**
 * 两级缓存策略（ISSUE-008 焦点⑥，纯逻辑）：key 生成、字母/整词分流、LRU 淘汰决策。
 * 磁盘 IO（读写/touch mtime/删除）留 desktopApp 薄壳，本对象只做可单测的决策。
 */
object TtsCachePolicy {

    /** 整词/词组缓存容量（LRU）。 */
    const val WORDS_MAX_ENTRIES = 200

    /** 缓存子目录名：单字母（拼写段）永久缓存。 */
    const val DIR_LETTERS = "letters"

    /** 缓存子目录名：整词/词组 LRU。 */
    const val DIR_WORDS = "words"

    /** 缓存条目：文件名 + recency（desktop 侧用文件 mtime 承载）。 */
    data class CacheEntry(val name: String, val lastUsedMillis: Long)

    /** 字节数组 → 小写 hex。 */
    internal fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[b.toInt() ushr 4 and 0xF])
            sb.append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    /**
     * 缓存 key：sha256(text + "|" + voice.name) 取前 16 字节 hex（32 字符，无非法文件名字符）。
     */
    fun cacheKey(text: String, voice: TtsVoice): String =
        hex(Sha256.digest((text + "|" + voice.name).encodeToByteArray()).copyOfRange(0, 16))

    /**
     * 字母分流：单字符文本（脚本拼写段已 uppercase，A–Z 及变体）走 [DIR_LETTERS] 永久缓存，
     * 其余（整词/词组/中文释义）走 [DIR_WORDS] LRU。
     */
    fun kindOf(text: String): String =
        if (text.length == 1) DIR_LETTERS else DIR_WORDS

    /**
     * LRU 淘汰决策（仅 words 目录调用，letters 永久豁免）：
     * [entries] 超出容量时返回应删除的文件名（lastUsedMillis 最旧的先淘汰，保持超限前数量）。
     * 重访续命由 desktop 命中时 touch mtime 实现——本函数只看给定 recency。
     */
    fun evictOverLimit(entries: List<CacheEntry>, max: Int = WORDS_MAX_ENTRIES): List<String> {
        if (entries.size <= max) return emptyList()
        return entries
            .sortedBy { it.lastUsedMillis }
            .take(entries.size - max)
            .map { it.name }
    }

    /** 缓存文件名：key + 扩展名。 */
    fun fileName(text: String, voice: TtsVoice): String = cacheKey(text, voice) + ".mp3"

    private val HEX = "0123456789abcdef".toCharArray()
}
