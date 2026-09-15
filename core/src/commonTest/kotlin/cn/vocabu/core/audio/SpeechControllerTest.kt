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

    // ---- 选音映射（ISSUE-008 焦点②：EN→AMERICAN 固定美音，ZH→MANDARIN）----

    @Test
    fun `按段选音_EN走美音_ZH走MANDARIN`() {
        val tts = FakeTtsClient()
        val player = FakeAudioPlayer()
        val c = SpeechController(tts, player)

        c.speak(
            script(
                Triple("apple", 500L, SpeechLang.EN),
                Triple("苹果", 0L, SpeechLang.ZH),
            ),
        )

        assertEquals(
            listOf("apple" to TtsVoice.AMERICAN, "苹果" to TtsVoice.MANDARIN),
            tts.requests,
        )
    }

    // ---- 异步编排（焦点④：手动 dispatcher 驱动）----

    /** 可手动驱动的 dispatcher：记录任务，测试显式 pump 执行。 */
    private class ManualDispatcher : SpeechDispatcher {
        val tasks = mutableListOf<() -> Unit>()
        override fun dispatch(block: () -> Unit) {
            tasks += block
        }

        fun pumpAll() {
            while (isNotEmpty()) tasks.removeAt(0).invoke()
        }

        fun isNotEmpty() = tasks.isNotEmpty()
    }

    @Test
    fun `speak经taskDispatcher后台编排_回调经notifyDispatcher`() {
        val tts = FakeTtsClient()
        val player = FakeAudioPlayer()
        val tasks = ManualDispatcher()
        val notifies = ManualDispatcher()
        val c = SpeechController(tts, player, taskDispatcher = tasks, notifyDispatcher = notifies)

        var finished = false
        c.speak(script(Triple("apple", 0L, SpeechLang.EN)), onFinished = { finished = true })
        assertTrue(!finished) // speak 立即返回，编排尚未执行
        tasks.pumpAll()       // 后台编排：clear→fetch→onDrained→enqueue
        assertTrue(player.hasPendingDrain)
        assertTrue(player.simulateDrained()) // 播放器自然播空
        assertTrue(notifies.isNotEmpty())    // 回调经 notifyDispatcher，不直接执行
        assertTrue(!finished)
        notifies.pumpAll()
        assertTrue(finished)
    }

    @Test
    fun `零段入队走onSilent_空脚本与全部失败同构`() {
        val tasks = ManualDispatcher()
        val notifies = ManualDispatcher()
        // 全部 fetch 失败
        val tts1 = FakeTtsClient(failOnText = setOf("apple"))
        val p1 = FakeAudioPlayer()
        var silent1 = false
        SpeechController(tts1, p1, taskDispatcher = tasks, notifyDispatcher = notifies)
            .speak(script(Triple("apple", 500L, SpeechLang.EN)), onSilent = { silent1 = true })
        tasks.pumpAll()
        assertEquals(0, p1.ops.filterIsInstance<FakeAudioPlayer.Op.Enqueue>().size)
        assertTrue(!p1.hasPendingDrain) // 未注册排空通知
        assertTrue(notifies.isNotEmpty())
        notifies.pumpAll()
        assertTrue(silent1)

        // 空脚本同构
        val tts2 = FakeTtsClient()
        val p2 = FakeAudioPlayer()
        var silent2 = false
        SpeechController(tts2, p2, taskDispatcher = tasks, notifyDispatcher = notifies)
            .speak(emptyList(), onSilent = { silent2 = true })
        tasks.pumpAll()
        assertTrue(!p2.hasPendingDrain)
        notifies.pumpAll()
        assertTrue(silent2)
    }

    @Test
    fun `部分段失败仍走onFinished`() {
        val tts = FakeTtsClient(failOnText = setOf("A"))
        val player = FakeAudioPlayer()
        val tasks = ManualDispatcher()
        val notifies = ManualDispatcher()
        val c = SpeechController(tts, player, taskDispatcher = tasks, notifyDispatcher = notifies)

        var finished = false
        var silent = false
        c.speak(
            script(
                Triple("apple", 500L, SpeechLang.EN),
                Triple("A", 300L, SpeechLang.EN),
            ),
            onFinished = { finished = true },
            onSilent = { silent = true },
        )
        tasks.pumpAll()
        assertTrue(player.hasPendingDrain) // ≥1 段入队 → 等排空
        assertTrue(player.simulateDrained())
        notifies.pumpAll()
        assertTrue(finished)
        assertTrue(!silent)
    }

    @Test
    fun `generation过时_旧任务丢弃结果且回调取消`() {
        val tts = FakeTtsClient()
        val player = FakeAudioPlayer()
        val tasks = ManualDispatcher()
        val notifies = ManualDispatcher()
        val c = SpeechController(tts, player, taskDispatcher = tasks, notifyDispatcher = notifies)

        var finishedA = false
        c.speak(script(Triple("apple", 0L, SpeechLang.EN)), onFinished = { finishedA = true })
        // 任务 A 已入队未执行时，B 触发 → A 过时
        c.speak(script(Triple("pear", 0L, SpeechLang.EN)))
        tasks.pumpAll() // A、B 任务顺序执行：A 应在中途/收尾被 generation 拦截

        // A 的结果不落播放器（仅 B 的 pear 一段入队）、A 不发起 fetch、A 回调不派发
        assertEquals(1, player.ops.filterIsInstance<FakeAudioPlayer.Op.Enqueue>().size)
        assertEquals(listOf("pear"), tts.requests.map { it.first })
        notifies.pumpAll()
        assertTrue(!finishedA)
    }

    @Test
    fun `stop使在飞任务过时_回调取消`() {
        val tts = FakeTtsClient()
        val player = FakeAudioPlayer()
        val tasks = ManualDispatcher()
        val notifies = ManualDispatcher()
        val c = SpeechController(tts, player, taskDispatcher = tasks, notifyDispatcher = notifies)

        var finished = false
        c.speak(script(Triple("apple", 0L, SpeechLang.EN)), onFinished = { finished = true })
        tasks.pumpAll() // 编排完成，onDrained pending
        c.stop()        // 离开页面：generation++ → player.clear() 取消 pending 回调
        assertTrue(!player.hasPendingDrain) // 排空通知已被 clear 取消
        assertTrue(!player.simulateDrained())
        notifies.pumpAll()
        assertTrue(!finished)
    }

    // ---- startDelay（焦点⑤：批改回放为告警音让位；默认 0 时零开销）----

    @Test
    fun `startDelay在编排前休眠且可被generation取消`() {
        val tts = FakeTtsClient()
        val player = FakeAudioPlayer()
        val tasks = ManualDispatcher()
        val notifies = ManualDispatcher()
        val slept = mutableListOf<Long>()
        val c = SpeechController(
            tts, player,
            taskDispatcher = tasks,
            notifyDispatcher = notifies,
            sleeper = { slept += it },
        )

        var finished = false
        c.speak(script(Triple("apple", 0L, SpeechLang.EN)), startDelayMillis = 200, onFinished = { finished = true })
        assertTrue(slept.isEmpty()) // 未执行前不睡
        tasks.pumpAll()
        // 200ms 分步休眠（每步 ≤50ms）
        assertTrue(slept.isNotEmpty() && slept.all { it <= 50L } && slept.sum() == 200L)
        assertTrue(player.ops.filterIsInstance<FakeAudioPlayer.Op.Enqueue>().isNotEmpty())

        // 取消：delay 期间触发新 speak → 旧任务在休眠分段中被拦截，不编排不回调
        val tts2 = FakeTtsClient()
        val player2 = FakeAudioPlayer()
        val tasks2 = ManualDispatcher()
        val c3 = SpeechController(
            tts2, player2,
            taskDispatcher = tasks2,
            notifyDispatcher = notifies,
            sleeper = { },
        )
        var silent = false
        c3.speak(script(Triple("apple", 0L, SpeechLang.EN)), startDelayMillis = 200, onSilent = { silent = true })
        c3.speak(script(Triple("pear", 0L, SpeechLang.EN)))
        tasks2.pumpAll()
        notifies.pumpAll()
        assertTrue(!silent)
        assertEquals(listOf("pear"), tts2.requests.map { it.first })
        assertEquals(1, player2.ops.filterIsInstance<FakeAudioPlayer.Op.Enqueue>().size)
    }
}
