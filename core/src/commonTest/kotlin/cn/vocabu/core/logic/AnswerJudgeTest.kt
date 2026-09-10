package cn.vocabu.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnswerJudgeTest {

    // ---- 时间→评级（PRD §2.5.2 考察自动判定）----

    @Test
    fun `正确且不超过Easy阈值评级5`() {
        assertEquals(5, AnswerJudge.qualityFromTiming(correct = true, elapsedSeconds = 10, easyThresholdSeconds = 15, goodThresholdSeconds = 60))
        assertEquals(5, AnswerJudge.qualityFromTiming(true, 15, 15, 60))
    }

    @Test
    fun `正确且超过Easy但不超过Good阈值评级4`() {
        assertEquals(4, AnswerJudge.qualityFromTiming(true, 16, 15, 60))
        assertEquals(4, AnswerJudge.qualityFromTiming(true, 60, 15, 60))
    }

    @Test
    fun `正确但超过Good阈值评级3`() {
        assertEquals(3, AnswerJudge.qualityFromTiming(true, 61, 15, 60))
    }

    @Test
    fun `答错评级0`() {
        assertEquals(0, AnswerJudge.qualityFromTiming(false, 5, 15, 60))
    }

    @Test
    fun `留空视为错误评级0`() {
        assertEquals(0, AnswerJudge.qualityFromTiming(correct = false, elapsedSeconds = 0, easyThresholdSeconds = 15, goodThresholdSeconds = 60))
    }

    // ---- 英文答案判定：忽略大小写、trim、连续空格折叠 ----

    @Test
    fun `英文判定忽略大小写与首尾空格`() {
        assertTrue(AnswerJudge.english("apple", "  Apple "))
        assertTrue(AnswerJudge.english("MOTHER-IN-LAW", "mother-in-law"))
    }

    @Test
    fun `英文判定折叠词组内连续空格`() {
        assertTrue(AnswerJudge.english("look forward to", "look  forward\tto"))
        assertFalse(AnswerJudge.english("look forward to", "lookforword to"))
    }

    @Test
    fun `英文判定内容不同为错`() {
        assertFalse(AnswerJudge.english("apple", "apples"))
        assertFalse(AnswerJudge.english("apple", "banana"))
        assertFalse(AnswerJudge.english("apple", ""))
    }

    // ---- 中文答案判定：对称拆分（PRD §2.5.2，修订 #20）----

    @Test
    fun `中文判定对称拆分 拼接输入每段命中即可`() {
        // 答案拆段 [详尽, 精心]；输入拆段 [详尽, 精心]，全部命中
        assertTrue(AnswerJudge.chinese("详尽，精心", "详尽；精心"))
        assertTrue(AnswerJudge.chinese("详尽;精心", "精心，详尽"))
    }

    @Test
    fun `中文判定单段退化为普通子串匹配`() {
        assertTrue(AnswerJudge.chinese("详尽，精心", "详尽"))
        assertTrue(AnswerJudge.chinese("苹果;水果，fruit", "苹果"))
        // 输入段是释义的子串即命中
        assertTrue(AnswerJudge.chinese("苹果;水果，fruit", "果"))
    }

    @Test
    fun `中文判定任一段未命中即错`() {
        assertFalse(AnswerJudge.chinese("详尽，精心", "详尽；敷衍"))
        assertFalse(AnswerJudge.chinese("苹果;水果", "梨"))
        // 输入超出释义内容的部分不再被判对（旧规则下 "fruit juice 之类" 会误判对）
        assertFalse(AnswerJudge.chinese("苹果;水果，fruit", "fruit juice 之类"))
    }

    @Test
    fun `中文判定空输入与空段`() {
        assertFalse(AnswerJudge.chinese("苹果;水果", ""))
        // 分隔符切出的空段被忽略，不参与判定
        assertTrue(AnswerJudge.chinese("苹果;水果", "；苹果；；"))
    }

    @Test
    fun `中文判定释义trim后匹配`() {
        assertTrue(AnswerJudge.chinese(" 苹果 ; 水果 ", "苹果"))
    }
}
