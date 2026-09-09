package cn.vocabu.core.fake

import cn.vocabu.core.model.Facet
import cn.vocabu.core.model.LearningRecord
import cn.vocabu.core.model.Word
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class InMemoryRepositoriesTest {

    private val ts = Instant.fromEpochSeconds(0)

    @Test
    fun `词库按大小写不敏感查找文本`() {
        val repo = InMemoryWordRepository()
        repo.add(Word.of("Apple", null, "n.", "苹果", ts, ts))

        assertNotNull(repo.findByText("apple"))
        assertNotNull(repo.findByText("APPLE"))
        assertNull(repo.findByText("banana"))
    }

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
    fun `设置仓库单行语义`() {
        val repo = InMemorySettingsRepository()
        assertEquals(20, repo.get().dailyNewWordCount)

        repo.save(repo.get().copy(dailyNewWordCount = 30))
        assertEquals(30, repo.get().dailyNewWordCount)
    }
}
