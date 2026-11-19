package com.cnife.listenreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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

/** 设置页 StepFun 区：key、模型、Voice、测试连接。key 只以密码形式展示，不进入 UI 状态。 */
@Composable
fun StepFunSettingsSection(service: ReadingService) {
    val state by service.settings.collectAsStateWithLifecycle()
    StepFunSettingsContent(
        state = state,
        onSaveKey = service::saveStepFunKey,
        onModelChange = service::setModel,
        onVoiceChange = service::setVoice,
        onTest = service::testStepFun,
    )
}

@Composable
internal fun StepFunSettingsContent(
    state: SettingsState,
    onSaveKey: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onVoiceChange: (String) -> Unit,
    onTest: () -> Unit,
) {
    var keyInput by remember { mutableStateOf("") }

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
            value = keyInput,
            onValueChange = { keyInput = it },
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
                    onSaveKey(keyInput)
                    keyInput = ""
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
        TestStatusText(state.stepFunTest)

        Text("朗读模型", style = MaterialTheme.typography.titleSmall)
        ModelOption(
            label = "stepaudio-3-tts（默认）",
            model = TtsModels.AUDIO3,
            selected = state.ttsModel == TtsModels.AUDIO3,
            onSelect = onModelChange,
        )
        ModelOption(
            label = "step-tts-mini",
            model = TtsModels.MINI,
            selected = state.ttsModel == TtsModels.MINI,
            onSelect = onModelChange,
        )

        Text(
            text = "音色",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Voices.ALL.forEach { voice ->
            VoiceOption(
                label = Voices.DISPLAY[voice] ?: voice,
                voice = voice,
                selected = state.voice == voice,
                onSelect = onVoiceChange,
            )
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TestStatusText(test: TestState) {
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
        modifier = Modifier.testTag("stepfun-test-status"),
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

@Composable
private fun VoiceOption(
    label: String,
    voice: String,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice-$voice")
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(voice) })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
