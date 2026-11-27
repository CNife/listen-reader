package com.cnife.listenreader.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cnife.listenreader.ui.theme.Hairline
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.LrCardShape
import com.cnife.listenreader.ui.theme.LrPillShape
import com.cnife.listenreader.ui.theme.Meta
import com.cnife.listenreader.ui.theme.Muted
import com.cnife.listenreader.ui.theme.OnPrimary
import com.cnife.listenreader.ui.theme.Primary
import com.cnife.listenreader.ui.theme.Sunken
import com.cnife.listenreader.ui.theme.Surface as CardSurface

@Composable
fun LrCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(LrCardShape)
            .background(CardSurface)
            .border(1.dp, Hairline, LrCardShape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun CardDivider(
    modifier: Modifier = Modifier,
    startIndent: Dp = 0.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(1.dp)
            .background(Hairline),
    )
}

@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(color, CircleShape),
    )
}

@Composable
fun ProgressLine(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
    trackColor: Color = Sunken,
    fillColor: Color = Primary,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .background(fillColor),
        )
    }
}

@Composable
fun PlaybackProgress(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val clamped = progress.coerceIn(0f, 1f)
        val thumbSize = 12.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(Sunken),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(4.dp)
                .clip(CircleShape)
                .background(Primary),
        )
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - thumbSize) * clamped)
                .size(thumbSize)
                .background(Primary, CircleShape),
        )
    }
}

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
            .height(3.dp)
            .clip(CircleShape)
            .background(Sunken),
    ) {
        val barWidth = maxWidth * 0.38f
        Box(
            modifier = Modifier
                .width(barWidth)
                .height(3.dp)
                .offset(x = (maxWidth - barWidth) * progress)
                .background(Primary),
        )
    }
}

@Composable
fun StatusLine(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionTag: String? = null,
    dotColor: Color? = null,
) {
    LrCard(
        modifier = modifier.fillMaxWidth(),
        padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = dotColor ?: Primary)
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Ink,
            )
        }
        if (description != null) {
            Text(
                text = description,
                modifier = Modifier.padding(top = 4.dp, start = 18.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Meta,
            )
        }
        if (actionLabel != null && onAction != null) {
            Box(modifier = Modifier.padding(start = 18.dp)) {
                QuietAction(
                    text = actionLabel,
                    onClick = onAction,
                    testTag = actionTag,
                )
            }
        }
    }
}

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
            shape = RoundedCornerShape(16.dp),
            color = CardSurface,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, Hairline),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 20.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = Ink,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                Spacer(Modifier.height(20.dp))
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
                        colors = ButtonDefaults.textButtonColors(contentColor = Muted),
                    ) {
                        Text(dismissLabel, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .height(44.dp)
                            .let { if (confirmTag != null) it.testTag(confirmTag) else it },
                        shape = LrPillShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = OnPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                    ) {
                        Text(
                            text = confirmLabel,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}
