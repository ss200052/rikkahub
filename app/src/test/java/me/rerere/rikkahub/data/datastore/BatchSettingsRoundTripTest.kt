package me.rerere.rikkahub.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.batch.BatchRegenerationConfig
import me.rerere.rikkahub.data.batch.KeywordScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** 内存版 DataStore：绕开文件系统，专门验证 SettingsStore 的存取逻辑本身 */
private class InMemoryDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }
}

class BatchSettingsRoundTripTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun fullConfig() = BatchRegenerationConfig(
        parallelCount = 9,
        commitKeywords = listOf("好的", "可以"),
        rerollReasoningKeywords = listOf("让我想想"),
        rerollTextKeywords = listOf("总之"),
        commitScope = KeywordScope.TEXT_AND_REASONING,
        ignoreCase = false,
        maxRerollsPerLane = 3,
        rerollOnNoHit = true,
        reasoningLevelOverride = ReasoningLevel.HIGH,
    )

    @Test
    fun `batch config survives update and reload`() = runBlocking {
        val disk = InMemoryDataStore()
        val store = SettingsStore(disk, scope)
        store.awaitLoaded()

        val config = fullConfig()
        store.update { it.copy(batchRegeneration = config) }

        assertEquals(config, store.settingsFlow.value.batchRegeneration)

        // 模拟重启：用同一个磁盘内容重新构造一个 Store
        val reopened = SettingsStore(disk, scope)
        val loaded = reopened.awaitLoaded()

        assertEquals(config, loaded.batchRegeneration)
    }

    @Test
    fun `batch config survives a second update touching another field`() = runBlocking {
        val disk = InMemoryDataStore()
        val store = SettingsStore(disk, scope)
        store.awaitLoaded()

        val config = fullConfig()
        store.update { it.copy(batchRegeneration = config) }
        // 之后又改了别的东西，批量配置不能被这次的差分写入漏掉
        store.update { it.copy(webServerPort = 9999) }

        val loaded = SettingsStore(disk, scope).awaitLoaded()
        assertEquals(config, loaded.batchRegeneration)
        assertEquals(9999, loaded.webServerPort)
    }
}
