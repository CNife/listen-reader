package com.cnife.listenreader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.service.TestState
import com.cnife.listenreader.ui.components.ChoiceRow
import com.cnife.listenreader.ui.components.LrDialog
import com.cnife.listenreader.ui.components.LrTextField
import com.cnife.listenreader.ui.components.PrimaryAction
import com.cnife.listenreader.ui.components.QuietAction
import com.cnife.listenreader.ui.components.SectionLabel
import com.cnife.listenreader.ui.player.VoiceSheetContent
import com.cnife.listenreader.ui.theme.CardSurface
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Ink2
import com.cnife.listenreader.ui.theme.Rule
import com.cnife.listenreader.ui.theme.Scrim

/** 设置页朗读区：两个供应商的 Key、模型选择、音色抽屉与测试连接。 */
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
    var showClearStepFun by remember { mutableStateOf(false) }
    var showClearQwen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsSectionTitle("朗读服务")
        Spacer(Modifier.height(2.dp))
        SectionLabel("StepFun")
        Text(
            text = if (state.stepFunKeyConfigured) "Key 已加密保存" else "尚未配置 Key",
            style = MaterialTheme.typography.bodySmall,
            color = Ink2,
            modifier = Modifier.testTag("stepfun-key-status"),
        )
        LrTextField(
            value = stepFunKeyInput,
            onValueChange = { stepFunKeyInput = it },
            label = "StepFun API Key",
            placeholder = "sk-…",
            visualTransformation = PasswordVisualTransformation(),
            testTag = "stepfun-key",
        )
        PrimaryAction(
            text = "保存 Key",
            onClick = {
                onSaveKey(stepFunKeyInput)
                stepFunKeyInput = ""
            },
            testTag = "stepfun-save-key",
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            QuietAction(
                text = if (state.stepFunTest is TestState.Running) "测试中…" else "测试连接",
                onClick = onTest,
                enabled = state.stepFunTest !is TestState.Running,
                testTag = "stepfun-test",
            )
            if (state.stepFunKeyConfigured) {
                QuietAction(
                    text = "清除 Key",
                    onClick = { showClearStepFun = true },
                    testTag = "stepfun-clear-key",
                )
            }
        }
        TestStatus(state.stepFunTest, testTag = "stepfun-test-status")

        Spacer(Modifier.height(8.dp))
        SectionLabel("千问")
        Text(
            text = if (state.qwenKeyConfigured) "Key 已加密保存" else "尚未配置 Key",
            style = MaterialTheme.typography.bodySmall,
            color = Ink2,
            modifier = Modifier.testTag("qwen-key-status"),
        )
        LrTextField(
            value = qwenKeyInput,
            onValueChange = { qwenKeyInput = it },
            label = "千问 API Key",
            placeholder = "sk-…",
            visualTransformation = PasswordVisualTransformation(),
            testTag = "qwen-key",
        )
        QuietAction(
            text = "保存千问 Key",
            onClick = {
                onSaveQwenKey(qwenKeyInput)
                qwenKeyInput = ""
            },
            testTag = "qwen-save-key",
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            QuietAction(
                text = if (state.qwenTest is TestState.Running) "测试中…" else "测试千问连接",
                onClick = onTestQwen,
                enabled = state.qwenTest !is TestState.Running,
                testTag = "qwen-test",
            )
            if (state.qwenKeyConfigured) {
                QuietAction(
                    text = "清除 Key",
                    onClick = { showClearQwen = true },
                    testTag = "qwen-clear-key",
                )
            }
        }
        TestStatus(state.qwenTest, testTag = "qwen-test-status")

        Spacer(Modifier.height(8.dp))
        SectionLabel("朗读模型")
        Column {
            ChoiceRow(
                label = "阶跃 step-tts-mini（默认）",
                selected = state.ttsModel == TtsModels.MINI,
                onSelect = { onModelChange(TtsModels.MINI) },
                testTag = "tts-model-${TtsModels.MINI}",
            )
            ChoiceRow(
                label = "千问 qwen-audio-3.0-tts-flash（备选）",
                selected = state.ttsModel == TtsModels.QWEN_FLASH,
                onSelect = { onModelChange(TtsModels.QWEN_FLASH) },
                testTag = "tts-model-${TtsModels.QWEN_FLASH}",
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionLabel("音色")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings-open-voice-sheet"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "当前音色 · ${Voices.DISPLAY[state.voice] ?: state.voice}",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                modifier = Modifier
                    .weight(1f)
                    .testTag("settings-current-voice"),
            )
            QuietAction(
                text = "切换",
                onClick = { showVoiceSheet = true },
                testTag = "settings-voice-button",
            )
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
            )
        }
    }

    if (showVoiceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showVoiceSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = CardSurface,
            scrimColor = Scrim,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(32.dp)
                        .height(4.dp)
                        .background(Rule, RoundedCornerShape(2.dp)),
                )
            },
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

    if (showClearStepFun) {
        LrDialog(
            title = "清除 StepFun Key？",
            body = "清除后需要重新填写 Key 才能使用阶跃朗读。",
            confirmLabel = "清除",
            onConfirm = {
                onSaveKey("")
                stepFunKeyInput = ""
                showClearStepFun = false
            },
            onDismiss = { showClearStepFun = false },
            confirmTag = "stepfun-clear-confirm",
            dismissTag = "stepfun-clear-cancel",
        )
    }

    if (showClearQwen) {
        LrDialog(
            title = "清除千问 Key？",
            body = "清除后需要重新填写 Key 才能使用千问朗读。",
            confirmLabel = "清除",
            onConfirm = {
                onSaveQwenKey("")
                qwenKeyInput = ""
                showClearQwen = false
            },
            onDismiss = { showClearQwen = false },
            confirmTag = "qwen-clear-confirm",
            dismissTag = "qwen-clear-cancel",
        )
    }
}
