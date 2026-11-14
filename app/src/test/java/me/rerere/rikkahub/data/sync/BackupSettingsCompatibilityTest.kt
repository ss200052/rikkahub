package me.rerere.rikkahub.data.sync

import kotlinx.serialization.encodeToString
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.batch.BatchKeywordCategory
import me.rerere.rikkahub.data.batch.BatchKeywordPreset
import me.rerere.rikkahub.data.batch.BatchRegenerationConfig
import me.rerere.rikkahub.data.batch.KeywordScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份里的设置能不能原样带回来。
 *
 * 备份只存一份 settings 的 JSON（见备份归档里的 settings.json），
 * 所以这个往返就是导出导入的实际路径。
 */
class BackupSettingsCompatibilityTest {

    private fun fullConfig() = BatchRegenerationConfig(
        parallelCount = 9,
        commitKeywords = listOf("好的"),
        rerollReasoningKeywords = listOf("让我想想"),
        rerollTextKeywords = listOf("总之"),
        commitScope = KeywordScope.TEXT_AND_REASONING,
        maxRerollsPerLane = 3,
        rerollOnNoHit = true,
        reasoningLevelOverride = ReasoningLevel.HIGH,
    )

    @Test
    fun `batch config survives the backup round trip`() {
        val presets = listOf(
            BatchKeywordPreset(category = BatchKeywordCategory.COMMIT, group = "常用", keyword = "可以"),
        )
        val original = Settings(
            batchRegeneration = fullConfig(),
            batchKeywordPresets = presets,
            promptSchemeId = "zh",
        )

        val json = JsonInstant.encodeToString(original)
        val restored = JsonInstant.decodeFromString<Settings>(json)

        assertEquals(original.batchRegeneration, restored.batchRegeneration)
        assertEquals(presets, restored.batchKeywordPresets)
        assertEquals("zh", restored.promptSchemeId)
    }

    /** 更早的备份里没有批量这几个字段，恢复时应该按默认值补齐，而不是整份失败 */
    @Test
    fun `older backup without batch fields restores defaults`() {
        val oldBackup = """{"dynamicColor":false,"themeId":"preset-a"}"""

        val restored = JsonInstant.decodeFromString<Settings>(oldBackup)

        assertEquals(BatchRegenerationConfig(), restored.batchRegeneration)
        assertTrue(restored.batchKeywordPresets.isEmpty())
        assertEquals(false, restored.dynamicColor)
    }
}
