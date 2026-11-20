package com.cnife.listenreader.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.VoiceCatalogEntry
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.ui.components.StatusLine
import com.cnife.listenreader.ui.theme.Accent
import com.cnife.listenreader.ui.theme.CardSurface
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Ink2
import com.cnife.listenreader.ui.theme.Rule
import com.cnife.listenreader.ui.theme.Scrim

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
        containerColor = CardSurface,
        contentColor = Ink,
        scrimColor = Scrim,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        dragHandle = { VoiceSheetHandle() },
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
private fun VoiceSheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Rule),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .width(32.dp)
                .height(4.dp)
                .background(Rule, RoundedCornerShape(2.dp)),
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
            .padding(top = 8.dp, bottom = 16.dp)
            .testTag("voice-sheet"),
    ) {
        Text("音色", style = MaterialTheme.typography.titleLarge, color = Ink)
        Text(
            text = "当前模型：${state.ttsModel}",
            style = MaterialTheme.typography.bodySmall,
            color = Ink2,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        state.auditionError?.let { error ->
            StatusLine(
                title = "试听失败",
                description = error,
                modifier = Modifier.testTag("voice-audition-error"),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .testTag("voice-sheet-list"),
        ) {
            entries.forEachIndexed { index, entry ->
                VoiceSheetRow(
                    entry = entry,
                    selected = selectedVoice == entry.id,
                    isAuditioning = state.isAuditioning && state.auditionVoice == entry.id,
                    onSelect = onSelect,
                    onPreview = onPreview,
                )
                if (index != entries.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Rule),
                    )
                }
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
            .heightIn(min = 56.dp)
            .testTag("voice-option-${entry.id}")
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(entry.id) })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = Ink,
                )
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Accent),
                    )
                }
            }
            if (entry.subtitle.isNotBlank()) {
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                )
            }
        }
        if (isAuditioning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(12.dp)
                        .testTag("voice-audition-progress"),
                    color = Ink2,
                    strokeWidth = 1.5.dp,
                )
                Text(
                    text = "合成中",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                )
            }
        } else {
            TextButton(
                onClick = { onPreview(entry.id) },
                modifier = Modifier.testTag("voice-preview-${entry.id}"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Accent),
            ) {
                Text(
                    text = "试听",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}
