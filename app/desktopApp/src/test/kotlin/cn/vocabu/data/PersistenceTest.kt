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
    fun `搜索英文大小写不敏感中文子串且LIKE通配符被转义`() {
        wordRepo.add(Word.of("apple", null, "n.", "苹果", ts, ts))
        wordRepo.add(Word.of("ApplePie", null, null, "苹果派", ts, ts))
        wordRepo.add(Word.of("banana", null, null, "香蕉", ts, ts))
        wordRepo.add(Word.of("a_b", null, null, "下划线词", ts, ts))

        // 英文大小写不敏感
        assertEquals(listOf("apple", "ApplePie"), wordRepo.search("APP").map { it.text })
        // 中文子串
        assertEquals(listOf("apple", "ApplePie"), wordRepo.search("苹果").map { it.text })
        // LIKE 通配符按字面匹配：% 和 _ 不当通配符用
        assertEquals(listOf("a_b"), wordRepo.search("a_b").map { it.text })
        assertEquals(emptyList(), wordRepo.search("axb"))
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
    fun `findByTextAndPos按归一化联合键查找`() {
        wordRepo.add(newWord("Apple")) // pos 归一化为 n

        assertEquals("Apple", wordRepo.findByTextAndPos("apple", "N.")?.text)
        assertNull(wordRepo.findByTextAndPos("apple", "v"))
    }

    @Test
    fun `同text异pos共存 ADR0007`() {
        val noun = wordRepo.add(Word.of("record", null, "n.", "记录", ts, ts))
        val verb = wordRepo.add(Word.of("RECORD", null, "V", "录制", ts, ts))

        assertEquals(2, wordRepo.getAll().size)
        assertEquals("n", noun.pos)  // 归一化存储
        assertEquals("v", verb.pos)
        assertNotNull(wordRepo.findByTextAndPos("record", "n."))
        assertNotNull(wordRepo.findByTextAndPos("record", "v"))
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
        val modified = AppSettings(
            dailyNewWordCount = 50,
            facetCatchUpQuota = 0,
            ttsService = "test",
            recallDirection = "zh2en",
            testMode = "writing",
            correctionReplay = false,
        )
        settingsRepo.save(modified)
        settingsRepo.save(modified.copy(dailyNewWordCount = 60)) // 再次保存仍是单行

        val loaded = settingsRepo.get()
        assertEquals(60, loaded.dailyNewWordCount)
        assertEquals(0, loaded.facetCatchUpQuota)
        assertEquals("test", loaded.ttsService)
        assertEquals("zh2en", loaded.recallDirection)
        assertEquals("writing", loaded.testMode)
        assertEquals(false, loaded.correctionReplay)
    }

    @Test
    fun `wordPlayTranslation默认关 开启后往返无损`() {
        assertEquals(false, settingsRepo.get().wordPlayTranslation) // 0012 C1 默认关
        settingsRepo.save(AppSettings(wordPlayTranslation = true))
        assertEquals(true, settingsRepo.get().wordPlayTranslation)
    }

    // ---- 每日统计增量落账（PRD 5.3 v1.2，修订 #21）----

    @Test
    fun `同日统计增量落账 计数累计且accuracy派生`() {
        // 听写一次提交 = 2 个判定（英文段 + 中文段），全对
        logRepo.increment(date = "2026-09-10", correctJudgments = 2, totalJudgments = 2)
        // 默写一次提交 = 1 个判定，答错
        logRepo.increment(date = "2026-09-10", correctJudgments = 0, totalJudgments = 1)
        // 同日又学习新词与复习
        val log = logRepo.increment(date = "2026-09-10", newWordsLearned = 3, wordsReviewed = 2)

        assertEquals(3, log.newWordsLearned)
        assertEquals(2, log.wordsReviewed)
        assertEquals(2, log.totalCorrect)
        assertEquals(3, log.totalJudgments)
        assertEquals(2.0f / 3.0f, log.accuracy, 1e-6f)

        // 落库读回一致（accuracy 派生值随计数一并写入）
        val reloaded = assertNotNull(logRepo.findByDate("2026-09-10"))
        assertEquals(log, reloaded)
        assertNull(logRepo.findByDate("2026-09-09"))
    }

    @Test
    fun `增量落账首日自动建行`() {
        val log = logRepo.increment(date = "2026-09-11", wordsReviewed = 4)
        assertEquals(4, log.wordsReviewed)
        assertEquals(0, log.totalJudgments)
        assertEquals(0.0f, log.accuracy, 1e-6f)
    }

    // ---- 文件库（验收：桌面端可创建数据库文件）----

    @Test
    fun `文件库创建并可重新打开`() {
        val dir = Files.createTempDirectory("vocabu-test")
        val dbFile = dir.resolve("vocabu.db")

        val first = VocabuDatabaseFactory.createAt(dbFile)
        WordRepositoryImpl(first).add(newWord("apple"))

        val reopened = VocabuDatabaseFactory.createAt(dbFile) // 已存在的库不再执行建表
        val loaded = WordRepositoryImpl(reopened).findByTextAndPos("apple", "n")
        assertNotNull(loaded)

        val fileNames = Files.walk(dir).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertTrue(fileNames.contains("vocabu.db"))
    }

    // ---- 旧库迁移（0012 C1：已存在库跳过建表，加列走幂等 ALTER，旧行按 DEFAULT 读）----

    @Test
    fun `旧库迁移补wordPlayTranslation列 默认false且重开往返`() {
        val dir = Files.createTempDirectory("vocabu-mig")
        val dbFile = dir.resolve("vocabu.db")
        // 手工建 0012 之前的旧版 appSettings（无 wordPlayTranslation 列）并落一行默认设置
        java.sql.DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE appSettings (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        dailyNewWordCount INTEGER NOT NULL DEFAULT 20,
                        facetCatchUpQuota INTEGER NOT NULL DEFAULT 5,
                        autoPlayOnSelect INTEGER NOT NULL DEFAULT 0,
                        wordPlayPronunciation INTEGER NOT NULL DEFAULT 1,
                        wordPlaySpelling INTEGER NOT NULL DEFAULT 1,
                        phrasePlayPronunciation INTEGER NOT NULL DEFAULT 1,
                        phrasePlayTranslation INTEGER NOT NULL DEFAULT 1,
                        recallDirection TEXT NOT NULL DEFAULT 'mixed',
                        recallDisplayCount INTEGER NOT NULL DEFAULT 10,
                        recallEn2ZhWordPlaySpelling INTEGER NOT NULL DEFAULT 1,
                        recallEn2ZhPhrasePlayTranslation INTEGER NOT NULL DEFAULT 0,
                        recallZh2EnAutoPlay INTEGER NOT NULL DEFAULT 0,
                        testMode TEXT NOT NULL DEFAULT 'mixed',
                        correctionReplay INTEGER NOT NULL DEFAULT 1,
                        dictationEasyThreshold INTEGER NOT NULL DEFAULT 5,
                        dictationGoodThreshold INTEGER NOT NULL DEFAULT 10,
                        ttsService TEXT NOT NULL DEFAULT 'youdao'
                    );
                    INSERT INTO appSettings(id) VALUES (1);
                    """.trimIndent(),
                )
            }
        }

        // 打开旧库：迁移补列，读设置不崩且按 DEFAULT 0 = false 读（派单口径）
        val db = VocabuDatabaseFactory.createAt(dbFile)
        val loaded = SettingsRepositoryImpl(db).get()
        assertEquals(false, loaded.wordPlayTranslation)

        // 开启 → 保存 → 重开再读：迁移幂等（第二次打开已含列则跳过 ALTER）且往返无损
        SettingsRepositoryImpl(db).save(loaded.copy(wordPlayTranslation = true))
        val reopened = VocabuDatabaseFactory.createAt(dbFile)
        assertEquals(true, SettingsRepositoryImpl(reopened).get().wordPlayTranslation)
    }
}
