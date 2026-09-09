package cn.vocabu.data

import cn.vocabu.core.model.AppSettings
import cn.vocabu.core.model.DailyStudyLog
import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Word
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PersistenceTest {

    private val db = VocabuDatabaseFactory.createInMemory()
    private val wordRepo = WordRepositoryImpl(db)
    private val recRepo = LearningRecordRepositoryImpl(db)
    private val settingsRepo = SettingsRepositoryImpl(db)
    private val logRepo = StudyLogRepositoryImpl(db)

    private val ts = Instant.fromEpochSeconds(1_000_000)

    private fun newWord(text: String) = Word.of(text, " phonetic ", " n. ", " 翻译 ", ts, ts)

    private fun record(wordId: Long, facet: Facet, interval: Long = 300) = LearningRecord(
        id = 0,
        wordId = wordId,
        facet = facet,
        easeFactor = 2.6f,
        repetitionCount = 1,
        intervalSeconds = interval,
        nextReviewTime = Instant.fromEpochSeconds(1_000_300),
        lastReviewTime = ts,
        totalReviews = 1,
        totalForgets = 0,
    )

    // ---- 词条 CRUD ----

    @Test
    fun `新增后按id查询且时间字段往返无损`() {
        val saved = wordRepo.add(newWord("apple"))

        assertTrue(saved.id > 0)
        val loaded = assertNotNull(wordRepo.getById(saved.id))
        assertEquals("apple", loaded.text)
        assertEquals(false, loaded.isPhrase)
        assertEquals("phonetic", loaded.phonetic) // Word.of 已 trim
        assertEquals(ts, loaded.createdAt)
        assertEquals(ts, loaded.updatedAt)
    }

    @Test
    fun `更新词条且列表按id升序`() {
        val a = wordRepo.add(newWord("apple"))
        val b = wordRepo.add(newWord("banana"))
        wordRepo.update(a.copy(translation = "苹果", updatedAt = Instant.fromEpochSeconds(2_000_000)))

        val loaded = assertNotNull(wordRepo.getById(a.id))
        assertEquals("苹果", loaded.translation)
        assertEquals(Instant.fromEpochSeconds(2_000_000), loaded.updatedAt)

        assertEquals(listOf(a.id, b.id), wordRepo.getAll().map { it.id })
    }

    @Test
    fun `删除词条`() {
        val a = wordRepo.add(newWord("apple"))
        wordRepo.delete(a.id)
        assertNull(wordRepo.getById(a.id))
    }

    // ---- text 大小写不敏感唯一（ISSUE-002 TDD 用例）----

    @Test
    fun `text大小写不敏感唯一 Apple与apple冲突`() {
        wordRepo.add(newWord("Apple"))
        assertFailsWith<java.sql.SQLException> { wordRepo.add(newWord("apple")) }
    }

    @Test
    fun `findByText大小写不敏感`() {
        wordRepo.add(newWord("Apple"))
        assertEquals("Apple", wordRepo.findByText("apple")?.text)
        assertEquals("Apple", wordRepo.findByText("  APPLE ")?.text)
    }

    // ---- 学习记录：UNIQUE(wordId, facet) 与级联删除 ----

    @Test
    fun `同词同面upsert覆盖 异面共存`() {
        val w = wordRepo.add(newWord("apple"))
        val en2zh = record(w.id, Facet.EN2ZH, interval = 300)
        val zh2en = record(w.id, Facet.ZH2EN, interval = 600)

        recRepo.upsert(en2zh)
        recRepo.upsert(zh2en)
        recRepo.upsert(en2zh.copy(intervalSeconds = 1800)) // 同 (wordId, facet) 再写 → 更新

        assertEquals(1800, recRepo.find(w.id, Facet.EN2ZH)?.intervalSeconds)
        assertEquals(2, recRepo.findAllByWordId(w.id).size)
    }

    @Test
    fun `删除词条级联清除三面记录`() {
        val w = wordRepo.add(newWord("apple"))
        Facet.entries.forEach { recRepo.upsert(record(w.id, it)) }
        assertEquals(3, recRepo.findAllByWordId(w.id).size)

        wordRepo.delete(w.id)

        assertEquals(0, recRepo.findAllByWordId(w.id).size)
    }

    // ---- 设置单行 upsert 语义（ISSUE-002 TDD 用例）----

    @Test
    fun `首次读取返回默认值并落库`() {
        val settings = settingsRepo.get()
        assertEquals(AppSettings(), settings) // PRD §5.5 默认值
    }

    @Test
    fun `保存后读取往返一致`() {
        val modified = AppSettings(dailyNewWordCount = 50, facetCatchUpQuota = 0, ttsService = "test")
        settingsRepo.save(modified)
        settingsRepo.save(modified.copy(dailyNewWordCount = 60)) // 再次 upsert 仍是单行

        val loaded = settingsRepo.get()
        assertEquals(60, loaded.dailyNewWordCount)
        assertEquals(0, loaded.facetCatchUpQuota)
        assertEquals("test", loaded.ttsService)
    }

    // ---- 每日统计 upsert ----

    @Test
    fun `同日统计upsert覆盖`() {
        logRepo.upsert(DailyStudyLog(date = "2026-09-09", newWordsLearned = 10, accuracy = 0.8f))
        logRepo.upsert(DailyStudyLog(date = "2026-09-09", newWordsLearned = 15, wordsReviewed = 5, accuracy = 0.9f))

        val log = assertNotNull(logRepo.findByDate("2026-09-09"))
        assertEquals(15, log.newWordsLearned)
        assertEquals(5, log.wordsReviewed)
        assertEquals(0.9f, log.accuracy)
        assertNull(logRepo.findByDate("2026-09-08"))
    }

    // ---- 文件库（验收：桌面端可创建数据库文件）----

    @Test
    fun `文件库创建并可重新打开`() {
        val dir = Files.createTempDirectory("vocabu-test")
        val dbFile = dir.resolve("vocabu.db")

        val first = VocabuDatabaseFactory.createAt(dbFile)
        WordRepositoryImpl(first).add(newWord("apple"))

        val reopened = VocabuDatabaseFactory.createAt(dbFile) // 已存在的库不再执行建表
        val loaded = WordRepositoryImpl(reopened).findByText("apple")
        assertNotNull(loaded)

        val fileNames = Files.walk(dir).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertTrue(fileNames.contains("vocabu.db"))
    }
}
