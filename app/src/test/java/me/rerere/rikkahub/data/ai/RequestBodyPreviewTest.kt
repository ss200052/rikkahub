package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.transformers.renderTimeReminder
import me.rerere.rikkahub.data.ai.transformers.renderWorkspaceReminder
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANTS
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Test
import java.io.File
import kotlin.time.Instant

/**
 * 把当前两套方案渲染出来的真实请求体导出成文本，供人工核对。
 *
 * 不涉及网络与数据库：骨架、方案文字、数据块填充全部是本地纯函数。
 */
class RequestBodyPreviewTest {

    private val out = StringBuilder()

    private fun section(title: String) {
        out.append("\n\n").append("=".repeat(70)).append("\n")
        out.append(title).append("\n")
        out.append("=".repeat(70)).append("\n")
    }

    private fun block(text: String) {
        out.append("\n---\n").append(text.trim()).append("\n")
    }

    @Test
    fun `dump request body of both schemes`() {
        val instant = LocalDateTime(2026, 2, 14, 10, 30).toInstant(TimeZone.of("Asia/Shanghai"))
        val assistant = DEFAULT_ASSISTANTS.firstOrNull { it.systemPrompt.isNotBlank() }
            ?: DEFAULT_ASSISTANTS.first()

        for (scheme in PromptSchemes.all) {
            section("方案：${scheme.id}（${PromptSchemes.displayName(scheme)}）")

            out.append("\n【1】助手的系统提示词（用户原始内容，两套方案相同）\n")
            block(assistant.systemPrompt)

            out.append("\n【2】时间提醒（有间隔时出现的合成消息）\n")
            block(
                renderTimeReminder(
                    gapSeconds = 3 * 3600,
                    instant = instant,
                    schemeId = scheme.id,
                ),
            )

            out.append("\n【3】工作区提醒（配了工作区才出现）\n")
            block(renderWorkspaceSegmentsForPreview(scheme.id))

            out.append("\n【4】记忆段（开了记忆才出现）\n")
            block(renderMemoryForPreview(scheme.id))
        }

        section("工具描述 · get_time_info")
        out.append("\n英文（源码原文）：\n")
        block("Get the current local date and time info from the device.\n" +
            "Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.")
        out.append("\n中文（方案覆盖）：\n")
        block(PromptToolTexts.ZH["tool.get_time_info.description"] ?: "(未找到中文条目)")

        section("当前启用的工具清单（取方案文字）")
        PromptSchemes.all.forEach { scheme ->
            out.append("\n[${scheme.id}] ")
            out.append(scheme.texts["tool.list.head"] ?: "(无)")
            out.append("\n")
        }

        val target = File("build/request-body-preview.txt")
        target.parentFile?.mkdirs()
        target.writeText(out.toString())
        println("written: " + target.absolutePath)
    }

    /** 工作区提醒：用与运行时相同的骨架渲染，数据块填一份示例值 */
    private fun renderWorkspaceSegmentsForPreview(schemeId: String): String {
        val texts = PromptSchemes.of(schemeId).texts
        return PromptComposer.compose(
            groups = PromptSegments.WORKSPACE,
            activeGroupIds = setOf("head", "body", "close", "distro", "shell_bash", "cwd"),
            schemeTexts = texts,
            slotOverrides = emptyMap(),
            tokenValues = mapOf(
                "workspace_name" to "笔记",
                "distro" to "Debian 12",
                "shell" to "/bin/bash",
                "cwd" to "notes/",
            ),
        )
    }

    private fun renderMemoryForPreview(schemeId: String): String {
        val texts = PromptSchemes.of(schemeId).texts
        return PromptComposer.compose(
            groups = PromptSegments.MEMORY,
            activeGroupIds = setOf("all"),
            schemeTexts = texts,
            slotOverrides = emptyMap(),
            tokenValues = mapOf(
                "profile_version" to "3",
                "profile_content" to "用户偏好简洁回答;常用语言为简体中文。",
                "preferences_version" to "5",
                "preferences_content" to "回复不要用感叹号;技术问题优先给结论。",
                "memory_listing" to "work.md (2 KB)\nideas.md (1 KB)",
            ),
        )
    }
}
