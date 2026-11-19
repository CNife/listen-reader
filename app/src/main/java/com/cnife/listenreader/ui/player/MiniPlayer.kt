package com.cnife.listenreader.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.PlayerState

/** 底部常驻播放条：进度、上一句/播放/下一句、睡眠定时、倍速、跟随。 */
@Composable
fun MiniPlayer(
    state: PlayerState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeedClick: () -> Unit,
    onToggleFollow: () -> Unit,
    onSleepTimer: (Int?) -> Unit,
    onVoiceClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    currentText: String? = null,
) {
    val total = state.totalDurationMs
    val segmentStart = state.segments
        .take(state.currentIndex)
        .sumOf { segmentProgressMs(it) }
    val elapsed = segmentStart + state.positionInSegmentMs
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("mini-player"),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (title != null || currentText != null) {
                Column {
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier.testTag("mini-player-title"),
                        )
                    }
                    if (currentText != null) {
                        Text(
                            text = currentText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.testTag("mini-player-current"),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(elapsed), style = MaterialTheme.typography.labelSmall)
                Text(formatTime(total), style = MaterialTheme.typography.labelSmall)
            }
            LinearProgressIndicator(
                progress = {
                    if (total > 0) (elapsed.toFloat() / total).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("player-progress"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一句")
                }
                FilledTonalIconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一句")
                }
                SleepTimerButton(state = state, onSelectMinutes = onSleepTimer)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onSpeedClick, modifier = Modifier.testTag("player-speed")) {
                    Text("${formatSpeed(state.speed)}×")
                }
                TextButton(
                    onClick = onVoiceClick,
                    modifier = Modifier.testTag("player-voice"),
                ) {
                    Text(
                        text = Voices.DISPLAY[state.activeTrack?.voice] ?: "音色",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = onToggleFollow, modifier = Modifier.testTag("player-follow")) {
                    Text(if (state.follow) "跟随" else "自由")
                }
                if (state.isPreparing) {
                    Text(
                        text = "正在合成…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("player-preparing"),
                    )
                }
            }
        }
    }
}

private fun segmentProgressMs(segment: com.cnife.listenreader.service.PlayerSegment): Long =
    if (segment.durationMs > 0) segment.durationMs else 0L

internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

internal fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else "%.2f".format(speed).trimEnd('0')

internal val SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

internal fun nextSpeed(current: Float): Float {
    val index = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - current) < 0.001f }
    return SPEED_STEPS[(index + 1).mod(SPEED_STEPS.size)]
}
