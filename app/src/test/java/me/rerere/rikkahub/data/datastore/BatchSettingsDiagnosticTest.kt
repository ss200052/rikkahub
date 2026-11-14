package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import me.rerere.rikkahub.data.batch.BatchRegenerationConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 诊断：确认「并发重 roll 的设置被默认值打底覆盖」这条链成不成立。
 *
 * 关注两件事：
 * 1. 占位对象 Settings.dummy() 里的 batchRegeneration 是不是全默认（4 路、关键词全空）
 * 2. 冷启动解码整份设置要多久 —— 这段时间里弹窗只能拿到占位值
 */
class BatchSettingsDiagnosticTest {

    @Test
    fun `dummy settings carry a fully default batch config`() {
        val dummy = Settings.dummy()
        val config = dummy.batchRegeneration

        assertEquals(true, dummy.init)
        assertEquals(4, config.parallelCount)
        assertEquals(emptyList<String>(), config.commitKeywords)
        assertEquals(emptyList<String>(), config.rerollReasoningKeywords)
        assertEquals(emptyList<String>(), config.rerollTextKeywords)
        assertEquals(emptyList<Any>(), dummy.batchKeywordPresets)
    }

    @Test
    fun `decoding a realistic settings payload takes how long`() {
        // 模拟真实用户：几十个助手（每个带完整提示词与模式）+ 批量配置
        val big = Settings(
            assistants = List(60) { index ->
                DEFAULT_ASSISTANTS[0].copy(id = Uuid.random(), name = "assistant-$index")
            },
            batchRegeneration = BatchRegenerationConfig(
                parallelCount = 9,
                commitKeywords = listOf("好的", "可以"),
            ),
        )
        val preferences = mutablePreferencesOf().apply { putSettings(big) }

        // 预热，避免把 JIT 成本算进去
        repeat(5) { preferences.toSettings().normalized() }

        val rounds = 20
        val start = System.nanoTime()
        repeat(rounds) { preferences.toSettings().normalized() }
        val averageMillis = (System.nanoTime() - start) / 1_000_000.0 / rounds

        println(">>> toSettings().normalized() 平均耗时: %.2f ms".format(averageMillis))
        println(">>> 序列化体积: ${preferences.asMap().values.sumOf { it.toString().length }} 字符")
    }

    /**
     * 直接演示覆盖效果：以占位默认值打底拼出的 config，保存后会把真实配置整份顶掉。
     */
    @Test
    fun `default based config overwrites real settings`() {
        val real = BatchRegenerationConfig(
            parallelCount = 9,
            commitKeywords = listOf("好的"),
            rerollTextKeywords = listOf("总之"),
        )
        val stored = Settings(batchRegeneration = real)
        val preferences = mutablePreferencesOf().apply { putSettings(stored) }

        // 弹窗在占位值上打开：initial 来自 Settings.dummy()
        val initial = Settings.dummy().batchRegeneration
        // 用户只把并发路数改成 6，其它字段保持「看到的默认」
        val fromDialog = initial.copy(parallelCount = 6)

        // 保存走的是 update { it.copy(batchRegeneration = fromDialog) }
        val saved = preferences.toSettings().copy(batchRegeneration = fromDialog)
        val roundTripped = mutablePreferencesOf()
            .apply { putSettings(saved, preferences.toSettings()) }
            .toSettings()

        assertEquals(6, roundTripped.batchRegeneration.parallelCount)
        assertEquals(emptyList<String>(), roundTripped.batchRegeneration.commitKeywords)
        assertEquals(emptyList<String>(), roundTripped.batchRegeneration.rerollTextKeywords)
        println(">>> 落盘后的批量配置: ${roundTripped.batchRegeneration}")
    }
}
