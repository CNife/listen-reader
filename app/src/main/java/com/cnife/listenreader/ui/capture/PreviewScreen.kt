package com.cnife.listenreader.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.model.SegmentationSource
import com.cnife.listenreader.service.PreviewState
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.ui.components.PageHeader
import com.cnife.listenreader.ui.components.PrimaryAction
import com.cnife.listenreader.ui.components.RuleLine
import com.cnife.listenreader.ui.components.StatusLine
import com.cnife.listenreader.ui.theme.Accent
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Ink2
import com.cnife.listenreader.ui.theme.Paper

@Composable
fun PreviewScreen(
    service: ReadingService,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by service.capture.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedResourceId) {
        state.savedResourceId?.let(onSaved)
    }

    val preview = state.preview

    Scaffold(
        containerColor = Paper,
        topBar = {
            PageHeader(title = "预览", onBack = onBack)
        },
        bottomBar = {
            if (preview != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    PrimaryAction(
                        text = saveButtonLabel(state.isSaving, preview),
                        onClick = { service.saveCapture() },
                        enabled = !state.isSaving,
                        testTag = "preview-save",
                    )
                }
            }
        },
    ) { padding ->
        if (preview == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "还没有预览内容，请先粘贴文本。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                )
                state.error?.let { error ->
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink2,
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
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column {
                Text(
                    text = "标题",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                )
                Spacer(Modifier.height(6.dp))
                BasicTextField(
                    value = preview.title,
                    onValueChange = service::updatePreviewTitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preview-title"),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = Ink),
                    cursorBrush = SolidColor(Accent),
                )
                Spacer(Modifier.height(8.dp))
                RuleLine()
            }

            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(
                    text = "正文",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                )
                Spacer(Modifier.height(6.dp))
                BasicTextField(
                    value = preview.body,
                    onValueChange = service::updatePreviewBody,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preview-body"),
                    textStyle = longTextStyle(),
                    minLines = 12,
                    cursorBrush = SolidColor(Accent),
                )
                Spacer(Modifier.height(8.dp))
                RuleLine()
            }

            Text(
                text = metaLabel(preview),
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
                modifier = Modifier.testTag("preview-meta"),
            )

            L2StatusSection(
                preview = preview,
                onRetryL2 = service::retryL2,
                onOpenSettings = onOpenSettings,
                extraction = state.extraction,
                onUseFullPage = service::useFullPage,
            )

            state.error?.let { error ->
                StatusLine(
                    title = "出错",
                    description = error.message,
                )
            }
        }
    }
}

@Composable
private fun longTextStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    fontSize = 18.sp,
    lineHeight = 32.sp,
    fontWeight = FontWeight.Normal,
    color = Ink,
)

private fun metaLabel(preview: PreviewState): String = buildString {
    append(preview.charCount).append(" 字 · ")
    append(preview.segmentDrafts.size).append(" 句 · ")
    append("来源：").append(preview.sourceLabel)
    if (preview.segmentation == SegmentationSource.L2) {
        append(" · LLM 清洗")
    }
}

private fun saveButtonLabel(isSaving: Boolean, preview: PreviewState): String = when {
    isSaving -> "保存中…"
    preview.l2Status == L2Status.FAILED ||
        preview.l2Status == L2Status.NOT_CONFIGURED ||
        preview.l2Status == L2Status.RUNNING -> "保存（按规则分段）"
    else -> "保存"
}
