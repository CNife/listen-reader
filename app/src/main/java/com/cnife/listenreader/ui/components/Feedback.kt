package com.cnife.listenreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cnife.listenreader.ui.theme.Accent
import com.cnife.listenreader.ui.theme.CardSurface
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Ink2
import com.cnife.listenreader.ui.theme.OnAccent
import com.cnife.listenreader.ui.theme.Rule

/** 2dp 细进度线：轨道用 Rule，填充用朱砂；两端平直。 */
@Composable
fun ProgressLine(
    progress: Float,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 2.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(Rule),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .background(Accent),
        )
    }
}

/** 不确定进度：2dp 朱砂线线性扫过纸面，没有末端圆点。 */
@Composable
fun IndeterminateProgressLine(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "indeterminate-line")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "indeterminate-progress",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Rule),
    ) {
        val barWidth = maxWidth * 0.38f
        Box(
            modifier = Modifier
                .width(barWidth)
                .height(2.dp)
                .offset(x = (maxWidth - barWidth) * progress)
                .background(Accent),
        )
    }
}

/**
 * 状态行不套卡片：标题 16/600，说明 13/20，动作是朱砂文字。
 * 生成中/失败用文字说明，不用黄红容器。
 */
@Composable
fun StatusLine(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionTag: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Ink,
        )
        if (description != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier
                    .height(40.dp)
                    .let { if (actionTag != null) it.testTag(actionTag) else it },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Accent),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/** 自定义确认弹层：纸面、1dp 细线、12dp 圆角；动作右对齐。 */
@Composable
fun LrDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "取消",
    confirmTag: String? = null,
    dismissTag: String? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(12.dp),
            color = CardSurface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, Rule),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink2,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .height(44.dp)
                            .let { if (dismissTag != null) it.testTag(dismissTag) else it },
                        colors = ButtonDefaults.textButtonColors(contentColor = Ink2),
                    ) {
                        Text(dismissLabel, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .height(44.dp)
                            .let { if (confirmTag != null) it.testTag(confirmTag) else it },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = OnAccent,
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    ) {
                        Text(
                            text = confirmLabel,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }
}
