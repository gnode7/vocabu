package cn.vocabu.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/** 留痕 ASCII 化（0013 方案A）：可打印原样、非 ASCII 转义、空格保留、控制字符转义。 */
class AsciiEscapeTest {

    @Test
    fun `ascii原样_中文与控制字符转义`() {
        assertEquals("apple look up", asciiEscape("apple look up"))
        assertEquals("A-z._~0", asciiEscape("A-z._~0"))
        // 苹 U+82F9、果 U+679C
        assertEquals("\\u82F9\\u679C", asciiEscape("苹果"))
        assertEquals("", asciiEscape(""))
        // 混合：英文原样 + 中文转义（模拟 core logger 行输出）
        assertEquals(
            "\\u6BB5\\u62C9\\u53D6\\u5931\\u8D25 text=\\u82F9\\u679C",
            asciiEscape("段拉取失败 text=苹果"),
        )
        // 控制字符与空格：空格保留、换行转义
        assertEquals("a\\u000Ab", asciiEscape("a\nb"))
        assertEquals("a b", asciiEscape("a b"))
    }
}
