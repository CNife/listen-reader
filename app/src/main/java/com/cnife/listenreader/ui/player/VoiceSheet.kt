package com.cnife.listenreader.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.VoiceCatalogEntry
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.SettingsState

/**
 * 音色选择抽屉：展示当前模型支持的全部上游音色 + 固定短句试听 + 当前选中。
 * 选中写入全局设置，并让服务为当前 Resource 新建/复用 AudioTrack。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(service: ReadingService, selectedVoice: String?, onDismiss: () -> Unit) {
    val settings by service.settings.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        VoiceSheetContent(
            selectedVoice = selectedVoice ?: settings.voice,
            state = settings,
            onSelect = { voice ->
                service.setVoice(voice)
                onDismiss()
            },
            onPreview = service::previewVoice,
        )
    }
}

@Composable
internal fun VoiceSheetContent(
    selectedVoice: String,
    state: SettingsState,
    onSelect: (String) -> Unit,
    onPreview: (String) -> Unit,
) {
    val entries = Voices.entriesForModel(state.ttsModel)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp)
            .testTag("voice-sheet"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("切换音色", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "当前模型：${state.ttsModel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "切换会为当前文章新建音轨并重新合成当前句，旧音轨会保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        if (state.isAuditioning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(2.dp).testTag("voice-audition-progress"),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "正在合成试听音频…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.auditionError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp).testTag("voice-audition-error"),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .testTag("voice-sheet-list"),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            entries.forEach { entry ->
                VoiceSheetRow(
                    entry = entry,
                    selected = selectedVoice == entry.id,
                    isAuditioning = state.isAuditioning && state.auditionVoice == entry.id,
                    onSelect = onSelect,
                    onPreview = onPreview,
                )
            }
        }
    }
}

@Composable
private fun VoiceSheetRow(
    entry: VoiceCatalogEntry,
    selected: Boolean,
    isAuditioning: Boolean,
    onSelect: (String) -> Unit,
    onPreview: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice-option-${entry.id}")
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(entry.id) }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (selected) "${entry.name}（当前）" else entry.name,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (entry.subtitle.isNotBlank()) {
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(
            onClick = { onPreview(entry.id) },
            enabled = !isAuditioning,
            modifier = Modifier.testTag("voice-preview-${entry.id}"),
        ) {
            Text(if (isAuditioning) "试听中…" else "试听")
        }
    }
}
