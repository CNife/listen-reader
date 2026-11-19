package com.cnife.listenreader.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.SettingsState

/**
 * 播放器音色选择：四个 Voice + 固定短句试听 + 当前选中。
 * 选中写入全局设置，并让服务为当前 Resource 新建/复用 AudioTrack。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(service: ReadingService, selectedVoice: String?, onDismiss: () -> Unit) {
    val settings by service.settings.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .testTag("voice-sheet"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Voices.ALL.forEach { voice ->
            VoiceSheetRow(
                label = Voices.DISPLAY[voice] ?: voice,
                voice = voice,
                selected = selectedVoice == voice,
                isAuditioning = state.isAuditioning && state.auditionVoice == voice,
                onSelect = onSelect,
                onPreview = onPreview,
            )
        }
        if (state.isAuditioning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
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
                modifier = Modifier.padding(top = 8.dp).testTag("voice-audition-error"),
            )
        }
    }
}

@Composable
private fun VoiceSheetRow(
    label: String,
    voice: String,
    selected: Boolean,
    isAuditioning: Boolean,
    onSelect: (String) -> Unit,
    onPreview: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice-option-$voice")
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(voice) })
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = if (selected) "$label（当前）" else label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onPreview(voice) },
            enabled = !isAuditioning,
            modifier = Modifier.testTag("voice-preview-$voice"),
        ) {
            Text(if (isAuditioning) "试听中…" else "试听")
        }
    }
}
