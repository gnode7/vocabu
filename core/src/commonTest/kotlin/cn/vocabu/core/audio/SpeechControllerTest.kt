package cn.vocabu.core.audio

import cn.vocabu.core.fake.FakeAudioPlayer
import cn.vocabu.core.fake.FakeTtsClient
import cn.vocabu.core.logic.SpeechLang
import cn.vocabu.core.logic.SpeechSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 播控（ISSUE-005，PRD §4.4）：
 * 触发即清空重来；切换选中=再次触发（清空）；离开页面 stop 清空；
 * TTS 失败段静默跳过不阻断；队列顺序与段间停顿忠实于脚本。
 */
class SpeechControllerTest {

    private fun script(vararg segs: Triple<String, Long, SpeechLang>) =
        segs.map { SpeechSegment(it.first, it.third, it.second) }

    @Test
    fun `按脚本顺序入队并携带停顿`() {
        val tts = FakeTtsClient()
        val player = FakeAudioPlayer()
        val c = SpeechController(tts, player)

        c.speak(
            script(
                Triple("apple", 500L, SpeechLang.EN),
                Triple("A", 300L, SpeechLang.EN),
                Triple("P", 0L, SpeechLang.EN),
            ),
        )

        assertEquals(listOf("apple", "A", "P"), tts.requests.map { it.first })
        val enqueues = player.ops.filterIsInstance<FakeAudioPlayer.Op.Enqueue>()
        assertEquals(listOf(500L, 300L, 0L), enqueues.map { it.pauseAfterMillis })
        // 先清空再入队
        assertEquals(FakeAudioPlayer.Op.Clear, player.ops.first())
    }

    @Test
    fun `再次触发取消重来`() {
        val tts = FakeTtsClient()
        val player = FakeAudioPlayer()
        val c = SpeechController(tts, player)

        c.speak(script(Triple("apple", 500L, SpeechLang.EN)))
        c.speak(script(Triple("pear", 0L, SpeechLang.EN)))

        // 两次触发各清空一次，清空先于该次入队；入队共两段（旧词、新词）
        val clears = player.ops.withIndex().filter { it.value == FakeAudioPlayer.Op.Clear }.map { it.index }
        val enqueues = player.ops.withIndex().filter { it.value is FakeAudioPlayer.Op.Enqueue }.map { it.index }
        assertEquals(2, clears.size)
        assertEquals(2, enqueues.size)
        assertTrue(clears[0] < enqueues[0])
        assertTrue(clears[1] < enqueues[1])
        assertEquals(listOf(500L, 0L), player.ops.filterIsInstance<FakeAudioPlayer.Op.Enqueue>().map { it.pauseAfterMillis })
        assertEquals(listOf("apple", "pear"), tts.requests.map { it.first })
    }

    @Test
    fun `TTS失败段静默跳过不阻断后续`() {
        val tts = FakeTtsClient(failOnText = setOf("A"))
        val player = FakeAudioPlayer()
        val c = SpeechController(tts, player)

        c.speak(
            script(
                Triple("apple", 500L, SpeechLang.EN),
                Triple("A", 300L, SpeechLang.EN),
                Triple("P", 0L, SpeechLang.EN),
            ),
        )

        // 失败段无入队（仅 apple 与 P 两段成功），后续 P 仍在
        assertEquals(2, player.ops.filterIsInstance<FakeAudioPlayer.Op.Enqueue>().size)
        assertEquals(listOf("apple", "A", "P"), tts.requests.map { it.first })
    }

    @Test
    fun `离开页面stop清空`() {
        val tts = FakeTtsClient()
        val player = FakeAudioPlayer()
        val c = SpeechController(tts, player)

        c.speak(script(Triple("apple", 500L, SpeechLang.EN)))
        c.stop()

        assertEquals(FakeAudioPlayer.Op.Clear, player.ops.last())
    }
}
