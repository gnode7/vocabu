package cn.vocabu.core.logic

import cn.vocabu.core.logic.BrowseSelection.clamp
import cn.vocabu.core.logic.BrowseSelection.visibleEntries
import cn.vocabu.core.model.Word
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * 通览选中遍历（ISSUE-005，PRD §2.3.2）：
 * 键盘上下键只在**可见条目**间移动——收拢分组内的条目不参与选择；越界钳制。
 */
class BrowseSelectionTest {

    private val t0: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun w(id: Long, text: String) = Word.of(
        text = text, phonetic = null, pos = null, translation = "t$text",
        createdAt = t0, updatedAt = t0, id = id,
    )

    private val groups = listOf(
        listOf(w(1, "apple"), w(2, "pear")),   // 新词
        listOf(w(3, "due-a"), w(4, "due-b")),  // 复习
        listOf(w(5, "catch")),                  // 补查
    )

    @Test
    fun `全展开时可见条目为全量按组序`() {
        val visible = visibleEntries(groups, expanded = listOf(true, true, true))
        assertEquals(listOf("apple", "pear", "due-a", "due-b", "catch"), visible.map { it.text })
    }

    @Test
    fun `收拢组的条目不参与选择`() {
        val visible = visibleEntries(groups, expanded = listOf(true, false, false))
        assertEquals(listOf("apple", "pear"), visible.map { it.text })

        val visible2 = visibleEntries(groups, expanded = listOf(false, true, false))
        assertEquals(listOf("due-a", "due-b"), visible2.map { it.text })
    }

    @Test
    fun `上下移动越界钳制到首尾`() {
        assertEquals(0, clamp(-3, size = 5))
        assertEquals(4, clamp(9, size = 5))
        assertEquals(2, clamp(2, size = 5))
    }

    @Test
    fun `空列表时钳制为零不崩溃`() {
        assertEquals(0, clamp(4, size = 0))
    }
}
