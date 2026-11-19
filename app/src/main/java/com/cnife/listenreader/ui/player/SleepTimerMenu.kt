package com.cnife.listenreader.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cnife.listenreader.service.PlayerState

val SLEEP_TIMER_OPTIONS = listOf(5, 15, 30, 60)

/** 底部播放器的睡眠定时入口：选择 5/15/30/60 分钟或关闭，并显示剩余时间。 */
@Composable
fun SleepTimerButton(
    state: PlayerState,
    onSelectMinutes: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("player-sleep-timer"),
        ) {
            Icon(
                imageVector = Icons.Filled.Timer,
                contentDescription = "睡眠定时",
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = sleepTimerLabel(state),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag("player-sleep-remaining"),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SLEEP_TIMER_OPTIONS.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text("$minutes 分钟") },
                    onClick = {
                        expanded = false
                        onSelectMinutes(minutes)
                    },
                    modifier = Modifier.testTag("sleep-option-$minutes"),
                )
            }
            if (state.sleepTimerMinutes != null) {
                DropdownMenuItem(
                    text = { Text("关闭定时") },
                    onClick = {
                        expanded = false
                        onSelectMinutes(null)
                    },
                    modifier = Modifier.testTag("sleep-cancel"),
                )
            }
        }
    }
}

internal fun sleepTimerLabel(state: PlayerState): String {
    val remainingMs = state.sleepTimerRemainingMs
    if (state.sleepTimerMinutes == null || remainingMs == null) return "定时"
    return "定时 ${formatTimerRemaining(remainingMs)}"
}

internal fun formatTimerRemaining(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
