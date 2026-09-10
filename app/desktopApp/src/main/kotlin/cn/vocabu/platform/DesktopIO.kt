package cn.vocabu.platform

import cn.vocabu.core.io.ExcelReader
import cn.vocabu.core.io.FilePicker
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
}
