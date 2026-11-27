package com.cnife.listenreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.cnife.listenreader.ui.theme.Hairline
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.LrCardShape
import com.cnife.listenreader.ui.theme.Meta
import com.cnife.listenreader.ui.theme.MetaOnCanvas
import com.cnife.listenreader.ui.theme.OnNight
import com.cnife.listenreader.ui.theme.StickerPurple
import com.cnife.listenreader.ui.theme.StickerPurpleInk
import com.cnife.listenreader.ui.theme.StickerSky
import com.cnife.listenreader.ui.theme.Surface

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
    val listState = rememberLazyListState()

    LaunchedEffect(searchExpanded) {
        if (searchExpanded) listState.scrollToItem(0)
    }

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
                listState = listState,
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
    listState: LazyListState,
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

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "header") {
            Column {
                if (isLoading) {
                    IndeterminateProgressLine(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    Text(
                        text = "正在加载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MetaOnCanvas,
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
                }
            }
        }

        itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
            LibraryCardRow(
                first = index == 0,
                last = index == state.items.lastIndex,
            ) {
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

@Composable
private fun LibraryCardRow(
    first: Boolean,
    last: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = when {
        first && last -> LrCardShape
        first -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        last -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        else -> RectangleShape
    }
    val hairline = Hairline
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = if (first) 8.dp else 0.dp)
            .clip(shape)
            .background(Surface)
            .drawBehind {
                drawCardOutline(first, last, 12.dp.toPx(), hairline)
            },
        content = content,
    )
}

private fun DrawScope.drawCardOutline(
    first: Boolean,
    last: Boolean,
    radius: Float,
    color: Color,
) {
    val stroke = 1.dp.toPx()
    val half = stroke / 2
    val width = size.width
    val height = size.height
    if (first && last) {
        drawRoundRect(
            color = color,
            topLeft = Offset(half, half),
            size = Size(width - stroke, height - stroke),
            cornerRadius = CornerRadius(radius),
            style = Stroke(stroke),
        )
        return
    }
    if (first) {
        drawLine(color, Offset(radius, half), Offset(width - radius, half), stroke)
        drawArc(color, 180f, 90f, false, Offset(half, half), Size(radius * 2, radius * 2), style = Stroke(stroke))
        drawArc(color, 270f, 90f, false, Offset(width - radius * 2 - half, half), Size(radius * 2, radius * 2), style = Stroke(stroke))
    }
    if (last) {
        drawArc(color, 90f, 90f, false, Offset(half, height - radius * 2 - half), Size(radius * 2, radius * 2), style = Stroke(stroke))
        drawArc(color, 0f, 90f, false, Offset(width - radius * 2 - half, height - radius * 2 - half), Size(radius * 2, radius * 2), style = Stroke(stroke))
        drawLine(color, Offset(radius, height - half), Offset(width - radius, height - half), stroke)
    }
    drawLine(color, Offset(half, if (first) radius else 0f), Offset(half, if (last) height - radius else height), stroke)
    drawLine(color, Offset(width - half, if (first) radius else 0f), Offset(width - half, if (last) height - radius else height), stroke)
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
            tint = if (isWeb) OnNight else StickerPurpleInk,
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
