package cn.vocabu.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class SettingsValidatorTest {

    // ---- 每日新词数量：1~500（PRD §2.6）----

    @Test
    fun `每日新词数量界内不提示`() {
        val r = SettingsValidator.dailyNewWordCount(20)
        assertEquals(20, r.value)
        assertNull(r.warning)
    }

    @Test
    fun `每日新词数量越界钳制并提示`() {
        assertEquals(1, SettingsValidator.dailyNewWordCount(0).value)
        assertEquals(500, SettingsValidator.dailyNewWordCount(501).value)
        assertNotNull(SettingsValidator.dailyNewWordCount(0).warning)
        assertNotNull(SettingsValidator.dailyNewWordCount(999).warning)
    }

    // ---- 每日补查数：0~50，0 = 关闭 ----

    @Test
    fun `补查数零合法 越界钳制`() {
        assertEquals(0, SettingsValidator.facetCatchUpQuota(0).value)
        assertNull(SettingsValidator.facetCatchUpQuota(0).warning)
        assertEquals(0, SettingsValidator.facetCatchUpQuota(-1).value)
        assertEquals(50, SettingsValidator.facetCatchUpQuota(51).value)
    }

    // ---- 考察阈值：Easy 1~120；Good = Easy+1 ~ 300（依赖 Easy，动态下限）----

    @Test
    fun `Easy阈值界内越界`() {
        assertEquals(5, SettingsValidator.dictationEasyThreshold(5).value)
        assertEquals(1, SettingsValidator.dictationEasyThreshold(0).value)
        assertEquals(120, SettingsValidator.dictationEasyThreshold(121).value)
    }

    @Test
    fun `Good阈值下限为Easy加一`() {
        val r = SettingsValidator.dictationGoodThreshold(input = 5, easy = 10)
        assertEquals(11, r.value) // 5 < Easy+1 → 钳到 11
        assertNotNull(r.warning)

        assertEquals(10, SettingsValidator.dictationGoodThreshold(input = 10, easy = 5).value)
        assertNull(SettingsValidator.dictationGoodThreshold(input = 10, easy = 5).warning)

        assertEquals(300, SettingsValidator.dictationGoodThreshold(input = 400, easy = 5).value)
    }

    // ---- 回忆展示条数：1 ~ 今日全部词数（动态上限）----

    @Test
    fun `回忆条数上限为今日词数`() {
        assertEquals(1, SettingsValidator.recallDisplayCount(0, todayTotal = 38).value)
        assertEquals(38, SettingsValidator.recallDisplayCount(99, todayTotal = 38).value)
        assertEquals(10, SettingsValidator.recallDisplayCount(10, todayTotal = 38).value)
        assertNull(SettingsValidator.recallDisplayCount(10, todayTotal = 38).warning)
    }

    @Test
    fun `今日无词时回忆条数下限保护为1`() {
        assertEquals(1, SettingsValidator.recallDisplayCount(5, todayTotal = 0).value)
    }
}
