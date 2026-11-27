package com.cnife.listenreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.ui.components.ProgressLine
import com.cnife.listenreader.ui.theme.LrIslandShape
import com.cnife.listenreader.ui.theme.Night
import com.cnife.listenreader.ui.theme.NightEyebrow
import com.cnife.listenreader.ui.theme.NightMeta
import com.cnife.listenreader.ui.theme.NightTrack
import com.cnife.listenreader.ui.theme.OnNight
import com.cnife.listenreader.ui.theme.StickerSky

@Composable
fun ContinueCard(
    item: LibraryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val percent = libraryProgressPercent(item)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(LrIslandShape)
            .background(Night)
            .testTag("continue-card")
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Text(
            text = "继续收听",
            style = MaterialTheme.typography.labelSmall,
            color = NightEyebrow,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = OnNight,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${item.sourceLabel} · $percent%",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightMeta,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(OnNight)
                    .testTag("continue-play")
                    .clickable(role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = Night,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        ProgressLine(
            progress = percent / 100f,
            modifier = Modifier.testTag("continue-progress"),
            trackColor = NightTrack,
            fillColor = StickerSky,
        )
    }
}

internal fun libraryProgressPercent(item: LibraryItem): Int {
    if (item.lastOpenedAt <= 0 || item.segmentCount <= 0) return 0
    val percent = (item.currentSegmentIndex + 1) * 100 / item.segmentCount
    return percent.coerceIn(0, 100)
}

internal fun formatRelativeTime(createdAt: Long, now: Long = System.currentTimeMillis()): String {
    if (createdAt <= 0) return "刚刚"
    val elapsedMs = (now - createdAt).coerceAtLeast(0)
    val minutes = elapsedMs / 60_000
    val hours = elapsedMs / 3_600_000
    val days = elapsedMs / 86_400_000
    return when {
        minutes < 1 -> "刚刚"
        hours < 1 -> "$minutes 分钟前"
        days < 1 -> "$hours 小时前"
        days < 7 -> "$days 天前"
        else -> "${days / 7} 周前"
    }
}
