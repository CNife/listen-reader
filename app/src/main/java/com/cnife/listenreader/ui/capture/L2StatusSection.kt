package com.cnife.listenreader.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.service.ExtractionInfo
import com.cnife.listenreader.service.PreviewState
import com.cnife.listenreader.ui.components.IndeterminateProgressLine
import com.cnife.listenreader.ui.components.QuietAction
import com.cnife.listenreader.ui.components.StatusLine
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Ink2

/**
 * 预览页的分段状态区：只写状态、原因和一个朱砂动作，不套彩色容器。
 * 运行中是一条 2dp 不确定线，成功只说结果，失败给原因和「重新清洗」。
 */
@Composable
fun L2StatusSection(
    preview: PreviewState,
    onRetryL2: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    extraction: ExtractionInfo? = null,
    onUseFullPage: () -> Unit = {},
) {
    val statusStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("l2-section"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (preview.l2Status) {
            L2Status.RUNNING -> {
                Text(
                    text = "正在用 LLM 清洗并分段…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    modifier = Modifier.testTag("preview-status"),
                )
                IndeterminateProgressLine(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .testTag("l2-progress"),
                )
            }

            L2Status.SUCCEEDED -> {
                Text(
                    text = "LLM 分段",
                    style = statusStyle,
                    color = Ink,
                    modifier = Modifier.testTag("preview-status"),
                )
                Text(
                    text = "已清洗，${preview.segmentDrafts.size} 段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    modifier = Modifier.testTag("l2-segmentation"),
                )
            }

            L2Status.FAILED -> {
                Text(
                    text = "LLM 清洗失败",
                    style = statusStyle,
                    color = Ink,
                    modifier = Modifier.testTag("preview-status"),
                )
                Text(
                    text = preview.l2Error ?: "LLM 返回异常，当前按规则分段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    modifier = Modifier.testTag("l2-error"),
                )
                QuietAction(
                    text = "重新清洗",
                    onClick = onRetryL2,
                    testTag = "l2-retry",
                )
            }

            L2Status.NOT_CONFIGURED -> {
                Text(
                    text = "LLM 未配置",
                    style = statusStyle,
                    color = Ink,
                    modifier = Modifier.testTag("preview-status"),
                )
                Text(
                    text = "还没有配置 LLM。分段会按规则进行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    modifier = Modifier.testTag("l2-error"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    QuietAction(
                        text = "去设置",
                        onClick = onOpenSettings,
                        testTag = "l2-open-settings",
                    )
                    QuietAction(
                        text = "重新清洗",
                        onClick = onRetryL2,
                        testTag = "l2-retry",
                    )
                }
            }

            L2Status.NOT_RUN -> {
                Text(
                    text = "当前按规则分段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                    modifier = Modifier.testTag("preview-status"),
                )
                QuietAction(
                    text = "重新清洗",
                    onClick = onRetryL2,
                    testTag = "l2-retry",
                )
            }
        }

        if (extraction != null && extraction.needsExpand) {
            StatusLine(
                title = if (extraction.usedFullPage) "已使用页面全文" else "正文可能不完整",
                modifier = Modifier.testTag("preview-degraded-hint"),
                description = if (extraction.usedFullPage) {
                    "已使用页面全文，可编辑正文后保存。"
                } else {
                    buildString {
                        extraction.degradedReason?.let { append(it).append("。") }
                        append("可以编辑，或使用页面全文。")
                    }
                },
                actionLabel = if (extraction.usedFullPage) null else "使用页面全文",
                onAction = if (extraction.usedFullPage) null else onUseFullPage,
                actionTag = "preview-use-full-page",
            )
        }
    }
}
