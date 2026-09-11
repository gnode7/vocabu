package cn.vocabu.data

import cn.vocabu.core.io.ExcelReader
import cn.vocabu.core.io.FilePicker
import cn.vocabu.core.audio.SpeechController
import cn.vocabu.core.logic.WordbookImporter
import cn.vocabu.core.fake.FakeAudioPlayer
import cn.vocabu.core.fake.FakeTtsClient
import cn.vocabu.platform.PoiExcelReader
import java.io.File
import java.io.FileOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/**
 * 词库页组合面回归（用户报告「点词库管理抛异常」的复现排查）：
 * 用与 main.kt 相同的真实依赖图（内存 SQLite + 真仓库 + 真导入器 + 真 POI 读取），
 * 把 WordbookScreen 组合期会调用的全部 API 在空库与有库两种状态下各打一遍。
 */
class WordbookScreenSurfaceTest {

    private fun graph(): WordbookGraph {
        val db = VocabuDatabaseFactory.createInMemory()
        return WordbookGraph(
            words = WordRepositoryImpl(db),
            records = LearningRecordRepositoryImpl(db),
            settings = SettingsRepositoryImpl(db),
        )
    }

    private class WordbookGraph(
        val words: WordRepositoryImpl,
        val records: LearningRecordRepositoryImpl,
        val settings: SettingsRepositoryImpl,
    )

    /** main.kt 同款装配（FilePicker 恒 null = 用户取消选择）。 */
    private fun viewModel(g: WordbookGraph, reader: ExcelReader = PoiExcelReader()): cn.vocabu.ui.WordbookViewModel =
        cn.vocabu.ui.WordbookViewModel(
            words = g.words,
            records = g.records,
            settings = g.settings,
            importer = WordbookImporter(g.words),
            excelReader = reader,
            filePicker = object : FilePicker {
                override fun pickExcelFile(): String? = null
            },
            now = { Instant.fromEpochSeconds(1_700_000_000) },
        )

    @Test
    fun `组合面API_空库全调用不抛`() {
        val vm = viewModel(graph())
        // WordbookScreen 组合期触点：remember(vm.version){vm.currentList()}、计数行、空态、筛选候选项
        assertEquals(0, vm.totalCount)
        assertEquals(0, vm.todayCount)
        assertEquals(emptyList(), vm.currentList())
        assertEquals(emptyList(), vm.availablePos())
        // 空态按钮路径
        vm.newDraft("apple")
        // 离开页面无副作用：无任何异常即通过
    }

    @Test
    fun `真实POI链路导入26词样本后组合面正常`() {
        val g = graph()
        val rows = PoiExcelReader().read(sampleFile().absolutePath)
        checkNotNull(rows)
        val report = WordbookImporter(g.words).import(rows, Instant.fromEpochSeconds(1_700_000_000))
        assertEquals(26, report.successCount, "成功导入应为 26：${report.failures}")
        assertEquals(0, report.duplicatedCount)
        assertEquals(0, report.failureCount)

        // 导入后再进词库页（等价用户操作顺序）
        val vm = viewModel(g)
        assertEquals(26, vm.totalCount)
        assertEquals(20, vm.todayCount, "默认每日新词数=20，今日词表应恰为 20")
        assertEquals(26, vm.currentList().size)
        assertTrue(vm.currentList().any { it.text == "record" && it.pos == "n" })
        assertTrue(vm.currentList().any { it.text == "record" && it.pos == "v" })
        assertTrue(vm.availablePos().isNotEmpty())

        // 通览播报链路同场验证（SpeechController + Fake 静默，不抛即通过）
        val speech = SpeechController(FakeTtsClient(), FakeAudioPlayer())
        val word = vm.currentList().first { it.text == "look forward to" }
        speech.speak(cn.vocabu.core.logic.SpeechScriptBuilder.build(word, g.settings.get()))
        speech.stop()
    }

    /** 与 samples/Vocabu冒烟词库样本.xlsx 同内容的 26 行样本（内存生成）。 */
    private fun sampleFile(): File {
        val data = listOf(
            listOf("单词/词组", "词性", "中文翻译", "音标"),
            listOf("apple", "n.", "苹果", "/ˈæpl/"),
            listOf("look forward to", "", "期待；盼望", ""),
            listOf("abandon", "v.", "放弃；抛弃", "/əˈbændən/"),
            listOf("ability", "n.", "能力；才能", "/əˈbɪləti/"),
            listOf("believe", "v.", "相信；认为", "/bɪˈliːv/"),
            listOf("record", "n.", "记录；唱片", "/ˈrekɔːd/"),
            listOf("record", "v.", "录制；记录", "/rɪˈkɔːd/"),
            listOf("give up", "", "放弃；戒除", ""),
            listOf("hardware", "n.", "硬件；五金制品", ""),
            listOf("knowledge", "n.", "知识；学问", ""),
            listOf("yet", "", "然而；还", "/jet/"),
            listOf("atmosphere", "n.", "大气；气氛", "/ˈætməsfɪə(r)/"),
            listOf("budget", "n.", "预算", "/ˈbʌdʒɪt/"),
            listOf("take care of", "", "照顾；处理", ""),
            listOf("challenge", "n.", "挑战；难题", "/ˈtʃælɪndʒ/"),
            listOf("decision", "n.", "决定；决心", "/dɪˈsɪʒn/"),
            listOf("environment", "n.", "环境", "/ɪnˈvaɪrənmənt/"),
            listOf("gradually", "adv.", "逐渐地", "/ˈɡrædʒuəli/"),
            listOf("influence", "n.", "影响", "/ˈɪnfluəns/"),
            listOf("justify", "v.", "证明…有理", "/ˈdʒʌstɪfaɪ/"),
            listOf("meanwhile", "adv.", "同时；其间", ""),
            listOf("opportunity", "n.", "机会；时机", "/ˌɒpəˈtjuːnəti/"),
            listOf("particular", "adj.", "特别的；挑剔的", "/pəˈtɪkjələ(r)/"),
            listOf("require", "v.", "需要；要求", ""),
            listOf("technology", "n.", "技术；工艺", "/tekˈnɒlədʒi/"),
            listOf("workplace", "n.", "职场；工作场所", ""),
        )
        val file = File.createTempFile("vocabu-smoke", ".xlsx")
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("词库")
            data.forEachIndexed { i, row ->
                val r = sheet.createRow(i)
                row.forEachIndexed { j, cell -> r.createCell(j).setCellValue(cell) }
            }
            FileOutputStream(file).use { wb.write(it) }
        }
        return file
    }
}
