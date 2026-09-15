package cn.vocabu.platform

/**
 * 留痕 ASCII 化（0013 方案A，匠人复审口径）：可打印 ASCII（0x20-0x7E）原样，其余转 \uXXXX
 * （含中文与控制字符）。Windows GBK 控制台下中文留痕显示为乱码（锟斤拷），纯 ASCII 才保证
 * 取证可读，且 ASCII 是所有代码页的公共子集——零配置依赖（不押在用户切 chcp 65001 上），
 * 转义后中文可按码点反查。落点：main.kt 的 SpeechController logger 闭包统一兜底
 * （一处兜住 core 全部 logger 输出，含未来新增），YoudaoTtsClient 直写 stderr 处单独调用。
 */
fun asciiEscape(s: String): String = buildString {
    for (ch in s) {
        if (ch.code in 0x20..0x7E) append(ch)
        else append("\\u" + ch.code.toString(16).padStart(4, '0').uppercase())
    }
}
