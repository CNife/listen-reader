package com.cnife.listenreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.ui.components.LrTextField
import com.cnife.listenreader.ui.components.QuietAction
import com.cnife.listenreader.ui.theme.Ink2

@Composable
fun LlmSettingsSection(
    state: SettingsState,
    onSave: (endpoint: String, key: String, model: String) -> Unit,
    onTest: () -> Unit,
    onClearKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var endpoint by rememberSaveable(state.llmEndpoint) { mutableStateOf(state.llmEndpoint) }
    var model by rememberSaveable(state.llmModel) { mutableStateOf(state.llmModel) }
    var key by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("llm-settings-section"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsSectionTitle("LLM 清洗")
        Text(
            text = if (state.llmKeyConfigured) {
                "Key 已配置。留空 Key 输入框保存可保留现有 Key。"
            } else {
                "还没有配置 LLM。分段会按规则进行。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Ink2,
            modifier = Modifier.testTag("llm-key-state"),
        )

        LrTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = "端点",
            placeholder = "https://openrouter.ai/api/v1",
            testTag = "llm-endpoint",
        )
        LrTextField(
            value = model,
            onValueChange = { model = it },
            label = "模型",
            placeholder = "openai/gpt-4.1-mini",
            testTag = "llm-model",
        )
        LrTextField(
            value = key,
            onValueChange = { key = it },
            label = "API Key",
            placeholder = if (state.llmKeyConfigured) "留空表示保留已保存的 Key" else "sk-…",
            visualTransformation = PasswordVisualTransformation(),
            testTag = "llm-key",
        )

        QuietAction(
            text = "保存并测试",
            onClick = {
                onSave(endpoint, key, model)
                onTest()
            },
            testTag = "llm-test",
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            QuietAction(
                text = "仅保存",
                onClick = { onSave(endpoint, key, model) },
                testTag = "llm-save",
            )
            if (state.llmKeyConfigured) {
                QuietAction(
                    text = "清除 Key",
                    onClick = {
                        key = ""
                        onClearKey()
                    },
                    testTag = "llm-clear-key",
                )
            }
        }

        TestStatus(state.llmTest, testTag = "llm-test-status")
    }
}
