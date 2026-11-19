package com.cnife.listenreader.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.service.CaptureMode
import com.cnife.listenreader.service.ReadingService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    service: ReadingService,
    onBack: () -> Unit,
    onOpenPreview: () -> Unit,
) {
    val state by service.capture.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var emptyHint by rememberSaveable { mutableStateOf(false) }

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
            if (state.mode == CaptureMode.URL && state.url.isNotBlank()) {
                Text(
                    text = "网页采集",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = state.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "网页正文提取将在后续版本接入，可先粘贴文本。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "粘贴文本",
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    emptyHint = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("capture-text-input"),
                label = { Text("要朗读的文本") },
                placeholder = { Text("把文章或段落粘贴到这里") },
                minLines = 8,
                maxLines = 16,
            )

            if (emptyHint) {
                Text(
                    text = "请先粘贴要朗读的文本",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.error?.let { error ->
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    if (text.isBlank()) {
                        emptyHint = true
                    } else {
                        service.beginTextCapture(text)
                        if (service.capture.value.preview != null) {
                            onOpenPreview()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("预览")
            }
        }
    }
}
