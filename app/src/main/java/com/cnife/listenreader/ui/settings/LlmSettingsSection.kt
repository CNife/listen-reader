package com.cnife.listenreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.service.TestState

/**
 * 设置页的 LLM（L2）配置区：端点、模型、Key、保存、测试连接与状态。
 * Key 只写不读——已保存时输入框留空即可保留原 Key。
 */
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "LLM 清洗（L2）",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Text(
            text = if (state.llmKeyConfigured) {
                "Key 已配置。留空 Key 输入框保存可保留现有 Key。"
            } else {
                "尚未配置 Key，采集时只能使用规则分段。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("llm-key-state"),
        )

        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("llm-endpoint"),
            label = { Text("端点") },
            placeholder = { Text("https://openrouter.ai/api/v1") },
            singleLine = true,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("llm-model"),
            label = { Text("模型") },
            placeholder = { Text("openai/gpt-4.1-mini") },
            singleLine = true,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("llm-key"),
            label = { Text("API Key") },
            placeholder = { Text(if (state.llmKeyConfigured) "留空表示保留已保存的 Key" else "sk-…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(endpoint, key, model) },
                modifier = Modifier.testTag("llm-save"),
            ) {
                Text("保存")
            }
            OutlinedButton(
                onClick = {
                    onSave(endpoint, key, model)
                    onTest()
                },
                modifier = Modifier.testTag("llm-test"),
            ) {
                Text("测试连接")
            }
            if (state.llmKeyConfigured) {
                TextButton(
                    onClick = {
                        key = ""
                        onClearKey()
                    },
                    modifier = Modifier.testTag("llm-clear-key"),
                ) {
                    Text("清除 Key")
                }
            }
        }

        when (val test = state.llmTest) {
            TestState.Idle -> Unit
            TestState.Running -> Text(
                text = "正在测试连接…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("llm-test-status"),
            )
            is TestState.Success -> Text(
                text = test.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("llm-test-status"),
            )
            is TestState.Failure -> Text(
                text = test.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("llm-test-status"),
            )
        }
    }
}
