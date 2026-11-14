package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

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
    /**
     * 弹窗里每改一处就回报一次，由调用方落库。
     *
     * 不依赖「开始」按钮：哪怕只是调了下并发路数就取消、甚至直接杀掉 App，
     * 下次打开弹窗看到的也还是刚才那一份。
     */
    onConfigChange: (BatchRegenerationConfig) -> Unit = {},
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
    var keepAwake by remember { mutableStateOf(initial.keepAwakeWhileScreenOff) }

    fun currentConfig(): BatchRegenerationConfig = initial.copy(
        parallelCount = parallel,
        commitKeywords = commitKeywords,
        rerollReasoningKeywords = reasoningKeywords,
        rerollTextKeywords = textKeywords,
        maxRerollsPerLane = rerollLimit,
        rerollOnNoHit = rerollOnNoHit,
        commitScope = commitScope,
        reasoningLevelOverride = reasoningOverride,
        keepAwakeWhileScreenOff = keepAwake,
    )

    // 改动即落库。连续改动（拖并发滑杆、连打字）靠 delay 去抖，停下后只写一次
    LaunchedEffect(
        parallel,
        commitKeywords,
        reasoningKeywords,
        textKeywords,
        rerollLimit,
        rerollOnNoHit,
        commitScope,
        reasoningOverride,
        keepAwake,
    ) {
        delay(300)
        onConfigChange(currentConfig())
    }

    AlertDialog(
        // 点外部 / 返回键也算一次改动结束，先落库再关
        onDismissRequest = {
            onConfigChange(currentConfig())
            onDismiss()
        },
        title = { Text("批量重新生成") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---- 当前启用的关键词汇总（跨三组，一眼看全）----
                val enabledSummary = buildList {
                    if (commitKeywords.isNotEmpty()) {
                        add("落盘词：" + commitKeywords.joinToString("、"))
                    }
                    if (reasoningKeywords.isNotEmpty()) {
                        add("重 roll·思考：" + reasoningKeywords.joinToString("、"))
                    }
                    if (textKeywords.isNotEmpty()) {
                        add("重 roll·正文：" + textKeywords.joinToString("、"))
                    }
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "当前启用的关键词",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (enabledSummary.isEmpty()) {
                            Text(
                                text = "一个都没启用：这一批不会按关键词重 roll，也不会标记命中",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            enabledSummary.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }

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
                        text = "只对这一批生效，不改动会话本身的设置；跟随会话表示用当前对话的深度。" +
                            "这个选择会被记住，下次打开沿用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
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

                // 熄屏是否继续跑：关掉时切到别的 App 仍会跑完，只是熄屏后会暂停
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "熄屏后继续跑",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (keepAwake) {
                                "熄灭屏幕也会跑完，期间更耗电。"
                            } else {
                                "默认只在应用切到后台时继续；熄屏后会暂停，亮屏继续。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = keepAwake,
                        onCheckedChange = { keepAwake = it },
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
                    val config = currentConfig()
                    // 先落库再开跑：即使下面一路报错、或用户马上杀进程，这份设置也留下来了
                    onConfigChange(config)
                    onStart(config)
                },
            ) { Text("开始") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    // 取消也保留：改都改了，下次打开还该是这一份
                    onConfigChange(currentConfig())
                    onDismiss()
                },
            ) { Text("取消") }
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
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    val groups = remember(presets, category) {
        presets.filter { it.category == category }.map { it.group }.distinct()
    }
    var selectedGroup by remember(category) { mutableStateOf(groups.firstOrNull().orEmpty()) }
    // 分组被删空时选中的 tab 要跟着回退
    val activeGroup = if (selectedGroup in groups) selectedGroup else groups.firstOrNull().orEmpty()
    val activeKeywords = presets
        .filter { it.category == category && it.group == activeGroup }
        .map { it.keyword }

    // 开关：点一下就是「这次用它 / 这次不用它」
    fun toggle(keyword: String) {
        onChange(if (keyword in keywords) keywords - keyword else (keywords + keyword).distinct())
    }

    // 新词直接进当前库，并立刻标记为本次使用
    fun addAndStore(value: String) {
        val text = value.trim()
        if (text.isEmpty()) return
        val group = activeGroup
        if (text !in keywords) onChange((keywords + text).distinct())
        val exists = presets.any { it.category == category && it.group == group && it.keyword == text }
        if (!exists) {
            onPresetsChange(
                presets + BatchKeywordPreset(category = category, group = group, keyword = text),
            )
        }
        selectedGroup = group
    }

    // 长按确认后从库里彻底删掉，顺带从本次使用里摘掉
    fun removeFromLibrary(keyword: String) {
        onPresetsChange(
            presets.filterNot {
                it.category == category && it.group == activeGroup && it.keyword == keyword
            },
        )
        if (keyword in keywords) onChange(keywords - keyword)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 本次生效：库里的开关汇总在这里，点一下取消
        if (keywords.isNotEmpty()) {
            Text(
                text = "本次生效 ${keywords.size} 个（点一下取消）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keywords.forEach { keyword ->
                    FilterChip(
                        selected = true,
                        onClick = { toggle(keyword) },
                        label = { Text(keyword) },
                    )
                }
            }
        }

        // 词库：一个库一个 tab，选中哪个就显示哪个库里的词
        if (groups.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                groups.forEach { group ->
                    FilterChip(
                        selected = group == activeGroup,
                        onClick = { selectedGroup = group },
                        label = { Text(group.ifBlank { BatchKeywordPreset.DEFAULT_GROUP_LABEL }) },
                    )
                }
                TextButton(
                    onClick = { showNewGroup = !showNewGroup },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) { Text("＋新库", style = MaterialTheme.typography.labelMedium) }
            }

            if (activeKeywords.isEmpty()) {
                Text(
                    text = "这个库还是空的，在下面的输入框加一个就会进来。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "点词＝开关；长按＝从库里删掉",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    activeKeywords.forEach { keyword ->
                        val on = keyword in keywords
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (on) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = { toggle(keyword) },
                                onLongClick = { pendingDelete = keyword },
                            ),
                        ) {
                            Text(
                                text = if (on) "✓ $keyword" else "   $keyword",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (on) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                TextButton(
                    onClick = {
                        onPresetsChange(
                            presets.filterNot {
                                it.category == category && it.group == activeGroup
                            },
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "删掉「${activeGroup.ifBlank { BatchKeywordPreset.DEFAULT_GROUP_LABEL }}」这个库",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // 新增词：进当前库并自动开启
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                placeholder = { Text("新增一个词", style = MaterialTheme.typography.bodySmall) },
            )
            TextButton(
                enabled = draft.isNotBlank(),
                onClick = {
                    addAndStore(draft)
                    draft = ""
                },
            ) { Text("添加") }
        }

        if (showNewGroup) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    singleLine = true,
                    label = { Text("新库名") },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                )
                TextButton(
                    enabled = newGroupName.isNotBlank(),
                    onClick = {
                        val name = newGroupName.trim()
                        val exists = presets.any { it.category == category && it.group == name }
                        if (!exists) {
                            // 一个库靠它里面的词存在，先放一个词进去，随后可以删掉
                            onPresetsChange(
                                presets + BatchKeywordPreset(
                                    category = category,
                                    group = name,
                                    keyword = name,
                                ),
                            )
                        }
                        selectedGroup = name
                        newGroupName = ""
                        showNewGroup = false
                    },
                ) { Text("确定") }
            }
        }
    }

    // 长按删除要二次确认：库是持久数据，误删不划算
    pendingDelete?.let { keyword ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("从库里删除") },
            text = { Text("把「$keyword」从当前库里删掉？本次使用也会一并取消。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeFromLibrary(keyword)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}
