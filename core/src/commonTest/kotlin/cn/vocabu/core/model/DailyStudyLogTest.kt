package cn.vocabu.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class DailyStudyLogTest {

    @Test
    fun `plus增量累计四组计数`() {
        val base = DailyStudyLog(date = "2026-09-10")
        val updated = base.plus(newWordsLearned = 3, wordsReviewed = 2, correctJudgments = 5, totalJudgments = 6)

        assertEquals(3, updated.newWordsLearned)
        assertEquals(2, updated.wordsReviewed)
        assertEquals(5, updated.totalCorrect)
        assertEquals(6, updated.totalJudgments)
    }

    @Test
    fun `plus可连续累计`() {
        val log = DailyStudyLog(date = "2026-09-10")
            .plus(newWordsLearned = 2, correctJudgments = 1, totalJudgments = 2)
            .plus(wordsReviewed = 4, correctJudgments = 3, totalJudgments = 5)

        assertEquals(2, log.newWordsLearned)
        assertEquals(4, log.wordsReviewed)
        assertEquals(4, log.totalCorrect)
        assertEquals(7, log.totalJudgments)
    }

    @Test
    fun `accuracy为派生值`() {
        assertEquals(0.8f, DailyStudyLog(date = "d", totalJudgments = 10, totalCorrect = 8).accuracy, 1e-6f)
    }

    @Test
    fun `判定分母为0时accuracy为0`() {
        assertEquals(0.0f, DailyStudyLog(date = "d").accuracy)
    }
}
