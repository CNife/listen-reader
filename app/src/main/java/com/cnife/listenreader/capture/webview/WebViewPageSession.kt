package com.cnife.listenreader.capture.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cnife.listenreader.service.ports.PageExtraction
import com.cnife.listenreader.service.ports.PageExtractionException
import com.cnife.listenreader.service.ports.PageSession
import com.cnife.listenreader.service.ports.PageSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * 单实例 WebView 页面会话：加载 URL、共享 CookieManager 登录态、等待 DOM 稳定后注入 L1。
 *
 * - WebView 只在主线程创建；[createView] 用调用方（Activity）的 Context。
 * - 登录/验证码/反爬提示出现时进入 [PageSessionState.NeedsUserAction]，把页面交还用户，
 *   绝不自动登录、提交表单或付费。
 * - 提取脚本：`assets/extract/readability.js` + `assets/extract/l1.js`；模板来自
 *   `assets/extract/templates.json`。
 */
class WebViewPageSession(
    private val appContext: Context,
    private val mainScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : PageSession {

    private val _state = MutableStateFlow<PageSessionState>(PageSessionState.Idle)
    override val state: StateFlow<PageSessionState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var pendingUrl: String? = null
    private var lastUrl: String? = null
    private var stabilizeJob: Job? = null

    private var injectBundle: String? = null
    private var bundleInstalled = false
    private var domStable = true

    override fun createView(context: Context): View {
        checkMainThread()
        webView?.let { return it }
        val view = WebView(context)
        configure(view)
        webView = view
        pendingUrl?.let { url ->
            pendingUrl = null
            loadInternal(url)
        }
        return view
    }

    override fun load(url: String) {
        lastUrl = url
        domStable = true
        bundleInstalled = false
        _state.value = PageSessionState.Loading(0)
        runOnMain {
            val view = webView
            if (view == null) pendingUrl = url else loadInternal(url)
        }
    }

    override fun reload() {
        val view = webView ?: return
        runOnMain {
            domStable = true
            bundleInstalled = false
            _state.value = PageSessionState.Loading(0)
            view.reload()
        }
    }

    override fun goBack(): Boolean {
        val view = webView ?: return false
        if (!view.canGoBack()) return false
        runOnMain { view.goBack() }
        return true
    }

    override suspend fun extract(): PageExtraction = withContext(Dispatchers.Main.immediate) {
        val view = webView ?: throw PageExtractionException("网页视图尚未创建，请重新打开采集页")
        awaitSettled()
        ensureBundle(view)
        val raw = withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
            evaluate("(window.__listenReaderL1 && window.__listenReaderL1(${optionsLiteral()}))")
        } ?: throw PageExtractionException("网页提取超时，请重试")
        parseExtraction(raw)
    }

    /** T6 只做 L1；T7 在这里接入 L3（规则展开）后重新提取。 */
    override suspend fun expandAndExtract(): PageExtraction = extract()

    override fun release() {
        stabilizeJob?.cancel()
        stabilizeJob = null
        val view = webView
        webView = null
        pendingUrl = null
        bundleInstalled = false
        domStable = true
        _state.value = PageSessionState.Idle
        if (view != null) {
            runOnMain {
                view.stopLoading()
                view.loadUrl("about:blank")
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
            }
        }
    }

    // --- 页面状态 ---

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // 登录态共享：第三方 cookie 常见于跳转登录与 iframe 内嵌登录。
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = PageClient()
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    val current = _state.value
                    if (current is PageSessionState.Loading || current is PageSessionState.Idle) {
                        _state.value = PageSessionState.Loading(newProgress)
                    }
                }
            }
        }
    }

    private inner class PageClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            bundleInstalled = false
            domStable = true
            stabilizeJob?.cancel()
            _state.value = PageSessionState.Loading(0)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (url == null || url.startsWith("about:")) return
            val current = _state.value
            if (current is PageSessionState.Idle ||
                current is PageSessionState.NeedsUserAction ||
                current is PageSessionState.Failed
            ) return
            _state.value = PageSessionState.Loading(100)
            startStabilize(view)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame != true) return
            stabilizeJob?.cancel()
            _state.value = PageSessionState.Failed(loadErrorText(error?.errorCode))
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            if (request?.isForMainFrame != true) return
            val code = errorResponse?.statusCode ?: return
            when {
                code == 401 || code == 403 ->
                    _state.value = PageSessionState.NeedsUserAction("页面需要登录或没有访问权限（HTTP $code）")
                code == 429 ->
                    _state.value = PageSessionState.NeedsUserAction("访问过于频繁（HTTP 429），请稍后再试")
                code >= 500 ->
                    _state.value = PageSessionState.Failed("网页服务暂时不可用（HTTP $code）")
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            val scheme = uri.scheme?.lowercase()
            // 站内 http(s) 留在 WebView；电话/邮件等外部 scheme 不交给系统，避免跳出和崩溃。
            return scheme != null && scheme != "http" && scheme != "https" && scheme != "about"
        }
    }

    private fun startStabilize(view: WebView?) {
        stabilizeJob?.cancel()
        stabilizeJob = mainScope.launch {
            val startedAt = SystemClock.uptimeMillis()
            var lastLength = -1
            var lastChangeAt = startedAt
            var stable = false
            while (SystemClock.uptimeMillis() - startedAt < DOM_TIMEOUT_MS) {
                val now = SystemClock.uptimeMillis()
                val snapshot = withTimeoutOrNull(EVALUATE_TIMEOUT_MS) { evaluate(DOM_SNAPSHOT_SCRIPT) }
                val parsed = decodeJsValue(snapshot, DomSnapshot.serializer())
                val ready = parsed?.readyState == "complete"
                val length = parsed?.textLength ?: 0
                if (length != lastLength) lastChangeAt = now
                lastLength = length
                // readyState 完成 + 一段时间内文本没有变化，才认为 DOM 静默。
                if (ready && length > 0 && now - lastChangeAt >= QUIET_PERIOD_MS) {
                    stable = true
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
            domStable = stable

            val detection = decodeJsValue(
                withTimeoutOrNull(EVALUATE_TIMEOUT_MS) { evaluate(DETECTION_SCRIPT) },
                UserActionSignal.serializer(),
            )
            val reason = detection?.let { userActionReason(it) }
            _state.value = when {
                reason != null -> PageSessionState.NeedsUserAction(reason)
                detection != null && detection.textLength < 40 ->
                    PageSessionState.Failed("页面没有可提取的文本，请确认链接是否正确")
                else -> PageSessionState.Ready
            }
        }
    }

    private fun userActionReason(signal: UserActionSignal): String? {
        if (signal.hit != null) {
            return when {
                signal.hit.contains("密码") || signal.hit == "password" ||
                    signal.hit == LOGIN_FORM_MARKER -> "页面需要登录，请在下方登录后点「提取正文」"
                signal.hit.contains("验证") || signal.hit.contains("captcha", true) ->
                    "页面要求验证码或人机验证，请在下方完成验证后点「提取正文」"
                signal.hit.contains("环境异常") ->
                    "页面提示访问环境异常，请在下方按页面提示处理后点「提取正文」"
                signal.hit.contains("频繁") ->
                    "访问过于频繁，请稍后再试，或按页面提示处理"
                signal.hit.contains("登录") ->
                    "页面需要登录，请在下方登录后点「提取正文」"
                else -> "页面需要人工处理：${signal.hit}"
            }
        }
        if (signal.iframes > 0 && signal.textLength < 200) {
            return "页面正文可能在第三方 iframe 中，请在下方处理或改用「粘贴文本」"
        }
        return null
    }

    private suspend fun awaitSettled() {
        val current = _state.value
        when (current) {
            is PageSessionState.NeedsUserAction -> throw PageExtractionException(current.reason)
            is PageSessionState.Failed -> throw PageExtractionException(current.message)
            PageSessionState.Idle -> throw PageExtractionException("请先打开网页")
            else -> Unit
        }
        val settled = withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
            state.first { it !is PageSessionState.Loading }
        }
        when (settled) {
            is PageSessionState.NeedsUserAction -> throw PageExtractionException(settled.reason)
            is PageSessionState.Failed -> throw PageExtractionException(settled.message)
            null -> Unit // 超时后按当前 DOM 尽力提取
            else -> Unit
        }
    }

    // --- 脚本注入与解析 ---

    private suspend fun ensureBundle(view: WebView) {
        if (bundleInstalled) return
        val script = injectBundle ?: assetsScript().also { injectBundle = it }
        withTimeoutOrNull(EVALUATE_TIMEOUT_MS) { evaluate(script) }
        bundleInstalled = true
    }

    private suspend fun assetsScript(): String = withContext(Dispatchers.IO) {
        val readability = appContext.assets.open("extract/readability.js").bufferedReader().use { it.readText() }
        val l1 = appContext.assets.open("extract/l1.js").bufferedReader().use { it.readText() }
        readability + "\n" + l1
    }

    private suspend fun optionsLiteral(): String {
        val templatesJson = withContext(Dispatchers.IO) {
            appContext.assets.open("extract/templates.json").bufferedReader().use { it.readText() }
        }
        val options = buildJsonObject {
            put("url", lastUrl.orEmpty())
            put("domStable", domStable)
            put("templates", Json.parseToJsonElement(templatesJson))
        }
        // evaluateJavascript 需要 JS 字符串字面量。
        return Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(options.toString()))
    }

    private fun parseExtraction(raw: String?): PageExtraction {
        if (raw.isNullOrBlank() || raw == "null") {
            throw PageExtractionException("网页脚本没有返回提取结果")
        }
        val decoded = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
            ?: throw PageExtractionException("无法解析网页提取结果")
        val payloadText = (decoded as? JsonPrimitive)?.contentOrNull ?: raw
        val payload = runCatching { json.decodeFromString<L1Payload>(payloadText) }.getOrNull()
            ?: throw PageExtractionException("无法解析网页提取结果")
        if (!payload.error.isNullOrBlank()) {
            throw PageExtractionException("正文提取失败：${payload.error}")
        }
        return PageExtraction(
            title = payload.title.trim(),
            byline = payload.byline?.trim()?.ifBlank { null },
            publishedAt = payload.publishedAt?.trim()?.ifBlank { null },
            markdown = payload.markdown,
            text = payload.text,
            pageText = payload.pageText,
            extractor = payload.extractor.ifBlank { "l1" },
            score = payload.score,
            confidence = payload.confidence,
            needsExpand = payload.needsExpand,
            degradedReason = payload.degradedReason?.ifBlank { null },
            html = payload.html?.ifBlank { null },
        )
    }

    /** evaluateJavascript 会把 JS 字符串结果再 JSON 编码一次，这里统一解两层。 */
    private fun <T> decodeJsValue(raw: String?, serializer: KSerializer<T>): T? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val payload = (element as? JsonPrimitive)?.contentOrNull ?: raw
        return runCatching { json.decodeFromString(serializer, payload) }.getOrNull()
    }

    private suspend fun evaluate(script: String): String? = suspendCancellableCoroutine { continuation ->
        val view = webView
        if (view == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        view.evaluateJavascript(script) { value ->
            if (continuation.isActive) continuation.resume(value)
        }
    }

    // --- 工具 ---

    private fun loadInternal(url: String) {
        bundleInstalled = false
        domStable = true
        _state.value = PageSessionState.Loading(0)
        webView?.loadUrl(url)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "WebView 只能在主线程创建"
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun loadErrorText(code: Int?): String = when (code) {
        android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> "无法解析网址，请检查网络或链接"
        android.webkit.WebViewClient.ERROR_CONNECT,
        android.webkit.WebViewClient.ERROR_TIMEOUT -> "网页加载超时或连接失败"
        android.webkit.WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "不支持的网址类型"
        android.webkit.WebViewClient.ERROR_FILE_NOT_FOUND,
        android.webkit.WebViewClient.ERROR_FILE -> "网页资源无法访问"
        android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "网页证书校验失败"
        else -> "网页加载失败，请检查网络后重试"
    }

    private companion object {
        const val POLL_INTERVAL_MS = 150L
        const val QUIET_PERIOD_MS = 600L
        const val DOM_TIMEOUT_MS = 8_000L
        const val SETTLE_TIMEOUT_MS = 12_000L
        const val EVALUATE_TIMEOUT_MS = 5_000L
        const val LOGIN_FORM_MARKER = "__login_form__"

        val json = Json { ignoreUnknownKeys = true }

        const val DOM_SNAPSHOT_SCRIPT =
            "(function(){var b=document.body;return JSON.stringify({" +
                "readyState:document.readyState," +
                "textLength:b?((b.innerText||'').length):0});})()"

        /**
         * 只识别明确的拦截提示；裸「登录」按钮不算，避免普通文章误判。
         * 「请登录/扫码登录」仅在页面文本很短时才算，因为这类页面通常是登录墙。
         */
        const val DETECTION_SCRIPT =
            "(function(){" +
                "var body=document.body;var text=body?((body.innerText||'').replace(/\\s+/g,' ')):'';" +
                "var strong=['验证码','人机验证','安全验证','滑动验证','拖动滑块','请完成验证','环境异常','访问异常'," +
                "'登录后查看','登录后继续','captcha','verify you are human','are you a robot','sign in to continue'," +
                "'login required','请求过于频繁','操作过于频繁'];" +
                "var weak=['请登录','扫码登录','password'];var hit=null;" +
                "for(var i=0;i<strong.length;i++){if(text.indexOf(strong[i])>=0){hit=strong[i];break;}}" +
                "if(!hit&&text.length<1500){for(var j=0;j<weak.length;j++){if(text.indexOf(weak[j])>=0){hit=weak[j];break;}}}" +
                "if(!hit&&document.querySelector('input[type=password]')&&text.length<1500){hit='__login_form__';}" +
                "return JSON.stringify({textLength:text.length,iframes:document.querySelectorAll('iframe').length,hit:hit});" +
                "})()"
    }
}

@Serializable
private data class DomSnapshot(
    val readyState: String = "",
    val textLength: Int = 0,
)

@Serializable
private data class UserActionSignal(
    val textLength: Int = 0,
    val iframes: Int = 0,
    val hit: String? = null,
)

@Serializable
private data class L1Payload(
    val title: String = "",
    val byline: String? = null,
    val publishedAt: String? = null,
    val markdown: String = "",
    val text: String = "",
    val pageText: String = "",
    val extractor: String = "",
    val score: Double = 0.0,
    val confidence: Double = 0.0,
    val needsExpand: Boolean = false,
    val degradedReason: String? = null,
    val html: String? = null,
    val error: String? = null,
)
