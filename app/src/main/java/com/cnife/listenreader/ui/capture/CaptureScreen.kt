package com.cnife.listenreader.ui.capture

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.service.CaptureMode
import com.cnife.listenreader.service.PageUiState
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.ui.components.PageHeader
import com.cnife.listenreader.ui.components.IndeterminateProgressLine
import com.cnife.listenreader.ui.components.PrimaryAction
import com.cnife.listenreader.ui.components.ProgressLine
import com.cnife.listenreader.ui.components.QuietAction
import com.cnife.listenreader.ui.components.RuleLine
import com.cnife.listenreader.ui.components.StatusLine
import com.cnife.listenreader.ui.components.TextTabs
import com.cnife.listenreader.ui.theme.Accent
import com.cnife.listenreader.ui.theme.Ink
import com.cnife.listenreader.ui.theme.Ink2
import com.cnife.listenreader.ui.theme.Ink3
import com.cnife.listenreader.ui.theme.Paper
import com.cnife.listenreader.ui.theme.Sunken

@Composable
fun CaptureScreen(
    service: ReadingService,
    onBack: () -> Unit,
) {
    val state by service.capture.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var urlText by rememberSaveable { mutableStateOf(state.url) }
    var text by rememberSaveable { mutableStateOf("") }
    var emptyHint by rememberSaveable { mutableStateOf(false) }
    var textMode by rememberSaveable { mutableStateOf(state.mode == CaptureMode.TEXT) }

    BackHandler { onBack() }

    // 分享/外部进入时如果页面还没开始加载，补一次加载（已有预览时不重载）。
    LaunchedEffect(Unit) {
        val current = service.capture.value
        if (current.mode == CaptureMode.URL &&
            current.url.isNotBlank() &&
            current.page is PageUiState.Idle &&
            current.preview == null
        ) {
            service.loadUrl(current.url)
        }
    }

    val modeKey = if (textMode) "paste-text" else "web"

    Scaffold(
        containerColor = Paper,
        topBar = {
            PageHeader(title = "添加内容", onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            TextTabs(
                options = listOf("web" to "网页", "paste-text" to "粘贴文本"),
                selectedKey = modeKey,
                onSelect = { key ->
                    val nextTextMode = key == "paste-text"
                    if (nextTextMode != textMode) {
                        textMode = nextTextMode
                        emptyHint = false
                        if (!nextTextMode && service.capture.value.mode == CaptureMode.TEXT) {
                            service.beginUrlCapture(null)
                        }
                    }
                },
                tagPrefix = "capture",
            )
            RuleLine()

            if (!textMode) {
                UrlModeContent(
                    service = service,
                    state = state,
                    context = context,
                    urlText = urlText,
                    onUrlChange = {
                        urlText = it
                        emptyHint = false
                    },
                    emptyHint = emptyHint,
                    onOpen = {
                        if (urlText.isBlank()) {
                            emptyHint = true
                        } else {
                            service.loadUrl(urlText.trim())
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                PrimaryAction(
                    text = if (state.isExtracting) "正在提取…" else "提取正文",
                    onClick = { service.extractPage() },
                    enabled = !state.isExtracting &&
                        (state.url.isNotBlank() || urlText.isNotBlank()),
                    testTag = "capture-extract",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            } else {
                TextComposer(
                    text = text,
                    onTextChange = {
                        text = it
                        emptyHint = false
                    },
                    emptyHint = emptyHint,
                    errorMessage = state.error?.message,
                    modifier = Modifier.weight(1f),
                )
                PrimaryAction(
                    text = "预览",
                    onClick = {
                        if (text.isBlank()) {
                            emptyHint = true
                        } else {
                            service.beginTextCapture(text)
                        }
                    },
                    testTag = "capture-preview",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun UrlModeContent(
    service: ReadingService,
    state: com.cnife.listenreader.service.CaptureState,
    context: android.content.Context,
    urlText: String,
    onUrlChange: (String) -> Unit,
    emptyHint: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showWebView = state.url.isNotBlank() || state.page !is PageUiState.Idle

    Column(modifier = modifier.fillMaxWidth()) {
        UrlRow(url = urlText, onUrlChange = onUrlChange, onOpen = onOpen)
        if (emptyHint) {
            Text(
                text = "请输入网址",
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (showWebView) {
            PageToolbar(
                url = state.url.ifBlank { urlText },
                onBack = { service.pageBack() },
                onRefresh = { service.reloadPage() },
            )
        }
        PageStatus(
            page = state.page,
            isExtracting = state.isExtracting,
            stage = state.extractionStage,
            onRetryPage = { service.reloadPage() },
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            WebViewArea(
                service = service,
                context = context,
                showWebView = showWebView,
            )
        }

        state.extraction?.takeIf { it.needsExpand }?.let { extraction ->
            val description = if (extraction.usedFullPage) {
                "已使用页面全文，可编辑正文后保存。"
            } else {
                buildString {
                    if (extraction.expandRounds > 0) {
                        append("已自动展开 ").append(extraction.expandRounds).append(" 轮。")
                    }
                    extraction.degradedReason?.let { append(it).append("。") }
                    append("可以编辑，或使用页面全文。")
                }
            }
            StatusLine(
                title = if (extraction.usedFullPage) "已使用页面全文" else "正文可能不完整",
                description = description,
                actionLabel = if (extraction.usedFullPage) null else "使用页面全文",
                onAction = if (extraction.usedFullPage) null else ({ service.useFullPage() }),
                actionTag = "capture-use-full-page",
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .testTag("capture-degraded-hint"),
            )
        }

        if (state.page !is PageUiState.Failed) {
            state.error?.let { error ->
                StatusLine(
                    title = "提取失败",
                    description = error.message,
                    actionLabel = "重试",
                    onAction = { service.extractPage() },
                    actionTag = "capture-retry",
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .testTag("capture-error"),
                )
            }
        }
    }
}

@Composable
private fun UrlRow(
    url: String,
    onUrlChange: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .height(48.dp)
            .background(Sunken, RoundedCornerShape(8.dp))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier
                .weight(1f)
                .testTag("capture-url-input"),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onOpen() }),
            decorationBox = { innerTextField ->
                Box {
                    if (url.isEmpty()) {
                        Text(
                            text = "https://…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink3,
                        )
                    }
                    innerTextField()
                }
            },
        )
        QuietAction(
            text = "打开",
            onClick = onOpen,
            testTag = "capture-open",
        )
    }
}

@Composable
private fun PageToolbar(
    url: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(onClick = onBack)
                .testTag("capture-web-back"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "网页后退",
                tint = Ink2,
                modifier = Modifier.size(20.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(onClick = onRefresh)
                .testTag("capture-web-refresh"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "刷新",
                tint = Ink2,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = pageHost(url),
            style = MaterialTheme.typography.bodySmall,
            color = Ink2,
            maxLines = 1,
        )
    }
}

private fun pageHost(url: String): String = runCatching {
    android.net.Uri.parse(url).host
}.getOrNull() ?: url

@Composable
private fun PageStatus(
    page: PageUiState,
    isExtracting: Boolean,
    stage: String?,
    onRetryPage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        when (page) {
            is PageUiState.Loading -> {
                Text(
                    text = "正在加载网页… ${page.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                )
                ProgressLine(
                    progress = page.progress / 100f,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            is PageUiState.NeedsUserAction -> StatusLine(
                title = "需要操作",
                description = "${page.reason}。完成页面操作后点「提取正文」。App 不会自动登录或提交表单。",
                modifier = Modifier.testTag("capture-user-action"),
            )

            is PageUiState.Failed -> StatusLine(
                title = "无法打开",
                description = page.message,
                actionLabel = "重试",
                onAction = onRetryPage,
                actionTag = "capture-retry",
                modifier = Modifier.testTag("capture-error"),
            )

            PageUiState.Idle, PageUiState.Ready -> Unit
        }
        if (isExtracting) {
            Text(
                text = stage ?: "正在提取正文…",
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("capture-extracting"),
            )
            IndeterminateProgressLine(modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun WebViewArea(
    service: ReadingService,
    context: android.content.Context,
    showWebView: Boolean,
) {
    if (!showWebView) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("capture-empty-web"),
            contentAlignment = Alignment.TopStart,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 32.dp)) {
                Text(
                    text = "输入网址，页面会在这里打开。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "提取后进入预览，可以修改标题和正文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink2,
                )
            }
        }
        return
    }
    var available by remember { mutableStateOf(true) }
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag("capture-webview"),
        factory = { ctx ->
            val view = service.createPageView(context)
            if (view == null) {
                available = false
                View(ctx)
            } else {
                view
            }
        },
    )
    if (!available) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "网页视图不可用",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}

@Composable
private fun TextComposer(
    text: String,
    onTextChange: (String) -> Unit,
    emptyHint: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    ) {
        Text(
            text = "正文",
            style = MaterialTheme.typography.bodySmall,
            color = Ink2,
        )
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("capture-text-input"),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 18.sp,
                lineHeight = 32.sp,
                color = Ink,
            ),
            cursorBrush = SolidColor(Accent),
            decorationBox = { innerTextField ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = "把文章或段落粘贴到这里",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 18.sp,
                                lineHeight = 32.sp,
                                color = Ink3,
                            ),
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (emptyHint) {
            Text(
                text = "请先粘贴要朗读的文本",
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Ink2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
