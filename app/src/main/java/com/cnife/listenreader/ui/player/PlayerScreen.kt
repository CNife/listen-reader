package com.cnife.listenreader.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.service.PlayerSegment
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.ui.components.CardDivider
import com.cnife.listenreader.ui.components.LrCard
import com.cnife.listenreader.ui.components.PageHeader
import com.cnife.listenreader.ui.components.StatusDot
import com.cnife.listenreader.ui.components.StatusLine
import com.cnife.listenreader.ui.theme.Canvas
import com.cnife.listenreader.ui.theme.CurrentBar
import com.cnife.listenreader.ui.theme.CurrentBg
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Link
import com.cnife.listenreader.ui.theme.Meta
import com.cnife.listenreader.ui.theme.Muted
import com.cnife.listenreader.ui.theme.ReadingTextStyle
import com.cnife.listenreader.ui.theme.StickerSky

@Composable
fun PlayerScreen(
    service: ReadingService,
    resourceId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by service.player.collectAsStateWithLifecycle()
    val notificationPermission = rememberNotificationPermission()
    var showVoiceSheet by remember { mutableStateOf(false) }

    LaunchedEffect(resourceId) {
        if (resourceId.isNotBlank() && state.resourceId != resourceId) {
            service.openResource(resourceId)
        }
    }

    Scaffold(
        containerColor = Canvas,
        topBar = {
            PageHeader(title = "正在朗读", onBack = onBack)
        },
        bottomBar = {
            if (state.resourceId != null) {
                PlayerDock(
                    state = state,
                    onPlayPause = service::togglePlay,
                    onPrevious = service::previousSegment,
                    onNext = service::nextSegment,
                    onSpeedClick = { service.setSpeed(nextSpeed(state.speed)) },
                    onToggleFollow = service::toggleFollow,
                    onSleepTimer = service::setSleepTimer,
                    onVoiceClick = { showVoiceSheet = true },
                )
            }
        },
    ) { padding ->
        if (state.resourceId == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Canvas)
                    .padding(20.dp),
            ) {
                LrCard(
                    modifier = Modifier.fillMaxWidth(),
                    padding = PaddingValues(24.dp),
                ) {
                    Text(
                        text = "没有正在播放的内容",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Ink,
                    )
                    Text(
                        text = "从资料库选择一篇内容，或先保存一段文本。",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Canvas)
                .testTag("player-lyrics"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        ) {
            item {
                TitleBlock(
                    state = state,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
                CardDivider()
            }
            if (notificationPermission.required && !notificationPermission.granted) {
                item {
                    NotificationPermissionHint(
                        state = notificationPermission,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            if (state.error != null) {
                item {
                    ErrorLine(
                        errorMessage = state.error?.message.orEmpty(),
                        errorKind = state.error?.kind,
                        service = service,
                        currentIndex = state.currentIndex,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            if (!state.isPreparing && !state.segments.any { it.status == ClipStatus.READY }) {
                item {
                    StatusLine(
                        title = "还没有音频",
                        description = "播放时会合成音频，并缓存在本机。",
                        actionLabel = "设置",
                        onAction = onOpenSettings,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .testTag("player-no-cache"),
                    )
                }
            }
            items(state.segments, key = { it.index }) { segment ->
                SegmentRow(
                    segment = segment,
                    isCurrent = segment.index == state.currentIndex,
                    isPast = segment.index < state.currentIndex,
                    onClick = { service.seekToSegment(segment.index) },
                    onRetry = { service.retrySegment(segment.index) },
                )
            }
        }
    }

    if (showVoiceSheet) {
        VoiceSheet(
            service = service,
            selectedVoice = state.activeTrack?.voice,
            onDismiss = { showVoiceSheet = false },
        )
    }
}

@Composable
private fun TitleBlock(
    state: PlayerState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = StickerSky, size = 6.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.sourceLabel.ifBlank { "内容" },
                style = MaterialTheme.typography.labelSmall,
                color = Meta,
            )
        }
        Text(
            text = state.title,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("player-title"),
            style = MaterialTheme.typography.displayMedium,
            color = Ink,
        )
        Text(
            text = playerMeta(state),
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Meta,
        )
    }
}

private fun playerMeta(state: PlayerState): String {
    val segments = state.segments.size
    val minutes = ((state.totalDurationMs + 59_999L) / 60_000L).toInt()
    val percent = if (segments > 0) {
        ((state.currentIndex + 1) * 100 / segments).coerceIn(0, 100)
    } else {
        0
    }
    return buildString {
        if (segments > 0) append("$segments 段")
        if (minutes > 0) {
            if (isNotEmpty()) append(" · ")
            append("约 $minutes 分钟")
        }
        if (segments > 0) {
            if (isNotEmpty()) append(" · ")
            append("已听 $percent%")
        }
    }
}

@Composable
private fun ErrorLine(
    errorMessage: String,
    errorKind: ErrorKind?,
    service: ReadingService,
    currentIndex: Int,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatusLine(
        title = "出错",
        description = errorMessage,
        actionLabel = if (errorKind == ErrorKind.NO_KEY) "去设置" else "重试这句",
        onAction = {
            if (errorKind == ErrorKind.NO_KEY) onOpenSettings() else service.retrySegment(currentIndex)
        },
        modifier = modifier.testTag("player-error"),
    )
}

@Composable
private fun SegmentRow(
    segment: PlayerSegment,
    isCurrent: Boolean,
    isPast: Boolean,
    onClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val textColor = when {
        isCurrent -> Ink
        isPast -> Muted
        else -> Ink
    }
    val barColor = CurrentBar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (isCurrent) 4.dp else 1.dp)
            .testTag("player-segment-${segment.index}")
            .semantics { selected = isCurrent }
            .clip(RoundedCornerShape(4.dp))
            .background(if (isCurrent) CurrentBg else Color.Transparent)
            .drawBehind {
                if (isCurrent) {
                    drawRect(color = barColor, size = Size(3.dp.toPx(), size.height))
                }
            }
            .clickable(onClick = onClick)
            .padding(
                start = if (isCurrent) 15.dp else 0.dp,
                end = if (isCurrent) 12.dp else 0.dp,
                top = if (isCurrent) 10.dp else 6.dp,
                bottom = if (isCurrent) 10.dp else 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = segment.text,
            style = ReadingTextStyle.copy(
                color = textColor,
                fontWeight = FontWeight.Normal,
            ),
            modifier = Modifier.weight(1f),
        )
        if (segment.status == ClipStatus.SYNTHESIZING && isCurrent) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(12.dp)
                    .testTag("player-segment-${segment.index}-synthesizing"),
                color = Meta,
                strokeWidth = 1.5.dp,
            )
        }
        if (segment.status == ClipStatus.ERROR) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .testTag("player-retry-${segment.index}"),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelMedium,
                    color = Link,
                )
            }
        }
    }
}
