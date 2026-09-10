package cn.vocabu.core.fake

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Word
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class InMemoryRepositoriesTest {

    private val ts = Instant.fromEpochSeconds(0)

    @Test
    fun `学习记录按wordId和facet唯一upsert`() {
        val repo = InMemoryLearningRecordRepository()
        val r1 = LearningRecord(id = 0, wordId = 1, facet = Facet.EN2ZH, nextReviewTime = ts)
        val r2 = r1.copy(intervalSeconds = 300)

        repo.upsert(r1)
        repo.upsert(r2)

        assertEquals(r2, repo.find(1, Facet.EN2ZH))
        assertEquals(1, repo.findAllByWordId(1).size)
    }

    @Test
    fun `按归一化text和pos联合查找`() {
        val repo = InMemoryWordRepository()
        repo.add(Word.of("Apple", null, "n.", "苹果", ts, ts))

        assertNotNull(repo.findByTextAndPos("apple", "N.")) // 归一化后同键
        assertNull(repo.findByTextAndPos("apple", "v"))     // 异 pos 无
        assertNull(repo.findByTextAndPos("banana", "n"))
    }

    @Test
    fun `同text异pos共存 同键被拒`() {
        val repo = InMemoryWordRepository()
        repo.add(Word.of("record", null, "n.", "记录", ts, ts))
        repo.add(Word.of("RECORD", null, "v.", "录制", ts, ts)) // 归一化后 (record,n) vs (record,v) → 共存

        assertEquals(2, repo.getAll().size)

        val err = assertFailsWith<IllegalArgumentException> {
            repo.add(Word.of("record", null, " N ", "再录一次", ts, ts)) // (record,n) 已存在
        }
        assertTrue(err.message!!.contains("已存在"))
    }

    @Test
    fun `搜索英文大小写不敏感且中文子串命中`() {
        val repo = InMemoryWordRepository()
        repo.add(Word.of("apple", null, "n.", "苹果", ts, ts))
        repo.add(Word.of("ApplePie", null, null, "苹果派", ts, ts))
        repo.add(Word.of("banana", null, "n.", "香蕉", ts, ts))

        // 英文：大小写不敏感包含匹配
        assertEquals(listOf("apple", "ApplePie"), repo.search("APP").map { it.text })
        // 中文：子串匹配
        assertEquals(listOf("apple", "ApplePie"), repo.search("苹果").map { it.text })
        // 无命中
        assertEquals(emptyList(), repo.search("樱桃"))
    }

    @Test
    fun `设置仓库单行语义`() {
        val repo = InMemorySettingsRepository()
        assertEquals(20, repo.get().dailyNewWordCount)

        repo.save(repo.get().copy(dailyNewWordCount = 30))
        assertEquals(30, repo.get().dailyNewWordCount)
    }
}
