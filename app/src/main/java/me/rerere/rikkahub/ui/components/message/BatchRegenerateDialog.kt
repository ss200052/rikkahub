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
import me.rerere.rikkahub.data.batch.BatchKeywordLibrary
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
 * 每组下面挂着可以自己新建的词库；库必须先显式建出来才会出现 tab，
 * 新词可以只加进本次的关键词列表，也可以存进当前选中的库。
 */
@Composable
fun BatchRegenerateDialog(
    initial: BatchRegenerationConfig,
    presets: List<BatchKeywordPreset>,
    libraries: List<BatchKeywordLibrary>,
    onDismiss: () -> Unit,
    onStart: (BatchRegenerationConfig) -> Unit,
    onPresetsChange: (List<BatchKeywordPreset>) -> Unit,
    onLibrariesChange: (List<BatchKeywordLibrary>) -> Unit,
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
                    libraries = libraries,
                    onLibrariesChange = onLibrariesChange,
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
                    libraries = libraries,
                    onLibrariesChange = onLibrariesChange,
                )

                KeywordEditor(
                    title = "重 roll · 仅正文",
                    hint = "正文一出现这些词就截断重来；留空表示不按正文重 roll",
                    category = BatchKeywordCategory.TEXT,
                    keywords = textKeywords,
                    onChange = { textKeywords = it },
                    presets = presets,
                    onPresetsChange = onPresetsChange,
                    libraries = libraries,
                    onLibrariesChange = onLibrariesChange,
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
 * 库必须先显式「新建」出来才会出现 tab；不存在那种谁都没建、却一直挂着的默认库。
 * 库名单独存一份，所以空库也能一直存在，不会因为里面没词就消失。
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
    libraries: List<BatchKeywordLibrary>,
    onLibrariesChange: (List<BatchKeywordLibrary>) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var newLibraryName by remember { mutableStateOf("") }
    var showNewLibrary by remember { mutableStateOf(false) }
    var pendingDeleteKeyword by remember { mutableStateOf<String?>(null) }
    var pendingDeleteLibrary by remember { mutableStateOf<String?>(null) }

    // 这一类下已显式建出的库，按建库顺序排
    val libraryNames = remember(libraries, category) {
        libraries.filter { it.category == category }.map { it.name }
    }
    // 早期没有「库」概念时存下的词（group 为空），或者库已被删掉却还留着词：
    // 都算还没归属，等有库以后并进第一个库，避免它们凭空消失
    val orphanKeywords = remember(presets, category, libraryNames) {
        presets.filter { it.category == category && it.group !in libraryNames }.map { it.keyword }
    }

    var selectedLibrary by remember(category) { mutableStateOf<String?>(null) }
    // 只有用户点了某个库 tab 才算选中；库被删掉时自动回到「未选中」
    val activeLibrary = selectedLibrary?.takeIf { it in libraryNames }
    val activeKeywords = activeLibrary?.let { library ->
        presets.filter { it.category == category && it.group == library }.map { it.keyword }
    }.orEmpty()

    // 启用的唯一判据：本次关键词列表。「本次生效」区和库里的词条都读这一份，
    // 不存在第二份状态，任何一边取消/启用都会同时反映到另一边
    val enabledKeywords = remember(keywords) { keywords.map { it.trim() }.toSet() }

    fun isEnabled(keyword: String) = keyword.trim() in enabledKeywords

    // 列表里还没进当前库的词：有选中库时才能收，没选中时为空
    val pendingAdopt = if (activeLibrary == null) {
        emptyList()
    } else {
        keywords.map { it.trim() }.filter { text ->
            text.isNotEmpty() && presets.none {
                it.category == category && it.group == activeLibrary && it.keyword == text
            }
        }
    }

    LaunchedEffect(category, libraryNames, orphanKeywords) {
        val target = libraryNames.firstOrNull() ?: return@LaunchedEffect
        if (orphanKeywords.isEmpty()) return@LaunchedEffect
        onPresetsChange(
            presets.map { preset ->
                if (preset.category == category && preset.group !in libraryNames) {
                    preset.copy(group = target)
                } else {
                    preset
                }
            },
        )
    }

    // 开关：点一下就是「这次用它 / 这次不用它」。两边都走这里，保证状态一致
    fun toggle(keyword: String) {
        val text = keyword.trim()
        onChange(if (text in keywords) keywords - text else (keywords + text).distinct())
    }

    // 只作用于本次，不碰词库
    fun useOnce(value: String) {
        val text = value.trim()
        if (text.isEmpty()) return
        if (text !in keywords) onChange((keywords + text).distinct())
    }

    // 存进当前选中的库，并立刻标记为本次使用；没有库时不接收
    fun storeIntoLibrary(value: String) {
        val text = value.trim()
        val library = activeLibrary ?: return
        if (text.isEmpty()) return
        if (text !in keywords) onChange((keywords + text).distinct())
        val exists = presets.any {
            it.category == category && it.group == library && it.keyword == text
        }
        if (!exists) {
            onPresetsChange(
                presets + BatchKeywordPreset(category = category, group = library, keyword = text),
            )
        }
    }

    fun createLibrary(value: String) {
        val name = value.trim()
        if (name.isEmpty()) return
        if (libraryNames.any { it.equals(name, ignoreCase = true) }) return
        onLibrariesChange(libraries + BatchKeywordLibrary(category = category, name = name))
        selectedLibrary = name
    }

    // 删库：库名、库里的词、以及这些词在本次生效里的开关一起收走
    fun deleteLibrary(name: String) {
        val removed = presets
            .filter { it.category == category && it.group == name }
            .map { it.keyword }
        onLibrariesChange(libraries.filterNot { it.category == category && it.name == name })
        onPresetsChange(presets.filterNot { it.category == category && it.group == name })
        if (removed.isNotEmpty()) onChange(keywords - removed.toSet())
        selectedLibrary = null
    }

    // 长按确认后从库里彻底删掉，顺带从本次使用里摘掉
    fun removeFromLibrary(keyword: String) {
        val library = activeLibrary ?: return
        onPresetsChange(
            presets.filterNot {
                it.category == category && it.group == library && it.keyword == keyword
            },
        )
        val text = keyword.trim()
        if (text in keywords) onChange(keywords - text)
    }

    // 把「本次生效」里已有的词收进当前库：不必删掉重输一遍。
    // 已经在这个库里的词跳过，不会重复写。
    fun adoptIntoLibrary(keyword: String) {
        val library = activeLibrary ?: return
        val text = keyword.trim()
        if (text.isEmpty()) return
        val exists = presets.any {
            it.category == category && it.group == library && it.keyword == text
        }
        if (exists) return
        onPresetsChange(
            presets + BatchKeywordPreset(category = category, group = library, keyword = text),
        )
    }

    // 一键把列表里还没进当前库的词全收进去
    fun adoptAllIntoLibrary() {
        val library = activeLibrary ?: return
        val missing = keywords
            .map { it.trim() }
            .filter { text ->
                text.isNotEmpty() && presets.none {
                    it.category == category && it.group == library && it.keyword == text
                }
            }
            .distinct()
        if (missing.isEmpty()) return
        onPresetsChange(
            presets + missing.map {
                BatchKeywordPreset(category = category, group = library, keyword = it)
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 本次生效：点一下取消；长按收进当前库（没选中库时长按什么也不做）
        if (keywords.isNotEmpty()) {
            Text(
                text = if (activeLibrary != null) {
                    "本次生效 ${keywords.size} 个（点一下取消，长按收进「$activeLibrary」）"
                } else {
                    "本次生效 ${keywords.size} 个（点一下取消）"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                keywords.forEach { keyword ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.combinedClickable(
                            onClick = { toggle(keyword) },
                            onLongClick = { adoptIntoLibrary(keyword) },
                        ),
                    ) {
                        Text(
                            text = "✓ $keyword",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            // 一键把列表里还没进库的词全收进去；没选中库、或全都已在库里时不显示
            if (activeLibrary != null && pendingAdopt.isNotEmpty()) {
                TextButton(
                    onClick = { adoptAllIntoLibrary() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "把列表里这 ${pendingAdopt.size} 个词收进「$activeLibrary」",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // 词库 tab：必须先「新建」出来才会出现，没有隐式的默认库。
        // 再点一次已选中的库可以取消选中，回到「只用关键词列表」的状态
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            libraryNames.forEach { library ->
                FilterChip(
                    selected = library == activeLibrary,
                    onClick = {
                        selectedLibrary = if (library == activeLibrary) null else library
                    },
                    label = { Text(library) },
                )
            }
            TextButton(
                onClick = { showNewLibrary = !showNewLibrary },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
            ) { Text("＋新建库", style = MaterialTheme.typography.labelMedium) }
        }

        when {
            libraryNames.isEmpty() -> Text(
                text = "还没有词库。点上面的「＋新建库」建一个，或者直接用下面的「加入关键词列表」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            activeLibrary == null -> Text(
                text = "点上面任意一个库，可以看里面的词、往里加词。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            activeKeywords.isEmpty() -> Text(
                text = "「$activeLibrary」还是空的，用下面的「加入当前库」加词。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
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
                        val on = isEnabled(keyword)
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (on) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = { toggle(keyword) },
                                onLongClick = { pendingDeleteKeyword = keyword },
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
            }
        }

        // 删库要二次确认：库名、库里的词、以及这些词在本次生效里的开关会一起收走
        if (activeLibrary != null) {
            TextButton(
                onClick = { pendingDeleteLibrary = activeLibrary },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text = "删掉「$activeLibrary」这个库",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // 新增词：两个动作分开。「加入关键词列表」只作用于本次；
        // 「加入当前库」写进选中的库，未选中库时不显示这个按钮
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("新增一个词", style = MaterialTheme.typography.bodySmall) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        useOnce(draft)
                        draft = ""
                    },
                ) { Text("加入关键词列表") }
                if (activeLibrary != null) {
                    TextButton(
                        enabled = draft.isNotBlank(),
                        onClick = {
                            storeIntoLibrary(draft)
                            draft = ""
                        },
                    ) { Text("加入当前库") }
                }
            }
        }

        // 新建库：建出来就是一个空库，立刻选中它
        if (showNewLibrary) {
            val name = newLibraryName.trim()
            val duplicated = name.isNotEmpty() && libraryNames.any { it.equals(name, ignoreCase = true) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newLibraryName,
                    onValueChange = { newLibraryName = it },
                    singleLine = true,
                    label = { Text("新库名") },
                    isError = duplicated,
                    supportingText = if (duplicated) {
                        { Text("已经有同名的库了", style = MaterialTheme.typography.labelSmall) }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                )
                TextButton(
                    enabled = name.isNotEmpty() && !duplicated,
                    onClick = {
                        createLibrary(newLibraryName)
                        newLibraryName = ""
                        showNewLibrary = false
                    },
                ) { Text("建库") }
            }
        }
    }

    // 长按删词要二次确认：库是持久数据，误删不划算
    pendingDeleteKeyword?.let { keyword ->
        AlertDialog(
            onDismissRequest = { pendingDeleteKeyword = null },
            title = { Text("从库里删除") },
            text = { Text("把「$keyword」从当前库里删掉？本次使用也会一并取消。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeFromLibrary(keyword)
                        pendingDeleteKeyword = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteKeyword = null }) { Text("取消") }
            },
        )
    }

    // 删库要确认：库名、库里的词、以及这些词的开关会一起没
    pendingDeleteLibrary?.let { library ->
        AlertDialog(
            onDismissRequest = { pendingDeleteLibrary = null },
            title = { Text("删除词库") },
            text = { Text("「$library」里的词会一起删掉，本次已启用的也会取消。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteLibrary(library)
                        pendingDeleteLibrary = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteLibrary = null }) { Text("取消") }
            },
        )
    }
}
