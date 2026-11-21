package com.cnife.listenreader.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.ui.components.ProgressLine
import com.cnife.listenreader.ui.components.RuleLine
import com.cnife.listenreader.ui.theme.Accent
import com.cnife.listenreader.ui.theme.CardSurface
import com.cnife.listenreader.ui.theme.Ink2
import com.cnife.listenreader.ui.theme.OnAccent
import java.util.Locale

@Composable
fun PlayerDock(
    state: PlayerState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeedClick: () -> Unit,
    onToggleFollow: () -> Unit,
    onSleepTimer: (Int?) -> Unit,
    onVoiceClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = state.totalDurationMs
    val segmentStart = state.segments
        .take(state.currentIndex)
        .sumOf { segmentProgressMs(it) }
    val elapsed = segmentStart + state.positionInSegmentMs
    val progress = if (total > 0) (elapsed.toFloat() / total).coerceIn(0f, 1f) else 0f

    Surface(color = CardSurface, modifier = modifier.fillMaxWidth().testTag("mini-player")) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            RuleLine()
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatTime(elapsed), style = tabularTime(), color = Ink2)
                    Text(formatTime(total), style = tabularTime(), color = Ink2)
                }
                ProgressLine(
                    progress = progress,
                    modifier = Modifier.testTag("player-progress"),
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onPrevious,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "上一句",
                                tint = Ink2,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(Accent, RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button, onClick = onPlayPause)
                                .testTag("player-hero-play"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (state.isPlaying) "暂停" else "播放",
                                tint = OnAccent,
                            )
                        }
                        IconButton(
                            onClick = onNext,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "下一句",
                                tint = Ink2,
                            )
                        }
                    }
                    Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                        SleepTimerButton(state = state, onSelectMinutes = onSleepTimer)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DockTextAction(
                        text = "${formatSpeed(state.speed)}×",
                        onClick = onSpeedClick,
                        testTag = "player-speed",
                    )
                    DockTextAction(
                        text = Voices.DISPLAY[state.activeTrack?.voice] ?: "音色",
                        onClick = onVoiceClick,
                        testTag = "player-voice",
                    )
                    DockTextAction(
                        text = if (state.follow) "跟随朗读" else "自由浏览",
                        onClick = onToggleFollow,
                        testTag = "player-follow",
                    )
                    if (state.isPreparing) {
                        Text(
                            text = "正在合成…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink2,
                            modifier = Modifier.testTag("player-preparing"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DockTextAction(
    text: String,
    onClick: () -> Unit,
    testTag: String,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Accent),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun tabularTime(): TextStyle = MaterialTheme.typography.labelMedium.copy(
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
)

private fun segmentProgressMs(segment: com.cnife.listenreader.service.PlayerSegment): Long =
    if (segment.durationMs > 0) segment.durationMs else 0L

internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

internal fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else "%.2f".format(Locale.US, speed).trimEnd('0')

internal val SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

internal fun nextSpeed(current: Float): Float {
    val index = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - current) < 0.001f }
    return SPEED_STEPS[(index + 1).mod(SPEED_STEPS.size)]
}
