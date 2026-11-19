package com.cnife.listenreader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.LibraryFilter
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.service.LibraryState
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    service: ReadingService,
    onNewCapture: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenResource: (String) -> Unit,
) {
    val state by service.library.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<LibraryItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("听我读") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.isLoading) {
                FloatingActionButton(
                    onClick = onNewCapture,
                    modifier = Modifier.testTag("library-new"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新建")
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
        if (state.isLoading) {
            Column(modifier = contentModifier) {
                Text(
                    text = "正在加载…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LibraryContent(
                state = state,
                contentModifier = contentModifier,
                onNewCapture = onNewCapture,
                onOpenResource = onOpenResource,
                onDeleteRequest = { pendingDelete = it },
                onQueryChange = service::setLibraryQuery,
                onFilterChange = service::setLibraryFilter,
            )
        }
    }

    pendingDelete?.let { item ->
        DeleteResourceDialog(
            item = item,
            onConfirm = {
                pendingDelete = null
                service.deleteResource(item.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun LibraryContent(
    state: LibraryState,
    contentModifier: Modifier,
    onNewCapture: () -> Unit,
    onOpenResource: (String) -> Unit,
    onDeleteRequest: (LibraryItem) -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
) {
    val clearFilters = {
        onQueryChange("")
        onFilterChange(LibraryFilter.ALL)
    }
    val filtersActive = state.query.isNotBlank() || state.filter != LibraryFilter.ALL

    Column(modifier = contentModifier) {
        state.continueListening?.let { item ->
            ContinueCard(
                item = item,
                onClick = { onOpenResource(item.id) },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
            )
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
                .testTag("library-search"),
            placeholder = { Text("搜索标题或来源") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FILTER_LABELS.forEach { (filter, label) ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("library-filter-${filter.name}"),
                )
            }
        }
        state.error?.let { error ->
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
                    .testTag("library-error"),
            )
        }
        when {
            state.items.isEmpty() && filtersActive -> EmptyState(
                title = "没有匹配的内容",
                description = "换个关键词，或清除搜索与筛选看看。",
                actionLabel = "清除筛选",
                onAction = clearFilters,
                modifier = Modifier.testTag("library-no-results"),
            )
            state.items.isEmpty() -> EmptyState(
                title = "还没有内容",
                description = "把网页链接或一段文字加进来，听我读会把它变成可以收听的朗读文稿。",
                actionLabel = "新建",
                onAction = onNewCapture,
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    LibraryRow(
                        item = item,
                        onClick = { onOpenResource(item.id) },
                        onDelete = { onDeleteRequest(item) },
                    )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = item.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${item.sourceLabel} · ${item.segmentCount} 句",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.segmentCount > 0) {
                Text(
                    text = "第 ${(item.currentSegmentIndex + 1).coerceAtMost(item.segmentCount)} / ${item.segmentCount} 句",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.hasCachedAudio) {
                Text(
                    text = "已缓存",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.testTag("library-delete-${item.id}"),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "删除")
        }
    }
}

private val FILTER_LABELS = listOf(
    LibraryFilter.ALL to "全部",
    LibraryFilter.WEB to "网页",
    LibraryFilter.TEXT to "粘贴文本",
    LibraryFilter.CACHED to "已缓存",
)
