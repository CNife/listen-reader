package com.cnife.listenreader.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.TestState
import com.cnife.listenreader.ui.components.PageHeader
import com.cnife.listenreader.ui.components.RuleLine
import com.cnife.listenreader.ui.components.StatusLine
import com.cnife.listenreader.ui.components.TextTabs
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Ink2
import com.cnife.listenreader.ui.theme.Paper

@Composable
fun SettingsScreen(service: ReadingService, onBack: () -> Unit) {
    val state by service.settings.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Paper,
        topBar = {
            PageHeader(title = "设置", onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 24.dp, bottom = 40.dp),
        ) {
            state.error?.let { error ->
                StatusLine(
                    title = "出错",
                    description = error.message,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .testTag("settings-error"),
                )
            }

            SettingsSectionTitle("外观", Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            TextTabs(
                options = listOf(
                    ThemeMode.SYSTEM.name to "跟随系统",
                    ThemeMode.LIGHT.name to "浅色",
                    ThemeMode.DARK.name to "深色",
                ),
                selectedKey = state.themeMode.name,
                onSelect = { key ->
                    ThemeMode.entries.firstOrNull { it.name == key }?.let(service::setThemeMode)
                },
                tagPrefix = "theme",
            )

            SectionDivider()

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                StepFunSettingsSection(service = service)
            }

            SectionDivider()

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                LlmSettingsSection(
                    state = state,
                    onSave = service::saveLlmConfig,
                    onTest = service::testLlm,
                    onClearKey = { service.setLlmKey("") },
                )
            }

            SectionDivider()

            SettingsSectionTitle("预取", Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            TextTabs(
                options = listOf(
                    "0" to "关闭",
                    "2" to "2",
                    "4" to "4",
                    "6" to "6",
                    "8" to "8",
                ),
                selectedKey = state.prefetchCount.toString(),
                onSelect = { key -> key.toIntOrNull()?.let(service::setPrefetchCount) },
                tagPrefix = "prefetch",
            )
            Text(
                text = if (state.prefetchCount == 0) {
                    "预取已关闭。"
                } else {
                    "播放当前句时预先合成后面 ${state.prefetchCount} 句，最多并行 2 句。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = com.cnife.listenreader.ui.theme.Ink2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            SectionDivider()

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                StorageSection(service = service)
            }
        }
    }
}

@Composable
internal fun SettingsSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = Ink,
    )
}

@Composable
internal fun SectionDivider() {
    Spacer(Modifier.height(20.dp))
    RuleLine(Modifier.padding(horizontal = 20.dp))
    Spacer(Modifier.height(20.dp))
}

@Composable
internal fun TestStatus(test: TestState, testTag: String) {
    when (test) {
        TestState.Idle -> Unit
        TestState.Running -> Text(
            text = "正在测试连接…",
            style = MaterialTheme.typography.bodySmall,
            color = Ink2,
            modifier = Modifier.testTag(testTag),
        )
        is TestState.Success -> Text(
            text = test.message,
            style = MaterialTheme.typography.bodySmall,
            color = Ink2,
            modifier = Modifier.testTag(testTag),
        )
        is TestState.Failure -> Column {
            Text(
                text = "测试失败",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Ink,
            )
            Text(
                text = test.message,
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
                modifier = Modifier.testTag(testTag),
            )
        }
    }
}
