package com.cnife.listenreader.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.ui.theme.Accent
import com.cnife.listenreader.ui.theme.CardSurface
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Rule

val SLEEP_TIMER_OPTIONS = listOf(5, 15, 30, 60)

/** 底部播放器的睡眠定时入口：文字动作，选择 5/15/30/60 分钟或关闭。 */
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
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = Accent),
        ) {
            Text(
                text = sleepTimerLabel(state),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.testTag("player-sleep-remaining"),
            )
        }
        if (expanded) {
            Popup(
                alignment = Alignment.BottomEnd,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    color = CardSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Rule),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .width(160.dp)
                        .testTag("sleep-timer-menu"),
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
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
            .height(48.dp)
            .testTag(testTag)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
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
