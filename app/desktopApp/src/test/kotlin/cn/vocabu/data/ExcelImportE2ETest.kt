package cn.vocabu.data

import cn.vocabu.core.logic.WordbookImporter
import cn.vocabu.platform.PoiExcelReader
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/** 端到端：真实 .xlsx 文件 → POI 读取 → 解析 → 查重 → 落库（PRD §2.1.1 验收）。 */
class ExcelImportE2ETest {

    @Test
    fun `生成xlsx到POI读取到导入落库全链路`() {
        val file = Files.createTempFile("vocabu-import", ".xlsx").toFile()
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Sheet1")
            sheet.createRow(0).apply {
                createCell(0).setCellValue("单词")
                createCell(1).setCellValue("词性")
                createCell(2).setCellValue("翻译")
            }
            sheet.createRow(1).apply {
                createCell(0).setCellValue("apple")
                createCell(1).setCellValue("n.")
                createCell(2).setCellValue("苹果")
                createCell(3).setCellValue("ˈæpl")
            }
            sheet.createRow(2).apply {
                createCell(0).setCellValue("look up")
                createCell(2).setCellValue("查阅")
            }
            sheet.createRow(3) // 空行：静默跳过
            sheet.createRow(4).apply {
                createCell(1).setCellValue("n.") // 缺单词 → 格式错误
            }
            FileOutputStream(file).use { wb.write(it) }
        }

        val rows = PoiExcelReader().read(file.absolutePath)
        assertNotNull(rows)

        val db = VocabuDatabaseFactory.createInMemory()
        val wordRepo = WordRepositoryImpl(db)
        val report = WordbookImporter(wordRepo).import(rows, now = Instant.fromEpochSeconds(1_000_000))

        assertEquals(2, report.successCount)
        assertEquals(0, report.duplicatedCount)
        assertEquals(1, report.failureCount)
        assertEquals(5, report.failures[0].lineNo)
        assertTrue(report.failures[0].reason.contains("单词"))

        val saved = wordRepo.getAll()
        assertEquals(listOf("apple", "look up"), saved.map { it.text })
        assertEquals("n.", saved[0].pos)
        assertEquals("ˈæpl", saved[0].phonetic)
        assertEquals(null, saved[1].pos) // 词组 pos 强制 null

        file.delete()
    }
}
