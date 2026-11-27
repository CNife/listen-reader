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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.LibraryFilter
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.service.AvailableUpdate
import com.cnife.listenreader.service.LibraryState
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.UpdateState
import com.cnife.listenreader.ui.components.CardDivider
import com.cnife.listenreader.ui.components.EmptyState
import com.cnife.listenreader.ui.components.IndeterminateProgressLine
import com.cnife.listenreader.ui.components.LrCard
import com.cnife.listenreader.ui.components.Masthead
import com.cnife.listenreader.ui.components.PrimaryAction
import com.cnife.listenreader.ui.components.ProgressLine
import com.cnife.listenreader.ui.components.QuietAction
import com.cnife.listenreader.ui.components.SearchField
import com.cnife.listenreader.ui.components.StatusLine
import com.cnife.listenreader.ui.components.TextTabs
import com.cnife.listenreader.ui.components.openExternalUrl
import com.cnife.listenreader.ui.theme.Canvas
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Meta
import com.cnife.listenreader.ui.theme.StickerPurple
import com.cnife.listenreader.ui.theme.StickerPurpleInk
import com.cnife.listenreader.ui.theme.StickerSky

@Composable
fun LibraryScreen(
    service: ReadingService,
    onNewCapture: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenResource: (String) -> Unit,
) {
    val state by service.library.collectAsStateWithLifecycle()
    val settings by service.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<LibraryItem?>(null) }
    var searchExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas),
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
                update = settings.update,
                onOpenUpdate = { url -> openExternalUrl(context, url) },
                onDismissUpdate = service::dismissUpdate,
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
    update: UpdateState,
    onOpenUpdate: (String) -> Unit,
    onDismissUpdate: () -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        if (isLoading) {
            IndeterminateProgressLine(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                text = "正在加载",
                style = MaterialTheme.typography.bodySmall,
                color = Meta,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
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
                    .padding(top = 8.dp)
                    .testTag("library-error"),
            )
        }
        update.release?.takeIf { !update.ignored }?.let { release ->
            UpdateNotice(
                update = release,
                onOpen = { onOpenUpdate(release.url) },
                onDismiss = onDismissUpdate,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp)
                    .testTag("library-update"),
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
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp),
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

        when {
            state.items.isEmpty() && filtersActive -> EmptyState(
                title = "没有匹配的内容",
                description = "换个词，或清除筛选。",
                actionLabel = "清除筛选",
                onAction = clearFilters,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp)
                    .testTag("library-no-results"),
            )
            state.items.isEmpty() && isLoading -> Unit
            state.items.isEmpty() -> EmptyState(
                title = "还没有内容",
                description = "粘贴一篇长文，或输入网页链接。提取后会生成语音。",
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp),
            )
            else -> LrCard(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp),
                padding = PaddingValues(0.dp),
            ) {
                state.items.forEachIndexed { index, item ->
                    LibraryRow(
                        item = item,
                        onClick = { onOpenResource(item.id) },
                        onDelete = { onDeleteRequest(item) },
                    )
                    if (index != state.items.lastIndex) {
                        CardDivider(startIndent = 60.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateNotice(
    update: AvailableUpdate,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LrCard(
        modifier = modifier.fillMaxWidth(),
        padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "发现新版本 v${update.version}",
            style = MaterialTheme.typography.titleSmall,
            color = Ink,
        )
        Row {
            QuietAction(text = "去更新", onClick = onOpen, testTag = "library-update-open")
            Spacer(Modifier.width(16.dp))
            QuietAction(text = "忽略", onClick = onDismiss, testTag = "library-update-dismiss")
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
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceTile(item.sourceKind)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = Ink,
            )
            Text(
                text = "${item.sourceLabel} · ${formatRelativeTime(item.createdAt)}",
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Meta,
            )
            if (percent > 0) {
                ProgressLine(
                    progress = percent / 100f,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
            color = Meta,
            textAlign = TextAlign.End,
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .size(44.dp)
                .testTag("library-delete-${item.id}"),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除",
                tint = Meta,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SourceTile(kind: SourceKind) {
    val isWeb = kind == SourceKind.URL
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isWeb) StickerSky else StickerPurple),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isWeb) Icons.Filled.Language else Icons.AutoMirrored.Filled.Subject,
            contentDescription = null,
            tint = if (isWeb) Color.White else StickerPurpleInk,
            modifier = Modifier.size(17.dp),
        )
    }
}

private val FILTER_LABELS = listOf(
    LibraryFilter.ALL.name to "全部",
    LibraryFilter.WEB.name to "网页",
    LibraryFilter.TEXT.name to "粘贴文本",
    LibraryFilter.CACHED.name to "已缓存",
)
