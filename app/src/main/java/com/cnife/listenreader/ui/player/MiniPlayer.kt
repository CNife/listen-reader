package com.cnife.listenreader.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.ui.components.PlaybackProgress
import com.cnife.listenreader.ui.theme.CurrentBg
import com.cnife.listenreader.ui.theme.Hairline
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.LrPillShape
import com.cnife.listenreader.ui.theme.Meta
import com.cnife.listenreader.ui.theme.OnPrimary
import com.cnife.listenreader.ui.theme.Primary
import com.cnife.listenreader.ui.theme.Surface as CardSurface
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(CardSurface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .testTag("mini-player"),
    ) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatTime(elapsed), style = tabularTime(), color = Meta)
                    Text(formatTime(total), style = tabularTime(), color = Meta)
                }
                PlaybackProgress(
                    progress = progress,
                    modifier = Modifier.testTag("player-progress"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "上一句",
                            tint = Ink,
                        )
                    }
                    Spacer(Modifier.width(18.dp))
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(Primary, CircleShape)
                            .clickable(role = Role.Button, onClick = onPlayPause)
                            .testTag("player-hero-play"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            tint = OnPrimary,
                        )
                    }
                    Spacer(Modifier.width(18.dp))
                    IconButton(
                        onClick = onNext,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "下一句",
                            tint = Ink,
                        )
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayerPill(
                        text = "${formatSpeed(state.speed)}×",
                        onClick = onSpeedClick,
                        active = false,
                        testTag = "player-speed",
                    )
                    PlayerPill(
                        text = Voices.DISPLAY[state.activeTrack?.voice] ?: "音色",
                        onClick = onVoiceClick,
                        active = false,
                        testTag = "player-voice",
                    )
                    PlayerPill(
                        text = if (state.follow) "跟随朗读" else "自由浏览",
                        onClick = onToggleFollow,
                        active = state.follow,
                        testTag = "player-follow",
                    )
                    SleepTimerButton(state = state, onSelectMinutes = onSleepTimer)
                }
                if (state.isPreparing) {
                    Text(
                        text = "正在合成…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Meta,
                        modifier = Modifier.testTag("player-preparing"),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlayerPill(
    text: String,
    onClick: () -> Unit,
    active: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    textTag: String? = null,
) {
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .background(if (active) CurrentBg else CardSurface, LrPillShape)
            .then(
                if (active) {
                    Modifier
                } else {
                    Modifier.border(1.dp, Hairline, LrPillShape)
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) Primary else Ink,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.let { if (textTag != null) it.testTag(textTag) else it },
        )
    }
}

@Composable
private fun tabularTime(): TextStyle = MaterialTheme.typography.bodySmall.copy(
    fontSize = 12.5.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
    color = Meta,
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
