package com.cnife.listenreader.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as PopupSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.ui.theme.Hairline
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.LrCardShape
import com.cnife.listenreader.ui.theme.Surface as CardSurface

val SLEEP_TIMER_OPTIONS = listOf(5, 15, 30, 60)

@Composable
fun SleepTimerButton(
    state: PlayerState,
    onSelectMinutes: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = state.sleepTimerMinutes != null
    Box(modifier = modifier) {
        PlayerPill(
            text = sleepTimerLabel(state),
            onClick = { expanded = true },
            active = active,
            testTag = "player-sleep-timer",
            textTag = "player-sleep-remaining",
        )
        if (expanded) {
            Popup(
                alignment = Alignment.BottomEnd,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                PopupSurface(
                    color = CardSurface,
                    shape = LrCardShape,
                    border = BorderStroke(1.dp, Hairline),
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .width(168.dp)
                        .testTag("sleep-timer-menu"),
                ) {
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        SLEEP_TIMER_OPTIONS.forEach { minutes ->
                            SleepTimerRow(
                                text = "$minutes 分钟",
                                onClick = {
                                    expanded = false
                                    onSelectMinutes(minutes)
                                },
                                testTag = "sleep-option-$minutes",
                            )
                        }
                        if (state.sleepTimerMinutes != null) {
                            SleepTimerRow(
                                text = "关闭",
                                onClick = {
                                    expanded = false
                                    onSelectMinutes(null)
                                },
                                testTag = "sleep-cancel",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepTimerRow(
    text: String,
    onClick: () -> Unit,
    testTag: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(testTag)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink,
        )
    }
}

internal fun sleepTimerLabel(state: PlayerState): String {
    val remainingMs = state.sleepTimerRemainingMs
    if (state.sleepTimerMinutes == null || remainingMs == null) return "睡眠定时"
    return "睡眠定时 ${formatTimerRemaining(remainingMs)}"
}

internal fun formatTimerRemaining(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
