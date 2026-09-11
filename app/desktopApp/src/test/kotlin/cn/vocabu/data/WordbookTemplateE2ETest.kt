package cn.vocabu.data

import cn.vocabu.core.logic.ImportTemplate
import cn.vocabu.core.logic.WordbookImporter
import cn.vocabu.platform.PoiExcelReader
import cn.vocabu.platform.PoiTemplateGenerator
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 端到端：运行时生成模板 → POI 读取 → 导入落库（往返一致，v1.2 #25 验收）。 */
class WordbookTemplateE2ETest {

    @Test
    fun `模板往返一致_生成文件可直接导入成功`() {
        val file = Files.createTempFile("vocabu-template", ".xlsx").toFile()
        try {
            assertTrue(PoiTemplateGenerator().write(file.absolutePath), "模板生成应成功")

            val rows = PoiExcelReader().read(file.absolutePath)
            assertNotNull(rows)
            assertEquals(ImportTemplate.HEADER, rows.first().map { it ?: "" }, "表头与模板口径同源")
            assertEquals(1 + ImportTemplate.SAMPLE_ROWS.size, rows.size)

            val db = VocabuDatabaseFactory.createInMemory()
            val wordRepo = WordRepositoryImpl(db)
            val report = WordbookImporter(wordRepo).import(rows, now = Instant.fromEpochSeconds(1_000_000))

            assertEquals(2, report.successCount, "两条示例行都应导入：${report.failures}")
            assertEquals(0, report.duplicatedCount)
            assertEquals(0, report.failureCount)

            val saved = wordRepo.getAll()
            assertEquals(listOf("apple", "look up"), saved.map { it.text })
            assertEquals("n", saved[0].pos) // 归一化存储（trim/小写/去尾点，ADR 0007）
            assertEquals("ˈæpl", saved[0].phonetic)
            assertEquals("", saved[1].pos) // 词组：无词性无音标
        } finally {
            file.delete()
        }
    }
}
