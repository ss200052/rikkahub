package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.batch.BatchKeywordCategory
import me.rerere.rikkahub.data.batch.BatchKeywordPreset
import me.rerere.rikkahub.data.batch.BatchRegenerationConfig
import me.rerere.rikkahub.data.batch.KeywordScope
import kotlin.math.roundToInt

/** 落盘词的匹配范围选项 */
private enum class CommitScopeOption(val scope: KeywordScope, val label: String) {
    TEXT(KeywordScope.TEXT_ONLY, "仅正文"),
    REASONING(KeywordScope.REASONING_ONLY, "仅思考"),
    BOTH(KeywordScope.TEXT_AND_REASONING, "正文+思考"),
}

/** 思考深度在弹窗里的显示名 */
private fun reasoningLabel(level: ReasoningLevel): String = when (level) {
    ReasoningLevel.OFF -> "关闭"
    ReasoningLevel.AUTO -> "自动"
    ReasoningLevel.LOW -> "低"
    ReasoningLevel.MEDIUM -> "中"
    ReasoningLevel.HIGH -> "高"
    ReasoningLevel.XHIGH -> "更高"
    ReasoningLevel.MAX -> "最高"
}

/**
 * 批量重新生成的参数弹窗。
 *
 * 关键词分三组独立配置：落盘标记词、思考内容重 roll 词、正文重 roll 词。
 * 每组下面挂着一份关键词库：库按自定义 tab 分组，点一下就把词加进当前这组，
 * 当前这组的关键词也可以反向存进库里。
 */
@Composable
fun BatchRegenerateDialog(
    initial: BatchRegenerationConfig,
    presets: List<BatchKeywordPreset>,
    onDismiss: () -> Unit,
    onStart: (BatchRegenerationConfig) -> Unit,
    onPresetsChange: (List<BatchKeywordPreset>) -> Unit,
) {
    var parallel by remember {
        mutableIntStateOf(initial.parallelCount.coerceIn(1, BatchRegenerationConfig.MAX_PARALLEL))
    }
    var commitKeywords by remember { mutableStateOf(initial.commitKeywords) }
    var reasoningKeywords by remember { mutableStateOf(initial.rerollReasoningKeywords) }
    var textKeywords by remember { mutableStateOf(initial.rerollTextKeywords) }
    var rerollLimit by remember { mutableStateOf(initial.maxRerollsPerLane) }
    var rerollOnNoHit by remember { mutableStateOf(initial.rerollOnNoHit) }
    var commitScope by remember { mutableStateOf(initial.commitScope) }
    var reasoningOverride by remember { mutableStateOf(initial.reasoningLevelOverride) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量重新生成") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---- 并行路数 ----
                Column {
                    Text(
                        text = "并行路数：$parallel",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Slider(
                        value = parallel.toFloat(),
                        onValueChange = { parallel = it.roundToInt() },
                        valueRange = 1f..BatchRegenerationConfig.MAX_PARALLEL.toFloat(),
                        steps = BatchRegenerationConfig.MAX_PARALLEL - 2,
                    )
                }

                HorizontalDivider()

                // ---- 思考深度 ----
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "思考深度", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "只对这一批生效，不改动会话本身的设置；跟随会话表示用当前对话的深度",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = reasoningOverride == null,
                            onClick = { reasoningOverride = null },
                            label = { Text("跟随会话") },
                        )
                        ReasoningLevel.entries.forEach { level ->
                            FilterChip(
                                selected = reasoningOverride == level,
                                onClick = { reasoningOverride = level },
                                label = { Text(reasoningLabel(level)) },
                            )
                        }
                    }
                }

                HorizontalDivider()

                KeywordEditor(
                    title = "落盘标记词",
                    hint = "回复中出现这些词就标记为「像样」，不会自动落盘；留空表示不标记",
                    category = BatchKeywordCategory.COMMIT,
                    keywords = commitKeywords,
                    onChange = { commitKeywords = it },
                    presets = presets,
                    onPresetsChange = onPresetsChange,
                )

                // 匹配范围：默认只看正文，避免思考里提到一次就被标记
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "落盘词的匹配范围",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CommitScopeOption.entries.forEach { option ->
                            FilterChip(
                                selected = commitScope == option.scope,
                                onClick = { commitScope = option.scope },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }

                KeywordEditor(
                    title = "重 roll · 仅思考内容",
                    hint = "思考内容一出现这些词就截断重来；留空表示不按思考重 roll",
                    category = BatchKeywordCategory.REASONING,
                    keywords = reasoningKeywords,
                    onChange = { reasoningKeywords = it },
                    presets = presets,
                    onPresetsChange = onPresetsChange,
                )

                KeywordEditor(
                    title = "重 roll · 仅正文",
                    hint = "正文一出现这些词就截断重来；留空表示不按正文重 roll",
                    category = BatchKeywordCategory.TEXT,
                    keywords = textKeywords,
                    onChange = { textKeywords = it },
                    presets = presets,
                    onPresetsChange = onPresetsChange,
                )

                HorizontalDivider()

                // ---- 重 roll 轮次 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = if (rerollLimit <= 0) "" else rerollLimit.toString(),
                        onValueChange = { input ->
                            rerollLimit = input.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
                        },
                        label = { Text("重 roll 轮次（0 = 不限）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "未命中关键词也重 roll",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = rerollOnNoHit,
                        onCheckedChange = { rerollOnNoHit = it },
                    )
                }

                HorizontalDivider()

                // ---- 成本提示 ----
                Text(
                    text = "落盘由你手动点选，命中落盘词只会标记、不会自动写入对话。" +
                        "轮次填 0 表示不限，靠面板里的停止按钮兜底。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onStart(
                        initial.copy(
                            parallelCount = parallel,
                            commitKeywords = commitKeywords,
                            rerollReasoningKeywords = reasoningKeywords,
                            rerollTextKeywords = textKeywords,
                            maxRerollsPerLane = rerollLimit,
                            rerollOnNoHit = rerollOnNoHit,
                            commitScope = commitScope,
                            reasoningLevelOverride = reasoningOverride,
                        ),
                    )
                },
            ) { Text("开始") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 一组关键词的编辑区，下面挂着属于这一类别的关键词库。
 *
 * 库的 tab 是用户自己起的分组名；点库里的词就加进上面这组，
 * 也可以把上面这组现有的词存进库里（存进当前 tab，或存成一个新分组）。
 */
@Composable
private fun KeywordEditor(
    title: String,
    hint: String,
    category: BatchKeywordCategory,
    keywords: List<String>,
    onChange: (List<String>) -> Unit,
    presets: List<BatchKeywordPreset>,
    onPresetsChange: (List<BatchKeywordPreset>) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var newGroupName by remember { mutableStateOf("") }
    var showNewGroup by remember { mutableStateOf(false) }

    val groups = remember(presets, category) {
        presets.filter { it.category == category }.map { it.group }.distinct()
    }
    var selectedGroup by remember(category) { mutableStateOf(groups.firstOrNull().orEmpty()) }
    // 分组可能被删空，选中的 tab 要跟着回退
    val activeGroup = if (selectedGroup in groups) selectedGroup else groups.firstOrNull().orEmpty()
    val groupKeywords = presets
        .filter { it.category == category && it.group == activeGroup }
        .map { it.keyword }

    fun addKeyword(value: String) {
        val text = value.trim()
        if (text.isNotEmpty()) onChange((keywords + text).distinct())
    }

    fun storeInto(group: String) {
        if (keywords.isEmpty()) return
        val existing = presets.map { Triple(it.category, it.group, it.keyword) }.toSet()
        val additions = keywords
            .filter { Triple(category, group, it) !in existing }
            .map { BatchKeywordPreset(category = category, group = group, keyword = it) }
        if (additions.isNotEmpty()) onPresetsChange(presets + additions)
        selectedGroup = group
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (keywords.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keywords.forEach { keyword ->
                    FilterChip(
                        selected = true,
                        onClick = { onChange(keywords - keyword) },
                        label = { Text(keyword) },
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                placeholder = { Text("输入关键词", style = MaterialTheme.typography.bodySmall) },
            )
            TextButton(
                enabled = draft.isNotBlank(),
                onClick = {
                    addKeyword(draft)
                    draft = ""
                },
            ) { Text("添加") }
        }

        // ---- 关键词库：分类内的自定义 tab ----
        if (groups.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { group ->
                    FilterChip(
                        selected = group == activeGroup,
                        onClick = { selectedGroup = group },
                        label = {
                            Text(group.ifBlank { BatchKeywordPreset.DEFAULT_GROUP_LABEL })
                        },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                groupKeywords.forEach { keyword ->
                    InputChip(
                        selected = false,
                        onClick = { addKeyword(keyword) },
                        label = { Text(keyword) },
                        trailingIcon = {
                            Text(
                                text = "×",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable {
                                        onPresetsChange(
                                            presets.filterNot {
                                                it.category == category &&
                                                    it.group == activeGroup &&
                                                    it.keyword == keyword
                                            },
                                        )
                                    }
                                    .padding(start = 2.dp, end = 4.dp),
                            )
                        },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                enabled = keywords.isNotEmpty(),
                onClick = { storeInto(activeGroup) },
            ) {
                Text(
                    text = if (groups.isEmpty()) "存进库" else "存进当前分组",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(
                enabled = keywords.isNotEmpty(),
                onClick = { showNewGroup = !showNewGroup },
            ) { Text("存为新分组", style = MaterialTheme.typography.labelMedium) }
            if (groupKeywords.isNotEmpty()) {
                TextButton(
                    onClick = {
                        onPresetsChange(
                            presets.filterNot {
                                it.category == category && it.group == activeGroup
                            },
                        )
                    },
                ) { Text("删掉本组", style = MaterialTheme.typography.labelMedium) }
            }
        }

        if (showNewGroup) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    singleLine = true,
                    label = { Text("新分组名") },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                )
                TextButton(
                    enabled = newGroupName.isNotBlank() && keywords.isNotEmpty(),
                    onClick = {
                        val name = newGroupName.trim()
                        storeInto(name)
                        newGroupName = ""
                        showNewGroup = false
                    },
                ) { Text("确定") }
            }
        }
    }
}
