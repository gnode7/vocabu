package cn.vocabu.core.io

/**
 * 桌面平台接缝（ADR-0002）：:core 定义接口，:app:desktopApp 提供实现。
 */
/** Excel 读取接缝：.xlsx → 行列文本。 */
interface ExcelReader {
    /**
     * 读取工作簿第一个 sheet 的全部行（含表头行）。
     * 空单元格为 null；读取失败返回 null（不抛异常到 UI 层）。
     */
    fun read(path: String): List<List<String?>>?
}

/** 文件选择接缝。 */
interface FilePicker {
    /** 弹出 .xlsx 选择对话框；用户取消返回 null。 */
    fun pickExcelFile(): String?

    /**
     * 弹出另存为对话框（默认文件名 defaultName），运行时生成导入模板并写入所选路径（v1.2 #25）。
     * 用户取消或写入失败返回 null（不抛异常到 UI 层）。
     */
    fun saveImportTemplate(defaultName: String): String?
}
