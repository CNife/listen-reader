package com.cnife.listenreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.LibraryFilter
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.service.LibraryState
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.ui.components.EmptyState
import com.cnife.listenreader.ui.components.IndeterminateProgressLine
import com.cnife.listenreader.ui.components.Masthead
import com.cnife.listenreader.ui.components.PrimaryAction
import com.cnife.listenreader.ui.components.ProgressLine
import com.cnife.listenreader.ui.components.SearchField
import com.cnife.listenreader.ui.components.StatusLine
import com.cnife.listenreader.ui.components.TextTabs
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Ink2
import com.cnife.listenreader.ui.theme.Paper
import com.cnife.listenreader.ui.theme.Rule

@Composable
fun LibraryScreen(
    service: ReadingService,
    onNewCapture: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenResource: (String) -> Unit,
) {
    val state by service.library.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<LibraryItem?>(null) }
    var searchExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        Masthead(
            title = "听我读",
            meta = mastheadMeta(state),
            onSearch = {
                if (searchExpanded) {
                    service.setLibraryQuery("")
                    searchExpanded = false
                } else {
                    searchExpanded = true
                }
            },
            onSettings = onOpenSettings,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LibraryContent(
                state = state,
                isLoading = state.isLoading,
                searchExpanded = searchExpanded,
                onOpenResource = onOpenResource,
                onDeleteRequest = { pendingDelete = it },
                onQueryChange = service::setLibraryQuery,
                onFilterChange = service::setLibraryFilter,
                onRetry = service::refreshLibrary,
            )
        }
        PrimaryAction(
            text = "添加内容",
            onClick = onNewCapture,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            testTag = "library-new",
        )
    }

    pendingDelete?.let { item ->
        DeleteResourceDialog(
            onConfirm = {
                pendingDelete = null
                service.deleteResource(item.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

private fun mastheadMeta(state: LibraryState): String =
    if (state.isLoading) "正在加载…" else "${state.items.size} 篇"

@Composable
private fun LibraryContent(
    state: LibraryState,
    isLoading: Boolean,
    searchExpanded: Boolean,
    onOpenResource: (String) -> Unit,
    onDeleteRequest: (LibraryItem) -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    onRetry: () -> Unit,
) {
    val clearFilters = {
        onQueryChange("")
        onFilterChange(LibraryFilter.ALL)
    }
    val filtersActive = state.query.isNotBlank() || state.filter != LibraryFilter.ALL

    Column(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            IndeterminateProgressLine(modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                text = "正在加载",
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        state.error?.let { error ->
            StatusLine(
                title = "出错",
                description = error.message,
                actionLabel = "重试",
                onAction = onRetry,
                actionTag = "library-retry",
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .testTag("library-error"),
            )
        }

        state.continueListening?.let { item ->
            ContinueCard(
                item = item,
                onClick = { onOpenResource(item.id) },
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp),
            )
        }

        if (searchExpanded) {
            SearchField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                testTag = "library-search",
            )
        }

        TextTabs(
            options = FILTER_LABELS,
            selectedKey = state.filter.name,
            onSelect = { key -> onFilterChange(LibraryFilter.valueOf(key)) },
            modifier = Modifier.padding(top = 8.dp),
            tagPrefix = "library-filter",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Rule),
        )

        when {
            state.items.isEmpty() && filtersActive -> EmptyState(
                title = "没有匹配的内容",
                description = "换个词，或清除筛选。",
                actionLabel = "清除筛选",
                onAction = clearFilters,
                modifier = Modifier.testTag("library-no-results"),
            )
            state.items.isEmpty() && isLoading -> Unit
            state.items.isEmpty() -> EmptyState(
                title = "还没有内容",
                description = "粘贴一篇长文，或输入网页链接。提取后会生成语音。",
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            ) {
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    LibraryRow(
                        item = item,
                        onClick = { onOpenResource(item.id) },
                        onDelete = { onDeleteRequest(item) },
                    )
                    if (index != state.items.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Rule),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    item: LibraryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val percent = libraryProgressPercent(item)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library-row-${item.id}")
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${item.sourceLabel} · ${formatRelativeTime(item.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
            )
            if (percent > 0) {
                Spacer(Modifier.height(8.dp))
                ProgressLine(progress = percent / 100f)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
            color = Ink2,
            textAlign = TextAlign.End,
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .size(48.dp)
                .testTag("library-delete-${item.id}"),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除",
                tint = Ink2,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private val FILTER_LABELS = listOf(
    LibraryFilter.ALL.name to "全部",
    LibraryFilter.WEB.name to "网页",
    LibraryFilter.TEXT.name to "粘贴文本",
    LibraryFilter.CACHED.name to "已缓存",
)
