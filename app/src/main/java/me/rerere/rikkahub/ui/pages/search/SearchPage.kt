package me.rerere.rikkahub.ui.pages.search

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Sorting01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.fts.SearchContentScope
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

@Composable
fun SearchPage(vm: SearchVM = koinViewModel()) {
    val navController = LocalNavController.current
    val focusRequester = remember { FocusRequester() }
    var showRebuildDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    if (showRebuildDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildDialog = false },
            title = { Text(stringResource(R.string.search_page_rebuild_index)) },
            text = { Text(stringResource(R.string.search_page_rebuild_index_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebuildDialog = false
                        vm.rebuildIndex()
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.search_page_title)) },
                actions = {
                    SortMenuButton(
                        current = vm.sortOrder,
                        onSortChange = { vm.onSortChange(it) },
                    )
                    IconButton(
                        onClick = { showRebuildDialog = true },
                        enabled = !vm.isRebuilding,
                    ) {
                        Icon(
                            HugeIcons.Refresh01,
                            contentDescription = stringResource(R.string.search_page_rebuild_button)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            OutlinedTextField(
                value = vm.searchQuery,
                onValueChange = { vm.onQueryChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.search_page_placeholder)) },
                shape = RoundedCornerShape(50),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { vm.search() }
                ),
            )

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            ) {
                MessageSearchScope.entries.forEachIndexed { index, scope ->
                    SegmentedButton(
                        selected = vm.searchScope == scope,
                        onClick = { vm.onScopeChange(scope) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = MessageSearchScope.entries.size,
                        ),
                    ) {
                        Text(
                            stringResource(
                                when (scope) {
                                    MessageSearchScope.CURRENT_ASSISTANT -> R.string.search_page_scope_current_assistant
                                    MessageSearchScope.ALL_ASSISTANTS -> R.string.search_page_scope_all_assistants
                                }
                            )
                        )
                    }
                }
            }

            // 检索范围：正文 / 思维链 / 两者
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            ) {
                SearchContentScope.entries.forEachIndexed { index, scope ->
                    SegmentedButton(
                        selected = vm.contentScope == scope,
                        onClick = { vm.onContentScopeChange(scope) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = SearchContentScope.entries.size,
                        ),
                    ) {
                        Text(
                            when (scope) {
                                SearchContentScope.TEXT_ONLY -> "正文"
                                SearchContentScope.REASONING_ONLY -> "思维链"
                                SearchContentScope.TEXT_AND_REASONING -> "全部"
                            }
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (vm.isLoading || vm.isRebuilding) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                when {
                    vm.isRebuilding -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val (current, total) = vm.rebuildProgress
                            Text(
                                text = if (total > 0) stringResource(
                                    R.string.search_page_rebuilding,
                                    current,
                                    total
                                ) else stringResource(R.string.search_page_rebuilding_simple),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    vm.searchQuery.isBlank() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.search_page_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    vm.results.isEmpty() && !vm.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.search_page_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(vm.results) { result ->
                                SearchResultItem(
                                    result = result,
                                    onClick = {
                                        // 先就地打开详情，不直接跳转
                                        vm.openDetail(result)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 点击结果先就地展示完整内容，再决定跳到哪儿
    vm.selectedDetail?.let { detail ->
        SearchResultDetailDialog(
            detail = detail,
            keyword = vm.searchQuery,
            onDismiss = { vm.dismissDetail() },
            onJumpToConversation = {
                vm.dismissDetail()
                navigateToChatPage(
                    navController,
                    chatId = Uuid.parse(detail.result.conversationId),
                    nodeId = Uuid.parse(detail.result.nodeId),
                    messageId = Uuid.parse(detail.result.messageId),
                    // 只定位到消息并给出切换提示，不擅自改变当前分支
                    selectBranch = false,
                )
            },
            onJumpToBranch = {
                vm.dismissDetail()
                navigateToChatPage(
                    navController,
                    chatId = Uuid.parse(detail.result.conversationId),
                    nodeId = Uuid.parse(detail.result.nodeId),
                    messageId = Uuid.parse(detail.result.messageId),
                    selectBranch = true,
                )
            },
        )
    }
}

/** 搜索结果详情：完整思维链 + 正文，关键词高亮 */
@Composable
private fun SearchResultDetailDialog(
    detail: SearchResultDetail,
    keyword: String,
    onDismiss: () -> Unit,
    onJumpToConversation: () -> Unit,
    onJumpToBranch: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = detail.result.title.ifBlank { "未命名对话" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = detail.result.updateAt.atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (detail.reasoning.isNotBlank()) {
                    Text("思维链", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = highlightKeyword(
                            text = detail.reasoning,
                            keyword = keyword,
                            background = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (detail.body.isNotBlank()) {
                    Text("正文", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = highlightKeyword(
                            text = detail.body,
                            keyword = keyword,
                            background = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (detail.reasoning.isBlank() && detail.body.isBlank()) {
                    Text("这条消息没有可显示的文本内容", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onJumpToBranch) { Text("跳转到该候选") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onJumpToConversation) { Text("跳到对话") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/** 把关键词标出来，大小写不敏感 */
private fun highlightKeyword(text: String, keyword: String, background: Color): AnnotatedString {
    if (keyword.isBlank() || text.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        while (cursor <= text.length) {
            val hit = text.indexOf(keyword, cursor, ignoreCase = true)
            if (hit < 0) {
                append(text.substring(cursor))
                break
            }
            append(text.substring(cursor, hit))
            withStyle(SpanStyle(background = background)) {
                append(text.substring(hit, hit + keyword.length))
            }
            cursor = hit + keyword.length
        }
    }
}

@Composable
private fun SortMenuButton(
    current: MessageSearchSort,
    onSortChange: (MessageSearchSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                HugeIcons.Sorting01,
                contentDescription = stringResource(R.string.search_page_sort)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MessageSearchSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                when (sort) {
                                    MessageSearchSort.RELEVANCE -> R.string.search_page_sort_relevance
                                    MessageSearchSort.NEWEST_FIRST -> R.string.search_page_sort_newest
                                    MessageSearchSort.OLDEST_FIRST -> R.string.search_page_sort_oldest
                                }
                            )
                        )
                    },
                    leadingIcon = {
                        RadioButton(
                            selected = sort == current,
                            onClick = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSortChange(sort)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    result: MessageSearchResult,
    onClick: () -> Unit,
) {
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val untitled = stringResource(R.string.search_page_untitled)
    val snippetText = buildAnnotatedString {
        val snippet = result.snippet
        var index = 0
        while (index < snippet.length) {
            val start = snippet.indexOf('[', index)
            if (start == -1) {
                append(snippet.substring(index))
                break
            }
            if (start > index) {
                append(snippet.substring(index, start))
            }
            val end = snippet.indexOf(']', start + 1)
            if (end == -1) {
                append(snippet.substring(start))
                break
            }
            val matched = snippet.substring(start + 1, end)
            withStyle(SpanStyle(background = highlightColor)) {
                append(matched)
            }
            index = end + 1
        }
    }
    val formattedTime = remember(result.updateAt) {
        result.updateAt.toLocalDateTime()
    }

    Surface(
        onClick = onClick,
        color = CustomColors.listItemColors.containerColor,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = result.title.ifBlank { untitled },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = snippetText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
