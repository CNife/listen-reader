package com.cnife.listenreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.SettingsState
import java.util.Locale

/** 设置页存储区：活动/旧 AudioTrack 占用空间与清理。 */
@Composable
fun StorageSection(service: ReadingService) {
    val state by service.settings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { service.refreshStorageUsage() }
    StorageContent(state = state, onClear = service::clearOldAudioTracks)
}

@Composable
internal fun StorageContent(state: SettingsState, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("storage-section"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("存储", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("当前音轨", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatBytes(state.storage.activeBytes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("storage-active-bytes"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("旧音轨", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatBytes(state.storage.oldTracksBytes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("storage-old-bytes"),
            )
        }
        Button(
            onClick = onClear,
            enabled = !state.isClearing,
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("storage-clear"),
        ) {
            Text(if (state.isClearing) "清理中…" else "清理旧音轨")
        }
        Text(
            text = "只删除非活动音轨（切换音色前合成的旧音频），当前正在使用的音轨会保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("storage-message"),
            )
        }
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
