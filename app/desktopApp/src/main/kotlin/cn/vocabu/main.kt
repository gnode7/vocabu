package cn.vocabu

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cn.vocabu.data.LearningRecordRepositoryImpl
import cn.vocabu.data.SettingsRepositoryImpl
import cn.vocabu.data.StudyLogRepositoryImpl
import cn.vocabu.data.VocabuDatabaseFactory
import cn.vocabu.data.WordRepositoryImpl
import cn.vocabu.core.logic.WordbookImporter
import cn.vocabu.platform.AwtFilePicker
import cn.vocabu.platform.PoiExcelReader
import cn.vocabu.ui.WordbookScreen
import cn.vocabu.ui.WordbookViewModel
import kotlin.time.Instant

fun main() = application {
    // 手动构造依赖图（ADR-0002：desktopApp 只做平台装配，不引 DI 框架）
    val db = VocabuDatabaseFactory.userDataDatabase()
    val wordRepository = WordRepositoryImpl(db)
    val learningRecordRepository = LearningRecordRepositoryImpl(db)
    val settingsRepository = SettingsRepositoryImpl(db)
    val studyLogRepository = StudyLogRepositoryImpl(db)

    val viewModel = WordbookViewModel(
        words = wordRepository,
        records = learningRecordRepository,
        settings = settingsRepository,
        importer = WordbookImporter(wordRepository),
        excelReader = PoiExcelReader(),
        filePicker = AwtFilePicker(),
        now = { Instant.fromEpochSeconds(System.currentTimeMillis() / 1000) },
    )
    @Suppress("UNUSED_EXPRESSION")
    studyLogRepository

    Window(
        onCloseRequest = ::exitApplication,
        title = "Vocabu",
    ) {
        WordbookScreen(viewModel)
    }
}
