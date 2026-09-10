package cn.vocabu.core.logic

import cn.vocabu.core.fake.InMemoryWordRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class WordbookImporterTest {

    private val now = Instant.fromEpochSeconds(1_000_000)

    private fun rows(vararg data: List<String?>): List<List<String?>> =
        listOf(listOf("单词", "词性", "翻译")) + data.toList()

    @Test
    fun `导入成功计数与落库`() {
        val repo = InMemoryWordRepository()
        val importer = WordbookImporter(repo)

        val report = importer.import(
            rows(listOf("apple", "n.", "苹果"), listOf("look up", null, "查阅")),
            now = now,
        )

        assertEquals(2, report.successCount)
        assertEquals(0, report.duplicatedTexts.size)
        assertEquals(0, report.failures.size)
        val all = repo.getAll()
        assertEquals(2, all.size)
        assertEquals("apple", all[0].text)
        assertTrue(!all[0].isPhrase)         // apple 是单词
        assertTrue(all[1].isPhrase)          // look up 是词组
        assertEquals("", all[1].pos)         // 词组 pos 归一化为空串（ADR 0007）
        assertEquals(now, all[0].createdAt)  // createdAt/updatedAt = now
    }

    @Test
    fun `同text异pos共存 归一化后同键跳过`() {
        val repo = InMemoryWordRepository()
        val importer = WordbookImporter(repo)

        val report = importer.import(
            rows(
                listOf("record", "n.", "记录"),
                listOf("RECORD", "V", "录制"),   // 异 pos → 共存
                listOf("record", " N. ", "又一条记录"), // 归一化 (record,n) 已存在 → 跳过
            ),
            now = now,
        )

        assertEquals(2, report.successCount)
        assertEquals(1, report.duplicatedCount)
        assertEquals(listOf("record"), report.duplicatedTexts)
        assertEquals(2, repo.getAll().size)
        assertEquals("n", repo.getAll()[0].pos) // 归一化存储
        assertEquals("v", repo.getAll()[1].pos)
    }

    @Test
    fun `重复词归一化同键跳过且不算错误`() {
        val repo = InMemoryWordRepository()
        repo.add(cn.vocabu.core.model.Word.of("Apple", null, null, "苹果", now, now)) // (apple, "")
        val importer = WordbookImporter(repo)

        val report = importer.import(
            rows(
                listOf("apple", null, "苹果"),        // 归一化同键 (apple,"") → 跳过
                listOf("APPLE", "n.", "水果"),        // (apple,n) 异 pos → 共存
                listOf("banana", null, "香蕉"),
            ),
            now = now,
        )

        assertEquals(2, report.successCount)
        assertEquals(listOf("apple"), report.duplicatedTexts)
        assertEquals(0, report.failures.size)
        assertEquals(3, repo.getAll().size) // Apple(空词性) + APPLE/n + banana
    }

    @Test
    fun `格式错误行收集行号与原因且不中断导入`() {
        val repo = InMemoryWordRepository()
        val importer = WordbookImporter(repo)

        val report = importer.import(
            rows(
                listOf(null, "n.", "没有单词"),
                listOf("apple", "n.", "苹果"),
                listOf("pear", null, "  "),
            ),
            now = now,
        )

        assertEquals(1, report.successCount)
        assertEquals(2, report.failures.size)
        assertEquals(2, report.failures[0].lineNo)
        assertEquals(4, report.failures[1].lineNo)
        assertTrue(report.failures[0].reason.contains("单词"))
        assertTrue(report.failures[1].reason.contains("翻译"))
    }

    @Test
    fun `空表导入全零报告`() {
        val report = WordbookImporter(InMemoryWordRepository()).import(
            listOf(listOf("单词", "词性", "翻译")),
            now = now,
        )

        assertEquals(0, report.successCount)
        assertEquals(0, report.duplicatedTexts.size)
        assertEquals(0, report.failures.size)
    }
}
