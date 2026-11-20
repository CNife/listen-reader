package com.cnife.listenreader.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.PlayerSegment
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.service.ReadingService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    service: ReadingService,
    resourceId: String,
    onBack: () -> Unit,
    onOpenReader: (String) -> Unit,
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
        topBar = {
            TopAppBar(
                title = { Text(if (state.resourceId == null) "播放器" else "正在朗读") },
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
                    Text("没有正在播放的内容", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "从资料库选择一篇内容，或先保存一段文本。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("player-lyrics"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { HeroCard(state, service, onOpenReader) }
                if (state.error != null) {
                    item { ErrorBanner(state, service, onOpenSettings) }
                }
                if (!state.segments.any { it.status == ClipStatus.READY }) {
                    item { NoKeyHint(state, onOpenSettings) }
                }
                if (notificationPermission.required && !notificationPermission.granted) {
                    item {
                        NotificationPermissionHint(
                            state = notificationPermission,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("歌词", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "点击任意一句跳转",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.segments, key = { it.index }) { segment ->
                    LyricRow(
                        segment = segment,
                        isCurrent = segment.index == state.currentIndex,
                        isPast = segment.index < state.currentIndex,
                        onClick = { service.seekToSegment(segment.index) },
                        onRetry = { service.retrySegment(segment.index) },
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
                onSleepTimer = service::setSleepTimer,
                onVoiceClick = { showVoiceSheet = true },
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
private fun HeroCard(
    state: PlayerState,
    service: ReadingService,
    onOpenReader: (String) -> Unit,
) {
    val cached = state.segments.count { it.status == ClipStatus.READY }
    val voiceLabel = Voices.DISPLAY[state.activeTrack?.voice] ?: state.activeTrack?.voice.orEmpty()
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player-hero"),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.sourceLabel.ifBlank { "内容" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "已缓存 $cached/${state.segments.size} 句",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag("player-title"),
            )
            Text(
                text = buildString {
                    append("${state.segments.size} 句 · ${formatTime(state.totalDurationMs)}")
                    if (voiceLabel.isNotBlank()) append(" · $voiceLabel")
                    append(" · ${formatSpeed(state.speed)}×")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag("player-meta"),
            )
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = service::togglePlay, modifier = Modifier.testTag("player-hero-play")) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.isPlaying) "暂停" else "播放")
                }
                OutlinedButton(
                    onClick = { state.resourceId?.let(onOpenReader) },
                    modifier = Modifier.testTag("player-open-reader"),
                ) {
                    Text("阅读模式")
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    state: PlayerState,
    service: ReadingService,
    onOpenSettings: () -> Unit,
) {
    val error = state.error ?: return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player-error"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error.kind == ErrorKind.NO_KEY) {
                    TextButton(onClick = onOpenSettings) { Text("去设置") }
                } else {
                    TextButton(onClick = { service.retrySegment(state.currentIndex) }) {
                        Text("重试这句")
                    }
                }
            }
        }
    }
}

@Composable
private fun NoKeyHint(state: PlayerState, onOpenSettings: () -> Unit) {
    if (state.isPreparing) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player-no-cache"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "还没有缓存的音频。播放时会按设置里的 TTS 配置逐句合成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text("设置") }
        }
    }
}

@Composable
private fun LyricRow(
    segment: PlayerSegment,
    isCurrent: Boolean,
    isPast: Boolean,
    onClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val background = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer
        segment.status == ClipStatus.SYNTHESIZING -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val textColor = when {
        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
        isPast -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player-segment-${segment.index}")
            .semantics { selected = isCurrent }
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else background,
            modifier = Modifier.size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "${segment.index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Text(
            text = segment.text,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        SegmentStatusIcon(segment, isCurrent, onRetry)
    }
}

@Composable
private fun SegmentStatusIcon(
    segment: PlayerSegment,
    isCurrent: Boolean,
    onRetry: () -> Unit,
) {
    when (segment.status) {
        ClipStatus.SYNTHESIZING -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        ClipStatus.READY -> Icon(
            Icons.Filled.Check,
            contentDescription = "已缓存",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        ClipStatus.ERROR -> Icon(
            Icons.Filled.Refresh,
            contentDescription = "重试这句",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .size(20.dp)
                .testTag("player-retry-${segment.index}")
                .clickable(onClick = onRetry),
        )
        ClipStatus.PENDING -> Icon(
            imageVector = if (isCurrent) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow,
            contentDescription = "待合成",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}
