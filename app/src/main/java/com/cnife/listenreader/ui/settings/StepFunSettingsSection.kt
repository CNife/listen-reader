package com.cnife.listenreader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.service.TestState
import com.cnife.listenreader.ui.components.CardDivider
import com.cnife.listenreader.ui.components.ChoiceRow
import com.cnife.listenreader.ui.components.LrCard
import com.cnife.listenreader.ui.components.LrDialog
import com.cnife.listenreader.ui.components.LrTextField
import com.cnife.listenreader.ui.components.PillAction
import com.cnife.listenreader.ui.components.QuietAction
import com.cnife.listenreader.ui.components.SectionLabel
import com.cnife.listenreader.ui.components.StatusDot
import com.cnife.listenreader.ui.player.VoiceSheetContent
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.LrSheetShape
import com.cnife.listenreader.ui.theme.Meta
import com.cnife.listenreader.ui.theme.RuleStrong
import com.cnife.listenreader.ui.theme.Scrim
import com.cnife.listenreader.ui.theme.StickerGreen
import com.cnife.listenreader.ui.theme.Surface

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LrCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(20.dp),
        ) {
            ServiceHeader(
                name = "StepFun",
                configured = state.stepFunKeyConfigured,
                statusText = if (state.stepFunKeyConfigured) "Key 已加密保存" else "尚未配置 Key",
                statusTag = "stepfun-key-status",
            )
            LrTextField(
                value = stepFunKeyInput,
                onValueChange = { stepFunKeyInput = it },
                label = "StepFun API Key",
                placeholder = "sk-…",
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.padding(top = 12.dp),
                testTag = "stepfun-key",
            )
            ActionRow(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                PillAction(
                    text = "保存 Key",
                    onClick = {
                        onSaveKey(stepFunKeyInput)
                        stepFunKeyInput = ""
                    },
                    testTag = "stepfun-save-key",
                )
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

            Spacer(Modifier.height(16.dp))
            CardDivider()
            Spacer(Modifier.height(16.dp))
            SectionLabel("朗读模型")
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

        LrCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(20.dp),
        ) {
            ServiceHeader(
                name = "千问",
                configured = state.qwenKeyConfigured,
                statusText = if (state.qwenKeyConfigured) "Key 已加密保存" else "尚未配置 Key",
                statusTag = "qwen-key-status",
            )
            LrTextField(
                value = qwenKeyInput,
                onValueChange = { qwenKeyInput = it },
                label = "千问 API Key",
                placeholder = "sk-…",
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.padding(top = 12.dp),
                testTag = "qwen-key",
            )
            ActionRow(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                PillAction(
                    text = "保存千问 Key",
                    onClick = {
                        onSaveQwenKey(qwenKeyInput)
                        qwenKeyInput = ""
                    },
                    testTag = "qwen-save-key",
                )
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
        }

        LrCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings-open-voice-sheet"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionLabel("音色")
                    Text(
                        text = "当前音色 · ${Voices.DISPLAY[state.voice] ?: state.voice}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("settings-current-voice"),
                    )
                }
                QuietAction(
                    text = "切换",
                    onClick = { showVoiceSheet = true },
                    testTag = "settings-voice-button",
                )
            }
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Meta,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (showVoiceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showVoiceSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Surface,
            scrimColor = Scrim,
            tonalElevation = 0.dp,
            shape = LrSheetShape,
            dragHandle = { VoiceSheetDragHandle() },
        ) {
            VoiceSheetContent(
                selectedVoice = state.voice,
                state = state,
                onSelect = { voice ->
                    onVoiceChange(voice)
                    showVoiceSheet = false
                },
                onPreview = onPreviewVoice,
                onDone = { showVoiceSheet = false },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceHeader(
    name: String,
    configured: Boolean,
    statusText: String,
    statusTag: String,
) {
    val fontScale = LocalDensity.current.fontScale
    val stacked = fontScale >= 1.5f
    if (stacked) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = if (configured) StickerGreen else RuleStrong)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = Ink,
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = Meta,
                modifier = Modifier
                    .padding(start = 18.dp, top = 2.dp)
                    .testTag(statusTag),
            )
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = if (configured) StickerGreen else RuleStrong)
            Spacer(Modifier.width(10.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = Meta,
                modifier = Modifier.testTag(statusTag),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}

@Composable
private fun VoiceSheetDragHandle() {
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
