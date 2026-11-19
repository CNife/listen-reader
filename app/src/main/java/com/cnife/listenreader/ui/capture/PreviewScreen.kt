package com.cnife.listenreader.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.model.SegmentationSource
import com.cnife.listenreader.service.PreviewState
import com.cnife.listenreader.service.ReadingService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    service: ReadingService,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val state by service.capture.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedResourceId) {
        if (state.savedResourceId != null) {
            onSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("预览") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val preview = state.preview
        if (preview == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("还没有预览内容，请先粘贴文本。", style = MaterialTheme.typography.bodyLarge)
                state.error?.let { error ->
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = preview.title,
                onValueChange = service::updatePreviewTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preview-title"),
                label = { Text("标题") },
                singleLine = true,
            )

            OutlinedTextField(
                value = preview.body,
                onValueChange = service::updatePreviewBody,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preview-body"),
                label = { Text("正文") },
                minLines = 10,
                maxLines = 24,
            )

            Text(
                text = "${preview.charCount} 字 · ${preview.segmentDrafts.size} 句",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("preview-meta"),
            )
            Text(
                text = previewStatusLabel(preview),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("preview-status"),
            )
            Text(
                text = buildString {
                    append("来源：").append(preview.sourceLabel)
                    preview.extractor?.let { append(" · 提取：").append(it) }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.error?.let { error ->
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { service.saveCapture() },
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isSaving) "保存中…" else "保存")
            }
        }
    }
}

private fun previewStatusLabel(preview: PreviewState): String = when {
    preview.segmentation == SegmentationSource.L2 && preview.l2Status == L2Status.SUCCEEDED ->
        "LLM 分段"
    preview.l2Status == L2Status.FAILED ->
        "规则分段 · LLM 清洗失败"
    else -> "规则分段 · LLM 未运行"
}
