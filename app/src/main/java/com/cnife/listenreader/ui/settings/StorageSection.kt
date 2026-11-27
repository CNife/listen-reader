package com.cnife.listenreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.ui.components.CardDivider
import com.cnife.listenreader.ui.components.LrCard
import com.cnife.listenreader.ui.components.QuietAction
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Meta
import java.util.Locale

@Composable
fun StorageSection(service: ReadingService) {
    val state by service.settings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { service.refreshStorageUsage() }
    StorageContent(state = state, onClear = service::clearOldAudioTracks)
}

@Composable
internal fun StorageContent(state: SettingsState, onClear: () -> Unit) {
    LrCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("storage-section"),
        padding = PaddingValues(20.dp),
    ) {
        SettingsSectionTitle("存储")
        Spacer(Modifier.height(8.dp))
        StorageRow(
            label = "当前音轨",
            value = formatBytes(state.storage.activeBytes),
            valueTag = "storage-active-bytes",
        )
        CardDivider()
        StorageRow(
            label = "旧音轨",
            value = formatBytes(state.storage.oldTracksBytes),
            valueTag = "storage-old-bytes",
        )
        QuietAction(
            text = if (state.isClearing) "清理中…" else "清理旧音轨",
            onClick = onClear,
            enabled = !state.isClearing,
            modifier = Modifier.padding(top = 4.dp),
            testTag = "storage-clear",
        )
        Text(
            text = "只删除非活动音轨（切换音色前合成的旧音频），当前正在使用的音轨会保留。",
            style = MaterialTheme.typography.bodySmall,
            color = Meta,
        )
        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Meta,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("storage-message"),
            )
        }
    }
}

@Composable
private fun StorageRow(label: String, value: String, valueTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
            color = Meta,
            modifier = Modifier.testTag(valueTag),
        )
    }
}

internal fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    return when {
        safe < 1024L -> "$safe B"
        safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
    }
}
