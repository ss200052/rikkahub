package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.PromptComposer
import me.rerere.rikkahub.data.ai.PromptSchemes
import me.rerere.rikkahub.data.ai.PromptSegments
import me.rerere.rikkahub.data.ai.SystemPromptOverride
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** 时间提醒在覆盖表里的键 */
internal const val TIME_REMINDER_ID = "segment.time_reminder"

/**
 * 时间提醒注入转换器
 *
 * 在时间间隔较大的消息之前自动注入 <time_reminder>，帮助 AI 了解对话的时间间隔
 */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.enableTimeReminder) return messages
        val override = ctx.settings.systemPromptOverrides
            .firstOrNull { it.blockId == TIME_REMINDER_ID }
        return applyTimeReminder(
            messages = messages,
            intervalMinutes = ctx.assistant.timeReminderIntervalMinutes,
            schemeId = ctx.settings.promptSchemeId,
            override = override,
        )
    }
}

internal fun applyTimeReminder(
    messages: List<UIMessage>,
    intervalMinutes: Int = 60,
    schemeId: String = PromptSchemes.ENGLISH.id,
    override: SystemPromptOverride? = null,
): List<UIMessage> {
    val thresholdSeconds = intervalMinutes.coerceAtLeast(1).toLong() * 60
    val result = mutableListOf<UIMessage>()
    val tz = TimeZone.currentSystemDefault()

    var firstUserFound = false
    for (i in messages.indices) {
        val current = messages[i]
        if (current.role == MessageRole.USER) {
            val currInstant = current.createdAt.toInstant(tz)
            if (!firstUserFound) {
                firstUserFound = true
                result.add(buildTimeReminderMessage(null, currInstant, schemeId, override))
            } else {
                val previous = messages[i - 1]
                val prevInstant = previous.createdAt.toInstant(tz)
                val gapSeconds = (currInstant - prevInstant).inWholeSeconds

                if (gapSeconds > thresholdSeconds) {
                    result.add(buildTimeReminderMessage(gapSeconds, currInstant, schemeId, override))
                }
            }
        }
        result.add(current)
    }

    return result
}

/**
 * 时间提醒正文：骨架固定，`«»` 里的文字按方案取，`‹›` 里的数据按当前时间填。
 *
 * 数据块一律是英文形态（`Saturday`、`3 h`），两套方案完全相同——它们不是自然语言，不翻译。
 */
internal fun renderTimeReminder(
    gapSeconds: Long?,
    instant: Instant,
    schemeId: String = PromptSchemes.ENGLISH.id,
    override: SystemPromptOverride? = null,
): String {
    val javaInstant = instant.toJavaInstant()
    val active = buildSet {
        add("time")
        add("tail")
        if (gapSeconds != null) add("gap")
    }
    return PromptComposer.compose(
        groups = PromptSegments.TIME_REMINDER,
        activeGroupIds = active,
        schemeTexts = PromptSchemes.of(schemeId).texts,
        slotOverrides = override?.slots.orEmpty(),
        tokenValues = mapOf(
            "time" to javaInstant.toLocalDateTime(),
            "weekday" to javaInstant.atZone(ZoneId.systemDefault()).dayOfWeek
                .getDisplayName(TextStyle.FULL, Locale.ENGLISH),
            "gap" to (gapSeconds?.let { formatDuration(it) } ?: ""),
        ),
    )
}

/** 时长按量级取单位；这是数据形态，不是自然语言，两套方案共用 */
internal fun formatDuration(seconds: Long): String = when {
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86400 -> "${seconds / 3600} h"
    else -> "${seconds / 86400} d"
}

private fun buildTimeReminderMessage(
    gapSeconds: Long?,
    instant: Instant,
    schemeId: String,
    override: SystemPromptOverride? = null,
): UIMessage = UIMessage.user(renderTimeReminder(gapSeconds, instant, schemeId, override))
    .copy(isSynthetic = true)
