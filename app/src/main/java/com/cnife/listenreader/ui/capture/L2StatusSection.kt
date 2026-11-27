package com.cnife.listenreader.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.service.ExtractionInfo
import com.cnife.listenreader.service.PreviewState
import com.cnife.listenreader.ui.components.IndeterminateProgressLine
import com.cnife.listenreader.ui.components.LrCard
import com.cnife.listenreader.ui.components.QuietAction
import com.cnife.listenreader.ui.components.StatusDot
import com.cnife.listenreader.ui.components.StatusLine
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Meta
import com.cnife.listenreader.ui.theme.Primary
import com.cnife.listenreader.ui.theme.StickerGreen
import com.cnife.listenreader.ui.theme.StickerOrange

@Composable
fun L2StatusSection(
    preview: PreviewState,
    onRetryL2: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    extraction: ExtractionInfo? = null,
    onUseFullPage: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("l2-section"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LrCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            when (preview.l2Status) {
                L2Status.RUNNING -> {
                    StatusHeader(
                        dotColor = Primary,
                        title = "正在用 LLM 清洗并分段…",
                        titleTag = "preview-status",
                    )
                    IndeterminateProgressLine(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .testTag("l2-progress"),
                    )
                }

                L2Status.SUCCEEDED -> {
                    StatusHeader(
                        dotColor = StickerGreen,
                        title = "LLM 分段",
                        titleTag = "preview-status",
                    )
                    Text(
                        text = "已清洗，${preview.segmentDrafts.size} 段。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Meta,
                        modifier = Modifier
                            .padding(top = 4.dp, start = 18.dp)
                            .testTag("l2-segmentation"),
                    )
                }

                L2Status.FAILED -> {
                    StatusHeader(
                        dotColor = StickerOrange,
                        title = "LLM 清洗失败",
                        titleTag = "preview-status",
                    )
                    Text(
                        text = preview.l2Error ?: "LLM 返回异常，当前按规则分段。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Meta,
                        modifier = Modifier
                            .padding(top = 4.dp, start = 18.dp)
                            .testTag("l2-error"),
                    )
                    BoxedAction {
                        QuietAction(
                            text = "重新清洗",
                            onClick = onRetryL2,
                            testTag = "l2-retry",
                        )
                    }
                }

                L2Status.NOT_CONFIGURED -> {
                    StatusHeader(
                        dotColor = Primary,
                        title = "LLM 未配置",
                        titleTag = "preview-status",
                    )
                    Text(
                        text = "还没有配置 LLM。分段会按规则进行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Meta,
                        modifier = Modifier
                            .padding(top = 4.dp, start = 18.dp)
                            .testTag("l2-error"),
                    )
                    Row(
                        modifier = Modifier.padding(start = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        QuietAction(
                            text = "去配置",
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
                        color = Meta,
                        modifier = Modifier.testTag("preview-status"),
                    )
                    BoxedAction {
                        QuietAction(
                            text = "重新清洗",
                            onClick = onRetryL2,
                            testTag = "l2-retry",
                        )
                    }
                }
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

@Composable
private fun StatusHeader(
    dotColor: Color,
    title: String,
    titleTag: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = dotColor)
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = Ink,
            modifier = Modifier.testTag(titleTag),
        )
    }
}

@Composable
private fun BoxedAction(content: @Composable () -> Unit) {
    Row(modifier = Modifier.padding(start = 18.dp)) {
        content()
    }
}
