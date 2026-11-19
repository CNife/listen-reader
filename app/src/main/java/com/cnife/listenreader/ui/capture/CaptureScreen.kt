package com.cnife.listenreader.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.service.CaptureMode
import com.cnife.listenreader.service.ReadingService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(service: ReadingService, onBack: () -> Unit) {
    val state by service.capture.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("采集") },
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = when (state.mode) {
                    CaptureMode.TEXT -> "粘贴文本"
                    CaptureMode.URL -> "网页采集"
                    CaptureMode.IDLE -> "新建采集"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            if (state.url.isNotBlank()) {
                Text(
                    text = state.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "内置浏览器与正文提取将在后续版本接入。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
