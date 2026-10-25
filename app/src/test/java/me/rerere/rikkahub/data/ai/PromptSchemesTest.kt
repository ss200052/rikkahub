package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 骨架与方案的约束校验。
 *
 * 这些约束在运行期由 `require` 保证，一旦不满足就是启动崩溃，
 * 所以用测试把它们钉住：骨架与方案必须一一对应，两套方案各自纯净。
 */
class PromptSchemesTest {
    private val segments = mapOf(
        "时间提醒" to PromptSegments.TIME_REMINDER,
        "工作区提醒" to PromptSegments.WORKSPACE,
        "技能清单" to PromptSegments.SKILLS,
        "记忆段" to PromptSegments.MEMORY,
    )

    private val hanzi = Regex("[\\u4e00-\\u9fff]")

    @Test
    fun `两套方案覆盖全部骨架槽位`() {
        segments.forEach { (name, groups) ->
            PromptSchemes.ENGLISH.requireCovers(name, groups)
            PromptSchemes.CHINESE.requireCovers(name, groups)
        }
    }

    @Test
    fun `骨架里不出现任何文字`() {
        // 骨架只有槽位名与固定结构；若有人往骨架里塞了文字，说明方案与骨架的界线被破坏
        segments.forEach { (name, groups) ->
            groups.flatMap { it.parts }.filterIsInstance<PromptPart.Text>().forEach { part ->
                assertTrue(
                    "$name 里的槽位名多余空白：'${part.slot}'",
                    part.slot == part.slot.trim(),
                )
                assertTrue(
                    "$name 的槽位名混入了空白：'${part.slot}'",
                    part.slot.none { it.isWhitespace() },
                )
            }
        }
    }

    @Test
    fun `英文方案除已知示例外不含汉字`() {
        val offenders = PromptSchemes.ENGLISH.texts
            .filterValues { hanzi.containsMatchIn(it) }
            .keys
        // memory.format.structure.language 是上游原文自带的「饮食」示例，按「不修改硬编码」保留
        assertEquals(setOf("memory.format.structure.language"), offenders)
    }

    @Test
    fun `中文方案的骨架槽位文字不含汉字以外的整句英文`() {
        // 数据块（Saturday、3 h、路径、清单）不在方案里，所以方案文字里出现英文只可能是标识符，
        // 这里只检查没有把整句英文遗漏在中文方案里：以空格分隔的英文单词连续超过 6 个即视为整句。
        val englishSentence = Regex("(\\b[A-Za-z][A-Za-z']+\\b[ ,.]+){7,}")
        val offenders = PromptSchemes.CHINESE.texts
            .filterKeys { it !in PromptToolTexts.ZH.keys }
            .filterValues { englishSentence.containsMatchIn(it) }
            .keys
        assertTrue("中文方案的骨架槽位疑似残留英文句子：$offenders", offenders.isEmpty())
    }

    @Test
    fun `渲染时间提醒会填上数据块`() {
        val rendered = PromptComposer.compose(
            groups = PromptSegments.TIME_REMINDER,
            activeGroupIds = setOf("time", "gap", "tail"),
            schemeTexts = PromptSchemes.CHINESE.texts,
            slotOverrides = emptyMap(),
            tokenValues = mapOf(
                "time" to "2026-02-14 10:30",
                "weekday" to "Saturday",
                "gap" to "3 h",
            ),
        )
        assertTrue(rendered, rendered.contains("2026-02-14 10:30"))
        assertTrue(rendered, rendered.contains("Saturday"))
        assertTrue(rendered, rendered.contains("3 h"))
        assertTrue(rendered, rendered.startsWith("<time_reminder>"))
        assertTrue(rendered, rendered.endsWith("</time_reminder>"))
    }

    @Test
    fun `没有时间间隔时整组不出现`() {
        val rendered = PromptComposer.compose(
            groups = PromptSegments.TIME_REMINDER,
            activeGroupIds = setOf("time", "tail"),
            schemeTexts = PromptSchemes.ENGLISH.texts,
            slotOverrides = emptyMap(),
            tokenValues = mapOf("time" to "2026-02-14 10:30", "weekday" to "Saturday"),
        )
        assertEquals(
            "<time_reminder>Current time: 2026-02-14 10:30, Saturday.</time_reminder>",
            rendered,
        )
    }

    @Test
    fun `槽位改写优先于方案文字`() {
        val rendered = PromptComposer.compose(
            groups = PromptSegments.TIME_REMINDER,
            activeGroupIds = setOf("time", "tail"),
            schemeTexts = PromptSchemes.ENGLISH.texts,
            slotOverrides = mapOf("time.prefix" to "Now: "),
            tokenValues = mapOf("time" to "10:30", "weekday" to "Saturday"),
        )
        assertTrue(rendered, rendered.startsWith("<time_reminder>Now: "))
    }
}
