package cn.vocabu

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cn.vocabu.core.fake.FakeAudioPlayer
import cn.vocabu.core.fake.FakeTtsClient
import cn.vocabu.core.audio.SpeechController
import cn.vocabu.core.io.ExcelReader
import cn.vocabu.core.io.FilePicker
import cn.vocabu.core.logic.SpeechScriptBuilder
import cn.vocabu.core.logic.WordbookImporter
import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.Word
import cn.vocabu.data.LearningRecordRepositoryImpl
import cn.vocabu.data.SettingsRepositoryImpl
import cn.vocabu.data.StudyLogRepositoryImpl
import cn.vocabu.data.VocabuDatabaseFactory
import cn.vocabu.data.WordRepositoryImpl
import cn.vocabu.platform.AwtFilePicker
import cn.vocabu.platform.PoiExcelReader
import cn.vocabu.ui.HomeViewModel
import cn.vocabu.ui.RecallViewModel
import cn.vocabu.ui.SettingsViewModel
import cn.vocabu.ui.VocabuApp
import cn.vocabu.ui.WordbookViewModel
import kotlin.time.Instant

fun main() = application {
    // 手动构造依赖图（ADR-0002：desktopApp 只做平台装配，不引 DI 框架）
    val db = VocabuDatabaseFactory.userDataDatabase()
    val wordRepository = WordRepositoryImpl(db)
    val learningRecordRepository = LearningRecordRepositoryImpl(db)
    val settingsRepository = SettingsRepositoryImpl(db)
    val studyLogRepository = StudyLogRepositoryImpl(db)

    // TTS/播放器接缝：真实实现 ISSUE-008（有道 + 两级缓存 + javax.sound）；当前 Fake 静默
    val ttsClient = FakeTtsClient()
    val audioPlayer = FakeAudioPlayer()

    // 播控装配（ISSUE-005）：脚本组装 + TTS 取段 + 入队停顿；真实 TTS/播放器在 ISSUE-008，当前 Fake 静默
    val speechController = SpeechController(ttsClient, audioPlayer)

    val homeViewModel = HomeViewModel(
        words = wordRepository,
        records = learningRecordRepository,
        settings = settingsRepository,
        speak = { word: Word -> speechController.speak(SpeechScriptBuilder.build(word, settingsRepository.get())) },
        stopSpeak = { speechController.stop() },
        now = { Instant.fromEpochSeconds(System.currentTimeMillis() / 1000) },
    )
    val wordbookViewModel = WordbookViewModel(
        words = wordRepository,
        records = learningRecordRepository,
        settings = settingsRepository,
        importer = WordbookImporter(wordRepository),
        excelReader = PoiExcelReader(),
        filePicker = AwtFilePicker(),
        now = { Instant.fromEpochSeconds(System.currentTimeMillis() / 1000) },
    )
    val settingsViewModel = SettingsViewModel(settingsRepository)

    // 回忆会话装配（ISSUE-006）：方向相关脚本组装在装配层；本地日期供每日统计落账
    val recallViewModel = RecallViewModel(
        words = wordRepository,
        records = learningRecordRepository,
        settings = settingsRepository,
        studyLog = studyLogRepository,
        speak = { word: Word, facet: Facet ->
            speechController.speak(SpeechScriptBuilder.buildRecall(word, facet, settingsRepository.get()))
        },
        stopSpeak = { speechController.stop() },
        now = { Instant.fromEpochSeconds(System.currentTimeMillis() / 1000) },
        today = { java.time.LocalDate.now().toString() },
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Vocabu",
    ) {
        VocabuApp(homeViewModel, wordbookViewModel, settingsViewModel, recallViewModel)
    }
}
