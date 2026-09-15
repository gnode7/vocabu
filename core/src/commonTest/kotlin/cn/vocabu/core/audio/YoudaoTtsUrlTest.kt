package cn.vocabu.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * dictvoice URL 构建（ISSUE-008 TDD 划界）：type 映射（实测口径：AMERICAN→0、BRITISH/MANDARIN→1）、
 * percent-encode（中文逐字节、空格 %20、unreserved 原样）。
 */
class YoudaoTtsUrlTest {

    @Test
    fun `type映射_美音0_英音1_中文1`() {
        assertEquals(
            "https://dict.youdao.com/dictvoice?audio=apple&type=0",
            YoudaoTtsUrl.build("apple", TtsVoice.AMERICAN),
        )
        assertEquals(
            "https://dict.youdao.com/dictvoice?audio=apple&type=1",
            YoudaoTtsUrl.build("apple", TtsVoice.BRITISH),
        )
        // 中文必须显式 type=1（缺省 500，实测修正 issue 文档）
        assertEquals(
            "https://dict.youdao.com/dictvoice?audio=%E6%9C%9F%E5%BE%85&type=1",
            YoudaoTtsUrl.build("期待", TtsVoice.MANDARIN),
        )
    }

    @Test
    fun `encode_unreserved原样_空格与中文逐字节`() {
        assertEquals("apple", YoudaoTtsUrl.encode("apple"))
        assertEquals("A-z._~0", YoudaoTtsUrl.encode("A-z._~0"))
        assertEquals("look%20forward%20to", YoudaoTtsUrl.encode("look forward to"))
        // 期 E6 9C 9F、待 E5 BE 85
        assertEquals("%E6%9C%9F%E5%BE%85", YoudaoTtsUrl.encode("期待"))
        assertEquals("", YoudaoTtsUrl.encode(""))
    }
}
