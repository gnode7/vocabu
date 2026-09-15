package cn.vocabu.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 缓存策略纯逻辑（ISSUE-008 焦点⑥）：sha256 摘要、key 格式、字母/词分流、LRU 容量边界与重访续命。
 */
class TtsCachePolicyTest {

    @Test
    fun `sha256_标准向量`() {
        // NIST FIPS 180-4 标准向量
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TtsCachePolicy.hex(Sha256.digest("abc".encodeToByteArray())),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            TtsCachePolicy.hex(Sha256.digest(ByteArray(0))),
        )
        // 长消息（跨越多块填充）
        assertEquals(
            "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            TtsCachePolicy.hex(Sha256.digest("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu".encodeToByteArray())),
        )
    }

    @Test
    fun `key_同一文本音色稳定_不同输入不同_无文件系统非法字符`() {
        val k1 = TtsCachePolicy.cacheKey("apple", TtsVoice.AMERICAN)
        val k2 = TtsCachePolicy.cacheKey("apple", TtsVoice.AMERICAN)
        assertEquals(k1, k2)
        assertTrue(k1 != TtsCachePolicy.cacheKey("apple", TtsVoice.BRITISH))
        assertTrue(k1 != TtsCachePolicy.cacheKey("期待", TtsVoice.MANDARIN))
        assertEquals(32, k1.length) // 16B hex
        assertTrue(k1.all { it in "0123456789abcdef" })
        assertEquals("$k1.mp3", TtsCachePolicy.fileName("apple", TtsVoice.AMERICAN))
    }

    @Test
    fun `分流_单字母永久_词与中文释义LRU`() {
        assertEquals(TtsCachePolicy.DIR_LETTERS, TtsCachePolicy.kindOf("A"))
        assertEquals(TtsCachePolicy.DIR_WORDS, TtsCachePolicy.kindOf("apple"))
        assertEquals(TtsCachePolicy.DIR_WORDS, TtsCachePolicy.kindOf("look forward to"))
        assertEquals(TtsCachePolicy.DIR_WORDS, TtsCachePolicy.kindOf("期待"))
    }

    @Test
    fun `LRU_未超限不淘汰_超限淘汰最旧`() {
        fun entry(name: String, used: Long) = TtsCachePolicy.CacheEntry(name, used)
        val entries = (1L..5L).map { entry("f$it", it) }
        assertTrue(TtsCachePolicy.evictOverLimit(entries, max = 5).isEmpty())
        // 容量 3：淘汰最旧的 f1、f2（lastUsed 最小者先走）
        assertEquals(listOf("f1", "f2"), TtsCachePolicy.evictOverLimit(entries, max = 3))
    }

    @Test
    fun `LRU_重访续命_最近命中的不被淘汰`() {
        val entries = listOf(
            TtsCachePolicy.CacheEntry("old", 100),
            TtsCachePolicy.CacheEntry("touched", 900), // 重访 touch 后 recency 更新
            TtsCachePolicy.CacheEntry("mid", 500),
            TtsCachePolicy.CacheEntry("mid2", 400),
        )
        // 容量 3 → 淘汰 recency 最小者（old=100），touched 虽入库早但重访续命保留
        assertEquals(listOf("old"), TtsCachePolicy.evictOverLimit(entries, max = 3))
    }

    @Test
    fun `LRU_默认容量200_空表安全`() {
        val entries = (1L..250L).map { TtsCachePolicy.CacheEntry("w$it", it) }
        val evicted = TtsCachePolicy.evictOverLimit(entries)
        assertEquals(50, evicted.size)
        assertEquals((1L..50L).map { "w$it" }, evicted) // 最旧的 50 个
        assertTrue(TtsCachePolicy.evictOverLimit(emptyList()).isEmpty())
    }
}
