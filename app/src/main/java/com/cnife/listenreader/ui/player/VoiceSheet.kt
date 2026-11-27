package com.cnife.listenreader.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.VoiceCatalogEntry
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.ui.components.CardDivider
import com.cnife.listenreader.ui.components.PillAction
import com.cnife.listenreader.ui.components.QuietAction
import com.cnife.listenreader.ui.components.StatusLine
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.LrSheetShape
import com.cnife.listenreader.ui.theme.Meta
import com.cnife.listenreader.ui.theme.RuleStrong
import com.cnife.listenreader.ui.theme.Scrim
import com.cnife.listenreader.ui.theme.Selected
import com.cnife.listenreader.ui.theme.Surface as CardSurface

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
        shape = LrSheetShape,
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
            onDone = onDismiss,
        )
    }
}

@Composable
internal fun VoiceSheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .background(RuleStrong, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
internal fun VoiceSheetContent(
    selectedVoice: String,
    state: SettingsState,
    onSelect: (String) -> Unit,
    onPreview: (String) -> Unit,
    onDone: (() -> Unit)? = null,
) {
    val entries = Voices.entriesForModel(state.ttsModel)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 16.dp)
            .testTag("voice-sheet"),
    ) {
        Text("音色", style = MaterialTheme.typography.headlineSmall, color = Ink)
        Text(
            text = "当前模型：${state.ttsModel}",
            style = MaterialTheme.typography.bodySmall,
            color = Meta,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        state.auditionError?.let { error ->
            StatusLine(
                title = "试听失败",
                description = error,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .testTag("voice-audition-error"),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
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
                    CardDivider(startIndent = 30.dp)
                }
            }
        }
        if (onDone != null) {
            Spacer(Modifier.height(12.dp))
            PillAction(
                text = "完成",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                testTag = "voice-done",
            )
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
            .heightIn(min = 64.dp)
            .testTag("voice-option-${entry.id}")
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(entry.id) })
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(1.5.dp, if (selected) Selected else RuleStrong, CircleShape)
                .padding(3.dp)
                .background(if (selected) Selected else Color.Transparent, CircleShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (entry.subtitle.isNotBlank()) {
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Meta,
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
                    color = Meta,
                    strokeWidth = 1.5.dp,
                )
                Text(
                    text = "合成中",
                    style = MaterialTheme.typography.bodySmall,
                    color = Meta,
                )
            }
        } else {
            QuietAction(
                text = "试听",
                onClick = { onPreview(entry.id) },
                testTag = "voice-preview-${entry.id}",
            )
        }
    }
}
