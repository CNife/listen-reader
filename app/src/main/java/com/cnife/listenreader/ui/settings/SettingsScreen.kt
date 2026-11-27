package com.cnife.listenreader.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.TestState
import com.cnife.listenreader.ui.components.LrCard
import com.cnife.listenreader.ui.components.PageHeader
import com.cnife.listenreader.ui.components.SectionLabel
import com.cnife.listenreader.ui.components.SegmentedControl
import com.cnife.listenreader.ui.components.StatusLine
import com.cnife.listenreader.ui.theme.Canvas
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Meta

@Composable
fun SettingsScreen(service: ReadingService, onBack: () -> Unit) {
    val state by service.settings.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Canvas,
        topBar = {
            PageHeader(title = "设置", onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 40.dp),
        ) {
            state.error?.let { error ->
                StatusLine(
                    title = "出错",
                    description = error.message,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 12.dp)
                        .testTag("settings-error"),
                )
            }

            SectionLabel(
                text = "外观",
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            SegmentedControl(
                options = listOf(
                    ThemeMode.SYSTEM.name to "跟随系统",
                    ThemeMode.LIGHT.name to "浅色",
                    ThemeMode.DARK.name to "深色",
                ),
                selectedKey = state.themeMode.name,
                onSelect = { key ->
                    ThemeMode.entries.firstOrNull { it.name == key }?.let(service::setThemeMode)
                },
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp),
                tagPrefix = "theme",
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel(
                text = "朗读服务",
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp),
            ) {
                StepFunSettingsSection(service = service)
            }

            Spacer(Modifier.height(24.dp))
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                LlmSettingsSection(
                    state = state,
                    onSave = service::saveLlmConfig,
                    onTest = service::testLlm,
                    onClearKey = { service.setLlmKey("") },
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(
                text = "预取",
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LrCard(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp),
                padding = PaddingValues(20.dp),
            ) {
                Text(
                    text = "预取句数",
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink,
                )
                SegmentedControl(
                    options = listOf(
                        "0" to "关闭",
                        "2" to "2",
                        "4" to "4",
                        "6" to "6",
                        "8" to "8",
                    ),
                    selectedKey = state.prefetchCount.toString(),
                    onSelect = { key -> key.toIntOrNull()?.let(service::setPrefetchCount) },
                    modifier = Modifier.padding(top = 12.dp),
                    tagPrefix = "prefetch",
                )
                Text(
                    text = if (state.prefetchCount == 0) {
                        "预取已关闭。"
                    } else {
                        "播放当前句时预先合成后面 ${state.prefetchCount} 句，最多并行 2 句。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Meta,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                StorageSection(service = service)
            }

            Spacer(Modifier.height(24.dp))
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                UpdateSection(service = service)
            }
        }
    }
}

@Composable
internal fun SettingsSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = Ink,
    )
}

@Composable
internal fun TestStatus(test: TestState, testTag: String) {
    when (test) {
        TestState.Idle -> Unit
        TestState.Running -> Text(
            text = "正在测试连接…",
            style = MaterialTheme.typography.bodySmall,
            color = Meta,
            modifier = Modifier.testTag(testTag),
        )
        is TestState.Success -> Text(
            text = test.message,
            style = MaterialTheme.typography.bodySmall,
            color = Meta,
            modifier = Modifier.testTag(testTag),
        )
        is TestState.Failure -> Column {
            Text(
                text = "测试失败",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = Ink,
            )
            Text(
                text = test.message,
                style = MaterialTheme.typography.bodySmall,
                color = Meta,
                modifier = Modifier.testTag(testTag),
            )
        }
    }
}
