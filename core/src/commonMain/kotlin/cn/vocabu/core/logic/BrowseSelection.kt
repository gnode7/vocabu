package cn.vocabu.core.logic

import cn.vocabu.core.model.Word

/**
 * 通览选中遍历（ISSUE-005；PRD §2.3.2）：
 * 键盘上下键只在**可见条目**间移动——收拢分组内的条目不参与选择。
 */
object BrowseSelection {

    /**
     * 可见条目平铺列表：仅收拢=展开的分组内条目参与选择。
     * [groups] = 各分组条目；[expanded] = 对应分组是否展开（一一对应）。
     */
    fun visibleEntries(groups: List<List<Word>>, expanded: List<Boolean>): List<Word> =
        groups.flatMapIndexed { i, entries -> if (expanded.getOrElse(i) { true }) entries else emptyList() }

    /** 选中下标钳制到 [0, size-1]；空列表恒为 0。 */
    fun clamp(index: Int, size: Int): Int =
        if (size <= 0) 0 else index.coerceIn(0, size - 1)
}
