package com.cnife.listenreader.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.service.PlayerSegment
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.ui.components.PageHeader
import com.cnife.listenreader.ui.components.StatusLine
import com.cnife.listenreader.ui.theme.Accent
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Ink2
import com.cnife.listenreader.ui.theme.Paper

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
        containerColor = Paper,
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
                    .background(Paper)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("没有正在播放的内容", style = MaterialTheme.typography.titleLarge, color = Ink)
                Text(
                    text = "从资料库选择一篇内容，或先保存一段文本。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink2,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Paper)
                .testTag("player-lyrics"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        ) {
            item {
                TitleBlock(
                    title = state.title,
                    sourceLabel = state.sourceLabel,
                    modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                )
            }
            if (notificationPermission.required && !notificationPermission.granted) {
                item {
                    NotificationPermissionHint(
                        state = notificationPermission,
                        modifier = Modifier.padding(bottom = 12.dp),
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
                        modifier = Modifier.testTag("player-no-cache"),
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
    title: String,
    sourceLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = sourceLabel.ifBlank { "内容" },
            style = MaterialTheme.typography.bodySmall,
            color = Ink2,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            modifier = Modifier
                .padding(top = 6.dp)
                .testTag("player-title"),
        )
    }
}

@Composable
private fun ErrorLine(
    errorMessage: String,
    errorKind: ErrorKind?,
    service: ReadingService,
    currentIndex: Int,
    onOpenSettings: () -> Unit,
) {
    StatusLine(
        title = "出错",
        description = errorMessage,
        actionLabel = if (errorKind == ErrorKind.NO_KEY) "去设置" else "重试这句",
        onAction = {
            if (errorKind == ErrorKind.NO_KEY) onOpenSettings() else service.retrySegment(currentIndex)
        },
        modifier = Modifier.testTag("player-error"),
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
        isPast -> Ink2
        else -> Ink
    }
    val fontWeight = when {
        isCurrent -> FontWeight.SemiBold
        isPast -> FontWeight.Normal
        else -> FontWeight.Normal
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player-segment-${segment.index}")
            .semantics { selected = isCurrent }
            .clickable(onClick = onClick)
            .padding(top = if (isCurrent) 14.dp else 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = segment.text,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = textColor,
                fontWeight = fontWeight,
            ),
            modifier = Modifier.weight(1f),
        )
        if (segment.status == ClipStatus.SYNTHESIZING && isCurrent) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(12.dp)
                    .testTag("player-segment-${segment.index}-synthesizing"),
                color = Ink2,
                strokeWidth = 1.5.dp,
            )
        }
        if (segment.status == ClipStatus.ERROR) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier
                    .height(40.dp)
                    .testTag("player-retry-${segment.index}"),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Accent,
                )
            }
        }
    }
}
