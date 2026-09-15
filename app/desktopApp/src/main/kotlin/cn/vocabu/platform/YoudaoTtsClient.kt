package cn.vocabu.platform

import cn.vocabu.core.audio.TtsAudio
import cn.vocabu.core.audio.TtsCachePolicy
import cn.vocabu.core.audio.TtsClient
import cn.vocabu.core.audio.TtsVoice
import cn.vocabu.core.audio.YoudaoTtsUrl
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * 有道 dictvoice TTS 客户端（ISSUE-008 焦点①⑥，ADR-0005 接缝真实侧）：
 * - URL 构建/类型映射在 core [YoudaoTtsUrl]（实测口径：中文必须显式 type=1，缺省 500）
 * - HTTP：connect/read 各 3s；非 200 / 异常 / 空 body → null 静默（PRD §4.4 不抛到 UI）
 * - 两级缓存（焦点⑥）：key = sha256(text|voice) 16B hex + .mp3（core [TtsCachePolicy] 纯逻辑），
 *   单字母 → letters/ 永久；整词/词组 → words/ LRU 200（mtime = recency，命中 touch、写入后超限淘汰最旧）；
 *   LRU 决策在 core 单测覆盖，本类只做磁盘 IO 薄壳（焦点 TDD 划界）。
 * - 缓存存原始 MP3 字节（spike 实测 audio/mpeg，MPEG-1/2 LSF 混流），播放时 JLayer 解码。
 * - 断网：命中正常播、未命中静默跳过且流程不阻断（验收②）。
 */
class YoudaoTtsClient(private val cacheDir: Path) : TtsClient {

    init {
        Files.createDirectories(cacheDir.resolve(TtsCachePolicy.DIR_LETTERS))
        Files.createDirectories(cacheDir.resolve(TtsCachePolicy.DIR_WORDS))
    }

    override fun fetch(text: String, voice: TtsVoice): TtsAudio? {
        val file = cacheDir
            .resolve(TtsCachePolicy.kindOf(text))
            .resolve(TtsCachePolicy.fileName(text, voice))
        if (Files.isRegularFile(file)) {
            if (TtsCachePolicy.kindOf(text) == TtsCachePolicy.DIR_WORDS) touch(file)
            return TtsAudio(Files.readAllBytes(file), MIME)
        }
        val bytes = httpFetch(YoudaoTtsUrl.build(text, voice)) ?: return null
        store(file, bytes)
        return TtsAudio(bytes, MIME)
    }

    /** 直连下载（spike 证据：无特殊请求头，200 + audio/mpeg）。 */
    private fun httpFetch(url: String): ByteArray? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MILLIS
        conn.readTimeout = TIMEOUT_MILLIS
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.use { it.readBytes() }.takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** 原子落盘（tmp+move 防半截文件）+ words 目录超限淘汰。 */
    private fun store(file: Path, bytes: ByteArray) {
        try {
            val tmp = file.resolveSibling(file.name + ".tmp")
            Files.write(tmp, bytes)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            return // 缓存写失败不阻断播报（内存字节已可用）
        }
        if (TtsCachePolicy.kindOf(file.name.removeSuffix(".mp3")) != TtsCachePolicy.DIR_WORDS) return
        val dir = file.parent
        val entries = listEntries(dir)
        for (name in TtsCachePolicy.evictOverLimit(entries)) {
            try {
                Files.deleteIfExists(dir.resolve(name))
            } catch (_: IOException) {
                // 淘汰失败留待下次
            }
        }
    }

    /** words 目录快照：文件名 + mtime 作为 recency（.tmp 半截文件不参与）。 */
    private fun listEntries(dir: Path): List<TtsCachePolicy.CacheEntry> =
        dir.toFile().listFiles()
            ?.filter { it.isFile && it.extension == "mp3" }
            ?.map { TtsCachePolicy.CacheEntry(it.name, it.lastModified()) }
            ?: emptyList()

    /** 命中续命：更新 mtime = recency。 */
    private fun touch(file: Path) {
        try {
            file.toFile().setLastModified(System.currentTimeMillis())
        } catch (_: Exception) {
            // touch 失败仅影响淘汰优先级
        }
    }

    private companion object {
        const val MIME = "audio/mpeg"
        const val TIMEOUT_MILLIS = 3_000
    }
}
