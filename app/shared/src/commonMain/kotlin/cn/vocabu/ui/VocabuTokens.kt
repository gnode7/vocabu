package cn.vocabu.ui

import androidx.compose.ui.graphics.Color

/**
 * vocabu 设计 token 收敛点（设计规范 docs/2.ui/design-spec-v0.md，版本 v1）。
 *
 * 硬规则：界面代码禁止内联 alpha/色值/时长魔法数，一律引本文件 token；
 * 规范未覆盖处遵循 Material 3 官方规范（design-spec-v0.md §1）。
 */
object VocabuTokens {
    // ---- §3.1 状态 alpha 家族（v0：010 走查定值收编）----

    /** 悬停态背景：onSurface @ 2.5%（010 走查定值 → 规范 §3.1 收编） */
    val HoverOverlayAlpha = 0.025f

    /** 选中态背景：onSurface @ 4.5%（010 走查定值 → 规范 §3.1 收编） */
    val SelectedOverlayAlpha = 0.045f

    // ---- §3.2 评级语义色（v1 锁定：2026-09-26 用户拍板 M3 基准值）----

    /** 评级 1 Forget：M3 baseline error */
    val RatingForgetColor = Color(0xFFB3261E)

    /** 评级 2 Hard：tertiary 橘系 */
    val RatingHardColor = Color(0xFFE8710A)

    /** 评级 3 Good：amber（黄底上文字用深色 onSurface 保证对比度） */
    val RatingGoodColor = Color(0xFFF9AB00)

    /** 评级 4 Easy：green 系 */
    val RatingEasyColor = Color(0xFF146C2E)

    /** 已评级条目内容降权：onSurface @ 45%（收编 RecallScreen 存量 0.45f；内容降权语义，与 §3.1 overlay 家族分组隔离） */
    val RatedContentAlpha = 0.45f

    // ---- §3.3 计时色（v1 随 §3.2 同族拍板；同族复用评级色族，单一值源）----

    /** 计时充足（绿，同族复用 RatingEasyColor） */
    val TimerSafeColor = RatingEasyColor

    /** 计时过半预警（黄，同族复用 RatingGoodColor） */
    val TimerWarnColor = RatingGoodColor

    /** 走满（橙，同族复用 RatingHardColor） */
    val TimerOverColor = RatingHardColor

    /** 计时圈轨道底色：黑 @ 13%（收编 TestScreen RingBadge 存量 0x22000000；规范 v1.1 增补） */
    val TimerTrackColor = Color(0x22000000)

    // ---- 时长 token（硬规则：禁内联时长魔法数）----

    /** 播报按钮播放中脉动动画时长（HomeScreen SpeakButton，tween 毫秒） */
    const val SpeakPulseDurationMillis = 500
}
