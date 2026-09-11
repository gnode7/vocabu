package cn.vocabu.platform

import cn.vocabu.core.io.ExcelReader
import cn.vocabu.core.io.FilePicker
import cn.vocabu.core.logic.ImportTemplate
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** POI 实现：读 .xlsx 第一个 sheet，全部单元格按显示文本取值（DataFormatter 避免 1.0 变形）。 */
class PoiExcelReader : ExcelReader {

    override fun read(path: String): List<List<String?>>? = runCatching {
        File(path).inputStream().use { input ->
            XSSFWorkbook(input).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                val formatter = DataFormatter()
                sheet.map { row ->
                    (0 until row.lastCellNum.coerceAtLeast(0))
                        .map { cellIndex -> row.getCell(cellIndex)?.let(formatter::formatCellValue) }
                }
            }
        }
    }.getOrNull()
}

/** POI 实现：运行时生成导入模板（表头 + 示例行，内容口径 = ImportTemplate，v1.2 #25）。 */
class PoiTemplateGenerator {

    /** 生成 .xlsx 模板写入 path；失败返回 false（不抛异常到 UI 层）。 */
    fun write(path: String): Boolean = runCatching {
        File(path).outputStream().use { output ->
            XSSFWorkbook().use { workbook ->
                val sheet = workbook.createSheet("Sheet1")
                sheet.createRow(0).apply {
                    ImportTemplate.HEADER.forEachIndexed { i, title -> createCell(i).setCellValue(title) }
                }
                ImportTemplate.SAMPLE_ROWS.forEachIndexed { rowIndex, values ->
                    sheet.createRow(rowIndex + 1).apply {
                        values.forEachIndexed { i, value -> createCell(i).setCellValue(value) }
                    }
                }
                (0 until ImportTemplate.HEADER.size).forEach { i -> sheet.setColumnWidth(i, 16 * 256) }
                workbook.write(output)
            }
        }
    }.isSuccess
}

/** AWT FileDialog 实现：选择 .xlsx 文件，取消返回 null。 */
class AwtFilePicker : FilePicker {

    override fun pickExcelFile(): String? {
        val dialog = FileDialog(null as Frame?, "选择词汇表 (.xlsx)", FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> name.lowercase().endsWith(".xlsx") }
            isMultipleMode = false
            modalityType = Dialog.ModalityType.APPLICATION_MODAL
        }
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return File(directory, file).absolutePath
    }

    override fun saveImportTemplate(defaultName: String): String? {
        val dialog = FileDialog(null as Frame?, "保存导入模板", FileDialog.SAVE).apply {
            file = defaultName
            modalityType = Dialog.ModalityType.APPLICATION_MODAL
        }
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val chosen = dialog.file ?: return null
        val target = File(directory, chosen)
        val xlsx = if (target.name.lowercase().endsWith(".xlsx")) target else File(target.parentFile, "${target.name}.xlsx")
        return if (PoiTemplateGenerator().write(xlsx.absolutePath)) xlsx.absolutePath else null
    }
}
