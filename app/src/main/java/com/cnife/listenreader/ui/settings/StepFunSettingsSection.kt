package com.cnife.listenreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.service.TestState
import com.cnife.listenreader.ui.player.VoiceSheetContent

/** 设置页 TTS 区：StepFun / 千问官方 Key、朗读模型、音色抽屉（支持全部上游音色与试听）、测试连接。 */
@Composable
fun StepFunSettingsSection(service: ReadingService) {
    val state by service.settings.collectAsStateWithLifecycle()
    StepFunSettingsContent(
        state = state,
        onSaveKey = service::saveStepFunKey,
        onModelChange = service::setModel,
        onVoiceChange = service::setVoice,
        onTest = service::testStepFun,
        onSaveQwenKey = service::saveQwenKey,
        onTestQwen = service::testQwen,
        onPreviewVoice = service::previewVoice,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StepFunSettingsContent(
    state: SettingsState,
    onSaveKey: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onVoiceChange: (String) -> Unit,
    onTest: () -> Unit,
    onSaveQwenKey: (String) -> Unit = {},
    onTestQwen: () -> Unit = {},
    onPreviewVoice: (String) -> Unit = {},
) {
    var stepFunKeyInput by remember { mutableStateOf("") }
    var qwenKeyInput by remember { mutableStateOf("") }
    var showVoiceSheet by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("StepFun 朗读", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (state.stepFunKeyConfigured) "Key 已加密保存" else "尚未配置 Key",
            style = MaterialTheme.typography.bodySmall,
            color = if (state.stepFunKeyConfigured) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.testTag("stepfun-key-status"),
        )
        OutlinedTextField(
            value = stepFunKeyInput,
            onValueChange = { stepFunKeyInput = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("stepfun-key"),
            label = { Text("StepFun API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSaveKey(stepFunKeyInput)
                    stepFunKeyInput = ""
                },
                modifier = Modifier.testTag("stepfun-save-key"),
            ) {
                Text("保存 Key")
            }
            Button(
                onClick = onTest,
                enabled = state.stepFunTest !is TestState.Running,
                modifier = Modifier.testTag("stepfun-test"),
            ) {
                Text(if (state.stepFunTest is TestState.Running) "测试中…" else "测试连接")
            }
        }
        TestStatusText(state.stepFunTest, testTag = "stepfun-test-status")

        Text(
            text = "千问朗读（官方端点）",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = if (state.qwenKeyConfigured) "Key 已加密保存" else "尚未配置 Key",
            style = MaterialTheme.typography.bodySmall,
            color = if (state.qwenKeyConfigured) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.testTag("qwen-key-status"),
        )
        OutlinedTextField(
            value = qwenKeyInput,
            onValueChange = { qwenKeyInput = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("qwen-key"),
            label = { Text("千问 API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSaveQwenKey(qwenKeyInput)
                    qwenKeyInput = ""
                },
                modifier = Modifier.testTag("qwen-save-key"),
            ) {
                Text("保存千问 Key")
            }
            Button(
                onClick = onTestQwen,
                enabled = state.qwenTest !is TestState.Running,
                modifier = Modifier.testTag("qwen-test"),
            ) {
                Text(if (state.qwenTest is TestState.Running) "测试中…" else "测试千问连接")
            }
        }
        TestStatusText(state.qwenTest, testTag = "qwen-test-status")

        Text(
            text = "朗读模型",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        ModelOption(
            label = "step-tts-mini（阶跃 · 默认）",
            model = TtsModels.MINI,
            selected = state.ttsModel == TtsModels.MINI,
            onSelect = onModelChange,
        )
        ModelOption(
            label = "qwen-audio-3.0-tts-flash（千问 · 备选）",
            model = TtsModels.QWEN_FLASH,
            selected = state.ttsModel == TtsModels.QWEN_FLASH,
            onSelect = onModelChange,
        )

        Text(
            text = "音色",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings-open-voice-sheet"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "当前音色：${Voices.DISPLAY[state.voice] ?: state.voice}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .testTag("settings-current-voice"),
            )
            OutlinedButton(
                onClick = { showVoiceSheet = true },
                modifier = Modifier.testTag("settings-voice-button"),
            ) {
                Text("切换音色")
            }
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (showVoiceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showVoiceSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            VoiceSheetContent(
                selectedVoice = state.voice,
                state = state,
                onSelect = { voice ->
                    onVoiceChange(voice)
                    showVoiceSheet = false
                },
                onPreview = onPreviewVoice,
            )
        }
    }
}

@Composable
private fun TestStatusText(test: TestState, testTag: String) {
    val (text, color) = when (test) {
        TestState.Idle -> return
        TestState.Running -> "正在测试连接…" to MaterialTheme.colorScheme.onSurfaceVariant
        is TestState.Success -> test.message to MaterialTheme.colorScheme.primary
        is TestState.Failure -> test.message to MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.testTag(testTag),
    )
}

@Composable
private fun ModelOption(
    label: String,
    model: String,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tts-model-$model")
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(model) })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
