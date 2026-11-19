package com.cnife.listenreader.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.model.SegmentationSource
import com.cnife.listenreader.service.PreviewState

/**
 * 预览页的 L2 状态区：明确区分「LLM 分段」与「规则分段」，
 * 失败时给出原因与重试，未配置时引导去设置。
 */
@Composable
fun L2StatusSection(
    preview: PreviewState,
    onRetryL2: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("l2-section"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = previewStatusLabel(preview),
            style = MaterialTheme.typography.titleSmall,
            color = when (preview.l2Status) {
                L2Status.SUCCEEDED -> MaterialTheme.colorScheme.primary
                L2Status.FAILED, L2Status.NOT_CONFIGURED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.testTag("preview-status"),
        )
        Text(
            text = segmentationLabel(preview),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("l2-segmentation"),
        )

        when (preview.l2Status) {
            L2Status.RUNNING -> {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("l2-progress"),
                )
                Text(
                    text = "正在用 LLM 清洗并分段…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            L2Status.FAILED -> {
                Text(
                    text = preview.l2Error ?: "LLM 清洗失败，当前为规则分段",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("l2-error"),
                )
                Text(
                    text = "保存仍可用，但会按规则分段朗读。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onRetryL2,
                    modifier = Modifier.testTag("l2-retry"),
                ) {
                    Text("重试 L2")
                }
            }

            L2Status.NOT_CONFIGURED -> {
                Text(
                    text = preview.l2Error ?: "尚未配置 LLM Key，当前为规则分段",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("l2-error"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("l2-open-settings"),
                    ) {
                        Text("去设置")
                    }
                    OutlinedButton(
                        onClick = onRetryL2,
                        modifier = Modifier.testTag("l2-retry"),
                    ) {
                        Text("重试 L2")
                    }
                }
            }

            L2Status.NOT_RUN -> {
                Text(
                    text = "当前按规则分段，未经 LLM 清洗。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onRetryL2,
                    modifier = Modifier.testTag("l2-retry"),
                ) {
                    Text("重新 L2")
                }
            }

            L2Status.SUCCEEDED -> Text(
                text = "已由 LLM 清洗并分段，共 ${preview.segmentDrafts.size} 段。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("l2-success-detail"),
            )
        }
    }
}

internal fun previewStatusLabel(preview: PreviewState): String = when {
    preview.segmentation == SegmentationSource.L2 && preview.l2Status == L2Status.SUCCEEDED ->
        "LLM 分段"
    preview.l2Status == L2Status.RUNNING -> "规则分段 · LLM 清洗中…"
    preview.l2Status == L2Status.FAILED -> "规则分段 · LLM 清洗失败"
    preview.l2Status == L2Status.NOT_CONFIGURED -> "规则分段 · LLM 未配置"
    else -> "规则分段 · LLM 未运行"
}

internal fun segmentationLabel(preview: PreviewState): String =
    if (preview.segmentation == SegmentationSource.L2) "当前分段：LLM 分段" else "当前分段：规则分段"
