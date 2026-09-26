package cn.vocabu.ui

/**
 * vocabu 设计 token 收敛点（设计规范 v0 §3.1，docs/2.ui/design-spec-v0.md）。
 *
 * 硬规则：界面代码禁止内联 alpha/时长魔法数，一律引本文件 token；
 * 规范未覆盖处遵循 Material 3 官方规范（design-spec-v0.md §1）。
 * 色值类 token（评级四色 §3.2 / 计时三色 §3.3）待用户拍板后收口进 v1。
 */
object VocabuTokens {
    /** 悬停态背景：onSurface @ 2.5%（010 走查定值 → 规范 §3.1 收编） */
    val HoverOverlayAlpha = 0.025f

    /** 选中态背景：onSurface @ 4.5%（010 走查定值 → 规范 §3.1 收编） */
    val SelectedOverlayAlpha = 0.045f
}
