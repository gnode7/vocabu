package cn.vocabu.core.audio

import cn.vocabu.core.logic.SpeechLang
import cn.vocabu.core.logic.SpeechSegment
import kotlin.concurrent.atomics.AtomicInt

/**
 * 任务派发接缝（ISSUE-008 焦点④异步化）：core 零协程依赖（核实 build.gradle.kts），
 * 以轻量 Dispatcher 替代；生产 desktop 传 daemon 线程执行器，UI 回调切线程由 notifyDispatcher 承载。
 */
fun interface SpeechDispatcher {
    fun dispatch(block: () -> Unit)

    companion object {
        /** 同步直派：默认值，Fake 路径与现有测试语义不变。 */
        val Direct: SpeechDispatcher = SpeechDispatcher { block -> block() }
    }
}

/**
 * 播控（ISSUE-005 起；ISSUE-008 异步化 + 完成联动，PRD §4.4、dispatch 焦点③④）：
 * - 触发播报 = 先清空队列再取数入队（重复触发/切换选中 = 取消当前重来）
 * - 离开页面 = [stop] 全取消
 * - TTS 失败段静默跳过（含其停顿），不阻断后续段（PRD §4.4）
 * - 段间停顿由 [AudioPlayer.enqueue] 的 pauseAfterMillis 承载
 *
 * 异步编排（焦点④）：fetch + enqueue 全部经 [taskDispatcher] 后台执行，speak 立即返回；
 * generation 协作取消——新 speak/stop 使在飞任务过时，过时任务丢弃 fetch 结果并取消 pending 回调。
 *
 * 完成联动（焦点③，009 挂账兑现）：
 * - ≥1 段入队且队列自然排空 = 播报结束 → [onFinished]
 * - 0 段入队（空脚本/全部 fetch 失败）= 播报未发生 → [onSilent]
 * - 部分段失败（有段播成）按 PRD 字面播报已发生 → [onFinished]
 *
 * 实现取舍：为保证排空判定无竞态，先完成全部 fetch 再统一注册 onDrained + 入队
 * （逐段 fetch→enqueue 会与极短段播放竞速，第一段播完时下段未入队，drain 误触发提前回调）。
 * 缓存命中时 fetch 为毫秒级，播报响应不受影响。
 *
 * [startDelayMillis]（焦点⑤）：后台任务在 fetch 前延迟，用于告警音先响（批改回放 +200ms）；
 * 由 [sleeper] 分段休眠并在每段后校验 generation，过时即取消——默认空操作（Fake 路径零延迟）。
 */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
class SpeechController(
    private val tts: TtsClient,
    private val player: AudioPlayer,
    private val taskDispatcher: SpeechDispatcher = SpeechDispatcher.Direct,
    private val notifyDispatcher: SpeechDispatcher = SpeechDispatcher.Direct,
    /** 休眠注入（毫秒）：生产传 Thread::sleep，测试注入记录型实现，core 不读时钟。 */
    private val sleeper: (Long) -> Unit = {},
    /** 失败留痕注入（0012 B1）：默认 no-op（Fake/测试零感知），desktop 传 stderr。仅诊断，不改控制流。 */
    private val logger: (String) -> Unit = {},
) {

    private val generation = AtomicInt(0)

    /**
     * 触发播报：立即返回，后台清空→（可选延迟）→取数→入队→分流回调。
     * 段选音：ZH 段 → [TtsVoice.MANDARIN]，EN 段 → [TtsVoice.AMERICAN]
     * （PRD §2.6 无口音设置项，固定 AMERICAN；BRITISH 枚举留 Edge TTS/后续扩展位）。
     */
    fun speak(
        segments: List<SpeechSegment>,
        startDelayMillis: Long = 0,
        onFinished: () -> Unit = {},
        onSilent: () -> Unit = {},
    ) {
        val gen = generation.fetchAndAdd(1) + 1
        taskDispatcher.dispatch { runPlayback(gen, segments, startDelayMillis, onFinished, onSilent) }
    }

    /** 离开页面 / 显式取消：使在飞任务过时 + 停止并清空全部播报（含 pending 排空通知）。 */
    fun stop() {
        generation.fetchAndAdd(1)
        player.clear()
    }

    private fun runPlayback(
        gen: Int,
        segments: List<SpeechSegment>,
        startDelayMillis: Long,
        onFinished: () -> Unit,
        onSilent: () -> Unit,
    ) {
        player.clear()
        holdStartDelay(gen, startDelayMillis)
        if (generation.load() != gen) return // 过时：延迟期间被新 speak/stop 取消

        val fetched = ArrayList<Pair<TtsAudio, SpeechSegment>>(segments.size)
        for (seg in segments) {
            if (generation.load() != gen) return // 过时：丢弃 fetch 结果（正常取消，不留痕）
            val audio = tts.fetch(seg.text, voiceFor(seg.lang))
            if (audio == null) {
                // 0012 B1：失败跳段留痕（PRD §4.4 静默语义不变），desktop 侧 TTS 客户端另有原因级留痕
                // 0013 方案A：留痕原文保持人眼可读，GBK 控制台转义由 desktop 装配端 logger 闭包统一兜底
                logger("段拉取失败跳过 text=${seg.text} voice=${voiceFor(seg.lang)}")
                continue
            }
            fetched += audio to seg
        }
        if (generation.load() != gen) return // 过时：fetch 完成后仍需校验，回调一并取消（不留痕）

        if (fetched.isEmpty()) {
            if (segments.isNotEmpty()) logger("全部段拉取失败，播报未发生 共${segments.size}段")
            notify(gen, onSilent)
            return
        }
        player.onDrained { notify(gen, onFinished) }
        fetched.forEach { (audio, seg) -> player.enqueue(audio, seg.pauseAfterMillis) }
    }

    /** 分段休眠（每 [SLEEP_STEP_MS] 校验 generation），过时提前退出。 */
    private fun holdStartDelay(gen: Int, millis: Long) {
        var remaining = millis
        while (remaining > 0 && generation.load() == gen) {
            val step = minOf(SLEEP_STEP_MS, remaining)
            sleeper(step)
            remaining -= step
        }
    }

    private fun notify(gen: Int, action: () -> Unit) {
        if (generation.load() != gen) return
        notifyDispatcher.dispatch(action)
    }

    private fun voiceFor(lang: SpeechLang): TtsVoice = when (lang) {
        SpeechLang.ZH -> TtsVoice.MANDARIN
        SpeechLang.EN -> TtsVoice.AMERICAN
    }

    private companion object {
        const val SLEEP_STEP_MS = 50L
    }
}
