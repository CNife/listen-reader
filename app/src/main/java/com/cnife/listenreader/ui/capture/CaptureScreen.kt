package com.cnife.listenreader.ui.capture

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cnife.listenreader.service.CaptureMode
import com.cnife.listenreader.service.PageUiState
import com.cnife.listenreader.service.ReadingService

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("采集") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!textMode) {
                        IconButton(
                            onClick = { service.pageBack() },
                            modifier = Modifier.testTag("capture-web-back"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "网页后退")
                        }
                        IconButton(onClick = { service.reloadPage() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!textMode) {
                UrlBar(
                    url = urlText,
                    onUrlChange = {
                        urlText = it
                        emptyHint = false
                    },
                    onOpen = {
                        if (urlText.isBlank()) {
                            emptyHint = true
                        } else {
                            service.loadUrl(urlText.trim())
                        }
                    },
                )
                if (emptyHint) {
                    HintText("请输入网址")
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    WebViewArea(
                        service = service,
                        context = context,
                        showWebView = state.url.isNotBlank() || state.page !is PageUiState.Idle,
                    )
                    PageStatusOverlay(state.page, state.isExtracting, state.extractionStage)
                }
                state.extraction?.takeIf { it.needsExpand }?.let { extraction ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HintText(
                            text = buildString {
                                if (extraction.expandRounds > 0) {
                                    append("已自动展开 ").append(extraction.expandRounds).append(" 轮；")
                                }
                                append(extraction.degradedReason ?: "正文可能不完整，可编辑预览或稍后重试展开")
                            },
                            modifier = Modifier.testTag("capture-degraded-hint"),
                        )
                        if (!extraction.usedFullPage) {
                            TextButton(
                                onClick = { service.useFullPage() },
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                                    .testTag("capture-use-full-page"),
                            ) {
                                Text("使用页面全文")
                            }
                        }
                    }
                }
                state.error?.let { error ->
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .testTag("capture-error"),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { service.extractPage() },
                        enabled = !state.isExtracting,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("capture-extract"),
                    ) {
                        Text(if (state.isExtracting) "提取中…" else "提取正文")
                    }
                    TextButton(
                        onClick = {
                            textMode = true
                            emptyHint = false
                        },
                        modifier = Modifier.testTag("capture-paste-text"),
                    ) {
                        Text("粘贴文本")
                    }
                }
            } else {
                TextComposer(
                    text = text,
                    onTextChange = {
                        text = it
                        emptyHint = false
                    },
                    emptyHint = emptyHint,
                    errorMessage = state.error?.message,
                    onPreview = {
                        if (text.isBlank()) {
                            emptyHint = true
                        } else {
                            service.beginTextCapture(text)
                        }
                    },
                    onSwitchUrl = {
                        textMode = false
                        if (state.mode == CaptureMode.TEXT) service.beginUrlCapture(null)
                    },
                )
            }
        }
    }
}

@Composable
private fun UrlBar(
    url: String,
    onUrlChange: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier
                .weight(1f)
                .testTag("capture-url-input"),
            label = { Text("网址") },
            placeholder = { Text("https://…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onOpen() }),
        )
        IconButton(
            onClick = onOpen,
            modifier = Modifier.testTag("capture-open"),
        ) {
            Icon(Icons.Filled.Search, contentDescription = "打开网址")
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
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(
                text = "输入网址开始采集",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun PageStatusOverlay(page: PageUiState, isExtracting: Boolean, stage: String?) {
    when (page) {
        is PageUiState.Loading -> Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("正在加载网页… ${page.progress}%", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { page.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is PageUiState.NeedsUserAction -> UserActionBanner(page.reason)
        is PageUiState.Failed -> HintText(page.message)
        PageUiState.Idle, PageUiState.Ready -> Unit
    }
    if (isExtracting) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stage ?: "正在提取正文…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("capture-extracting"),
            )
        }
    }
}

@Composable
private fun UserActionBanner(reason: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.testTag("capture-user-action"),
            )
            Text(
                text = "请在下方页面内完成操作，然后点「提取正文」。App 不会自动登录或提交表单。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
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
    onPreview: () -> Unit,
    onSwitchUrl: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("粘贴文本", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("capture-text-input"),
            label = { Text("要朗读的文本") },
            placeholder = { Text("把文章或段落粘贴到这里") },
            minLines = 8,
        )
        if (emptyHint) HintText("请先粘贴要朗读的文本")
        errorMessage?.let { HintText(it, error = true) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPreview,
                modifier = Modifier
                    .weight(1f)
                    .testTag("capture-preview"),
            ) {
                Text("预览")
            }
            TextButton(onClick = onSwitchUrl, modifier = Modifier.testTag("capture-switch-url")) {
                Text("网页采集")
            }
        }
    }
}

@Composable
private fun HintText(text: String?, modifier: Modifier = Modifier, error: Boolean = false) {
    text?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
