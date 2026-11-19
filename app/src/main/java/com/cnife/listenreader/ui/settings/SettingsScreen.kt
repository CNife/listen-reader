package com.cnife.listenreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.ReadingService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(service: ReadingService, onBack: () -> Unit) {
    val state by service.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "外观",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            ThemeOption(
                label = "跟随系统",
                selected = state.themeMode == ThemeMode.SYSTEM,
                testTag = "theme-SYSTEM",
                onSelect = { service.setThemeMode(ThemeMode.SYSTEM) },
            )
            ThemeOption(
                label = "浅色",
                selected = state.themeMode == ThemeMode.LIGHT,
                testTag = "theme-LIGHT",
                onSelect = { service.setThemeMode(ThemeMode.LIGHT) },
            )
            ThemeOption(
                label = "深色",
                selected = state.themeMode == ThemeMode.DARK,
                testTag = "theme-DARK",
                onSelect = { service.setThemeMode(ThemeMode.DARK) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            StepFunSettingsSection(service = service)

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                text = "LLM 清洗、预取与存储清理将在后续版本接入。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    selected: Boolean,
    testTag: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
