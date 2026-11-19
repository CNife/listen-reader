package com.cnife.listenreader.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.service.PlayerSegment
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.service.ReadingService

private const val TAB_SCRIPT = 0
private const val TAB_SNAPSHOT = 1

/**
 * B 阅读模式：朗读文稿 / 原文快照切换，底部播放条常驻。
 * 播放、高亮与跳转直接复用 [ReadingService] 的播放器状态，不引入第二套语义。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    service: ReadingService,
    resourceId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by service.player.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(TAB_SCRIPT) }
    var snapshotFallbackVisible by remember { mutableStateOf(false) }
    var showVoiceSheet by remember { mutableStateOf(false) }

    LaunchedEffect(resourceId) {
        if (resourceId.isNotBlank() && state.resourceId != resourceId) {
            service.openResource(resourceId)
        }
    }

    // 快照在打开 Resource 时异步读入；若用户已在快照页而快照不可用，退回朗读文稿。
    LaunchedEffect(tab, state.snapshotText) {
        if (tab == TAB_SNAPSHOT && state.snapshotText == null) {
            tab = TAB_SCRIPT
            snapshotFallbackVisible = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读模式") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.resourceId == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("没有正在朗读的内容", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "从资料库选择一篇内容，或先保存一段文本。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            ReaderModeSwitch(
                selectedTab = tab,
                onSelect = { requested ->
                    if (requested == TAB_SNAPSHOT && state.snapshotText == null) {
                        snapshotFallbackVisible = true
                    } else {
                        snapshotFallbackVisible = false
                        tab = requested
                    }
                },
            )

            if (snapshotFallbackVisible) {
                SnapshotFallbackBanner(state.snapshotError)
            }

            Box(modifier = Modifier.weight(1f)) {
                if (tab == TAB_SNAPSHOT) {
                    val snapshot = state.snapshotText
                    if (snapshot != null) {
                        SnapshotContent(snapshot)
                    }
                } else {
                    ScriptContent(
                        state = state,
                        onSelectSegment = service::seekToSegment,
                    )
                }
            }

            MiniPlayer(
                state = state,
                onPlayPause = service::togglePlay,
                onPrevious = service::previousSegment,
                onNext = service::nextSegment,
                onSpeedClick = { service.setSpeed(nextSpeed(state.speed)) },
                onToggleFollow = service::toggleFollow,
                onVoiceClick = { showVoiceSheet = true },
                title = state.title,
                currentText = state.segments.getOrNull(state.currentIndex)?.text,
            )
        }

        if (showVoiceSheet) {
            VoiceSheet(
                service = service,
                selectedVoice = state.activeTrack?.voice,
                onDismiss = { showVoiceSheet = false },
            )
        }
    }
}

@Composable
private fun ReaderModeSwitch(selectedTab: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("reader-mode-switch"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedTab == TAB_SCRIPT,
            onClick = { onSelect(TAB_SCRIPT) },
            label = { Text("朗读文稿") },
            modifier = Modifier
                .weight(1f)
                .testTag("reader-tab-script"),
        )
        FilterChip(
            selected = selectedTab == TAB_SNAPSHOT,
            onClick = { onSelect(TAB_SNAPSHOT) },
            label = { Text("原文快照") },
            modifier = Modifier
                .weight(1f)
                .testTag("reader-tab-snapshot"),
        )
    }
}

@Composable
private fun SnapshotFallbackBanner(reason: String?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = "${reason ?: "原文快照不可用"}，已显示朗读文稿",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("reader-snapshot-fallback"),
        )
    }
}

@Composable
private fun ScriptContent(
    state: PlayerState,
    onSelectSegment: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentIndex, state.follow) {
        if (state.follow && state.segments.isNotEmpty()) {
            listState.scrollToItem(state.currentIndex.coerceIn(0, state.segments.lastIndex))
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("reader-script"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(state.segments, key = { it.index }) { segment ->
            ScriptBlock(
                segment = segment,
                isCurrent = segment.index == state.currentIndex,
                isPast = segment.index < state.currentIndex,
                onClick = { onSelectSegment(segment.index) },
            )
        }
    }
}

@Composable
private fun ScriptBlock(
    segment: PlayerSegment,
    isCurrent: Boolean,
    isPast: Boolean,
    onClick: () -> Unit,
) {
    val background = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val textColor = when {
        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
        isPast -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val base = Modifier
        .fillMaxWidth()
        .testTag("reader-segment-${segment.index}")
        .semantics { selected = isCurrent }
        .clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp)

    when (segment.kind) {
        SegmentKind.HEADING -> Text(
            text = segment.text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = base.background(background, RoundedCornerShape(8.dp)),
        )
        SegmentKind.QUOTE -> Row(
            modifier = base
                .height(IntrinsicSize.Min)
                .background(background, RoundedCornerShape(8.dp)),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outline),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = segment.text,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                color = textColor,
            )
        }
        SegmentKind.PARAGRAPH -> Text(
            text = segment.text,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
            modifier = base.background(background, RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
private fun SnapshotContent(markdown: String) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("reader-snapshot"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(blocks) { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = block.text,
                    style = if (block.level <= 2) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.Bold,
                )
                is MarkdownBlock.Paragraph -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
                is MarkdownBlock.Quote -> Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .height(IntrinsicSize.Min)
                        .testTag("reader-snapshot-quote"),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is MarkdownBlock.ListItem -> Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.testTag("reader-snapshot-item"),
                ) {
                    Text("•", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(8.dp))
                    Text(block.text, style = MaterialTheme.typography.bodyLarge)
                }
                is MarkdownBlock.Code -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
    }
}

/** 轻量 Markdown 块：只覆盖快照里会出现的标题/段落/引用/列表/代码。 */
internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class ListItem(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
}

private val orderedItem = Regex("""^\d+[.)]\s+""")
private val headingLine = Regex("""^#{1,6}\s+.*""")

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    val quote = StringBuilder()
    val code = StringBuilder()
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.toString())
            paragraph.clear()
        }
    }

    fun flushQuote() {
        if (quote.isNotEmpty()) {
            blocks += MarkdownBlock.Quote(quote.toString())
            quote.clear()
        }
    }

    for (rawLine in markdown.replace("\r\n", "\n").lines()) {
        val line = rawLine.trim()
        if (line.startsWith("```")) {
            flushParagraph()
            flushQuote()
            if (inCode) {
                blocks += MarkdownBlock.Code(code.toString().trimEnd())
                code.clear()
            }
            inCode = !inCode
            continue
        }
        if (inCode) {
            code.appendLine(rawLine)
            continue
        }
        val orderedMatch = orderedItem.find(line)?.takeIf { it.range.first == 0 }
        when {
            line.isEmpty() -> {
                flushParagraph()
                flushQuote()
            }
            line.startsWith(">") -> {
                flushParagraph()
                if (quote.isNotEmpty()) quote.append('\n')
                quote.append(line.removePrefix(">").trim())
            }
            line.matches(headingLine) -> {
                flushParagraph()
                flushQuote()
                val level = line.takeWhile { it == '#' }.length
                blocks += MarkdownBlock.Heading(level, line.drop(level).trim())
            }
            orderedMatch != null -> {
                flushParagraph()
                flushQuote()
                blocks += MarkdownBlock.ListItem(line.removeRange(orderedMatch.range).trim())
            }
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> {
                flushParagraph()
                flushQuote()
                blocks += MarkdownBlock.ListItem(line.drop(2).trim())
            }
            else -> {
                flushQuote()
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
    }
    if (inCode && code.isNotEmpty()) {
        blocks += MarkdownBlock.Code(code.toString().trimEnd())
    }
    flushParagraph()
    flushQuote()
    return blocks
}
