package com.cnife.listenreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.ui.components.ProgressLine
import com.cnife.listenreader.ui.theme.Accent
import com.cnife.listenreader.ui.theme.CardSurface
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Ink2
import com.cnife.listenreader.ui.theme.OnAccent
import com.cnife.listenreader.ui.theme.Rule

/**
 * 资料库唯一的一张卡：标题、友好来源 · 进度百分比、2dp 进度线与播放键。
 * 点击卡片或播放键都回到该 Resource 的 Segment 与位置。
 */
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
            .border(1.dp, Rule, RoundedCornerShape(12.dp))
            .background(CardSurface, RoundedCornerShape(12.dp))
            .testTag("continue-card")
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${item.sourceLabel} · $percent%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Accent)
                    .testTag("continue-play")
                    .clickable(role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = OnAccent,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ProgressLine(
            progress = percent / 100f,
            modifier = Modifier.testTag("continue-progress"),
        )
    }
}

/** 列表与继续卡共用的进度：只对听过的 Resource 计算，0..100，0 表示尚未开始。 */
internal fun libraryProgressPercent(item: LibraryItem): Int {
    if (item.lastOpenedAt <= 0 || item.segmentCount <= 0) return 0
    val percent = (item.currentSegmentIndex + 1) * 100 / item.segmentCount
    return percent.coerceIn(0, 100)
}

/** 友好相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / N 周前。 */
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
