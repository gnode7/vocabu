package cn.vocabu

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cn.vocabu.core.audio.SpeechController
import cn.vocabu.core.io.ExcelReader
import cn.vocabu.core.io.FilePicker
import cn.vocabu.core.logic.SpeechScriptBuilder
import cn.vocabu.core.logic.SpeechSegment
import cn.vocabu.core.logic.WordbookImporter
import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.Word
import cn.vocabu.data.LearningRecordRepositoryImpl
import cn.vocabu.data.SettingsRepositoryImpl
import cn.vocabu.data.StudyLogRepositoryImpl
import cn.vocabu.data.VocabuDatabaseFactory
import cn.vocabu.data.WordRepositoryImpl
import cn.vocabu.platform.AwtFilePicker
import cn.vocabu.platform.DesktopAudioPlayer
import cn.vocabu.platform.ErrorCuePlayer
import cn.vocabu.platform.PoiExcelReader
import cn.vocabu.platform.YoudaoTtsClient
import cn.vocabu.platform.asciiEscape
import cn.vocabu.ui.HomeViewModel
import cn.vocabu.ui.RecallViewModel
import cn.vocabu.ui.TestViewModel
import cn.vocabu.ui.SettingsViewModel
import cn.vocabu.ui.VocabuApp
import cn.vocabu.ui.WordbookViewModel
import javax.swing.SwingUtilities
import kotlin.time.Instant

fun main() = application {
    // 手动构造依赖图（ADR-0002：desktopApp 只做平台装配，不引 DI 框架）
    val db = VocabuDatabaseFactory.userDataDatabase()
    val wordRepository = WordRepositoryImpl(db)
    val learningRecordRepository = LearningRecordRepositoryImpl(db)
    val settingsRepository = SettingsRepositoryImpl(db)
    val studyLogRepository = StudyLogRepositoryImpl(db)

    // TTS/播放器接缝（ISSUE-008 装配替换，焦点⑦）：有道 dictvoice + 两级缓存 + JLayer 播放；
    // core fake 包保留供测试，不算残留
    val ttsClient = YoudaoTtsClient(VocabuDatabaseFactory.userDataDir().resolve("tts-cache"))
    val audioPlayer = DesktopAudioPlayer()

    // 播控装配（ISSUE-005 接缝 + ISSUE-008 异步化）：后台编排 daemon 线程，UI 回调切 EDT；
    // 播报响应（PRD §6.1 <2s）：缓存命中毫秒级，未命中一次 HTTP ~数百 ms
    // logger（0012 B1）：fetch 失败留痕 stderr，`[vocabu]` 前缀与 TTS 客户端 `[vocabu-tts]` 区分层级
    val speechController = SpeechController(
        ttsClient, audioPlayer,
        taskDispatcher = { block -> Thread(block, "vocabu-tts").apply { isDaemon = true }.start() },
        notifyDispatcher = { block -> SwingUtilities.invokeLater(block) },
        sleeper = { Thread.sleep(it) },
        // 0013 方案A：此处统一 asciiEscape 兜底——一处兜住 core 全部 logger 输出（含未来新增），
        // core 留痕原文保持人眼可读，GBK 控制台输出纯 ASCII（码点可反查）
        logger = { System.err.println("[vocabu] ${asciiEscape(it)}") },
    )
    val errorCuePlayer = ErrorCuePlayer()

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

    // 考察会话装配（ISSUE-009 接缝 + ISSUE-008 兑现）：听写脚本（仅读音）与答错回放在脚本构建器组装；
    // 计时联动：hold→播报结束/静默放行（焦点③）；告警音真实音效（焦点⑤）
    val testViewModel = TestViewModel(
        words = wordRepository,
        records = learningRecordRepository,
        settings = settingsRepository,
        studyLog = studyLogRepository,
        speak = { segments: List<SpeechSegment>, startDelayMillis: Long, onFinished: () -> Unit, onSilent: () -> Unit ->
            speechController.speak(segments, startDelayMillis, onFinished, onSilent)
        },
        stopSpeak = { speechController.stop() },
        errorCue = { errorCuePlayer.play() }, // 兑现 ISSUE-005 挂账 TODO
        now = { Instant.fromEpochSeconds(System.currentTimeMillis() / 1000) },
        today = { java.time.LocalDate.now().toString() },
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Vocabu",
    ) {
        VocabuApp(homeViewModel, wordbookViewModel, settingsViewModel, recallViewModel, testViewModel)
    }
}
