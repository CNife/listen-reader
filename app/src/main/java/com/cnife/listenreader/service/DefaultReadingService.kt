package com.cnife.listenreader.service

import android.content.Context
import android.view.View
import com.cnife.listenreader.capture.ShareText
import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.core.RuleSegmenter
import com.cnife.listenreader.core.UiError
import com.cnife.listenreader.model.AudioClip
import com.cnife.listenreader.model.AudioTrack
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.model.LibraryFilter
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.LlmDefaults
import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.Segment
import com.cnife.listenreader.model.SegmentationSource
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.ports.AuditionPlayer
import com.cnife.listenreader.service.ports.CleanResult
import com.cnife.listenreader.service.ports.LibraryStore
import com.cnife.listenreader.service.ports.PageExtraction
import com.cnife.listenreader.service.ports.PageExtractionException
import com.cnife.listenreader.service.ports.PageSession
import com.cnife.listenreader.service.ports.PageSessionState
import com.cnife.listenreader.service.ports.PlaybackEngine
import com.cnife.listenreader.service.ports.PlaybackEngineState
import com.cnife.listenreader.service.ports.ScriptCleaner
import com.cnife.listenreader.service.ports.SettingsStore
import com.cnife.listenreader.service.ports.SpeechSynthesizer
import com.cnife.listenreader.service.ports.TimeProvider
import com.cnife.listenreader.tts.SpeechException
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 应用级协调器：资料库、采集、设置与逐 Segment 的 TTS / 播放编排。
 * 播放规则：当前段按需合成并缓存；引擎结束自动推进；倍速只作用播放器。
 */
class DefaultReadingService(
    private val pageSession: PageSession,
    private val scriptCleaner: ScriptCleaner,
    private val speechSynthesizer: SpeechSynthesizer,
    private val libraryStore: LibraryStore,
    private val playbackEngine: PlaybackEngine,
    private val settingsStore: SettingsStore,
    private val timeProvider: TimeProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val auditionPlayer: AuditionPlayer = com.cnife.listenreader.service.ports.NoopAuditionPlayer,
    private val previewDir: File? = null,
) : ReadingService {

    private val _library = MutableStateFlow(LibraryState())
    override val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _player = MutableStateFlow(PlayerState())
    override val player: StateFlow<PlayerState> = _player.asStateFlow()

    private val _capture = MutableStateFlow(CaptureState())
    override val capture: StateFlow<CaptureState> = _capture.asStateFlow()

    private val _settings = MutableStateFlow(SettingsState())
    override val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    private var allItems: List<LibraryItem> = emptyList()

    /** 最近打开的 Resource，来源是持久化的 `lastOpenedResourceId()` 查询结果。 */
    private var lastOpenedId: String? = null

    /** 每次开始新的采集会话自增，用于丢弃已过期会话的异步保存结果。 */
    private var captureEpoch = 0

    private var currentResource: Resource? = null
    private var currentSegments: List<Segment> = emptyList()
    private var currentClips: MutableMap<Int, AudioClip> = mutableMapOf()
    private var activeTrack: AudioTrack? = null

    /** 用户意图：是否应该正在播放（区别于引擎瞬时状态）。 */
    private var wantPlaying = false
    private var endedHandledFor: Int? = null
    private var playbackJob: Job? = null

    /** 每个 Segment 一把锁：同一句不会重复请求，不同句可以并行合成。 */
    private val synthLocks = mutableMapOf<Int, Mutex>()

    /** 睡眠定时倒计时；到点暂停并清空。 */
    private var sleepTimerJob: Job? = null

    /** 服务主动 prepare/play 期间，引擎的瞬时暂停不算用户从通知栏暂停。 */
    private var pendingEngineStart = false

    /** 进行中的预取任务，按 Segment 索引去重；窗口移动时旧任务继续跑完。 */
    private val prefetchJobs = mutableMapOf<Int, Job>()

    /** 预取请求的并发上限；前台合成不受它约束。 */
    private val prefetchSemaphore = Semaphore(MAX_CONCURRENT_PREFETCH)

    /** 切换 AudioTrack 时自增，用于丢弃旧轨上已完成但已过期的合成结果。 */
    private var trackEpoch = 0

    private var auditionJob: Job? = null

    /** 每次 L2 运行自增，用于丢弃已过期的清洗结果。 */
    private var l2Epoch = 0
    private var l2Job: Job? = null

    /** 最近一次 LLM 配置写入，供 testLlm 等待保存完成。 */
    private var llmConfigSaveJob: Job? = null

    /** 最近一次网页提取的原始快照，保存时写入 Source（Markdown + HTML）。 */
    private var pendingMarkdown: String? = null
    private var pendingHtml: String? = null

    /** 最近一次提取拿到的页面全文，供「使用页面全文」兜底。 */
    private var pendingPageText: String? = null

    init {
        scope.launch {
            settingsStore.settings.collect { appSettings ->
                _settings.update {
                    it.copy(
                        themeMode = appSettings.themeMode,
                        ttsModel = appSettings.ttsModel,
                        voice = appSettings.voice,
                        prefetchCount = appSettings.prefetchCount,
                        llmEndpoint = appSettings.llmEndpoint,
                        llmModel = appSettings.llmModel,
                        stepFunKeyConfigured = appSettings.stepFunKeyConfigured,
                        llmKeyConfigured = appSettings.llmKeyConfigured,
                    )
                }
            }
        }
        scope.launch {
            libraryStore.observeItems().collect { items ->
                allItems = items
                lastOpenedId = readLastOpenedId()
                publishLibrary(isLoading = false, error = null)
            }
        }
        scope.launch {
            playbackEngine.state.collect { state -> onEngineState(state) }
        }
        scope.launch {
            pageSession.state.collect { pageState ->
                if (_capture.value.mode == CaptureMode.URL) {
                    _capture.update { it.copy(page = pageState.toUiState()) }
                }
            }
        }
        scope.launch {
            val resourceId = runCatching { libraryStore.lastOpenedResourceId() }.getOrNull()
            if (resourceId != null && _player.value.resourceId == null) {
                runCatching { loadResource(resourceId, markOpened = false) }
            }
        }
    }

    // --- 资料库 ---

    override fun refreshLibrary() {
        _library.update { it.copy(isLoading = true, error = null) }
        scope.launch {
            try {
                val items = libraryStore.observeItems().first()
                allItems = items
                lastOpenedId = readLastOpenedId()
                publishLibrary(isLoading = false, error = null)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                publishLibrary(
                    isLoading = false,
                    error = UiError("资料库加载失败，请稍后重试", ErrorKind.UNKNOWN, retryable = true),
                )
            }
        }
    }

    override fun setLibraryQuery(query: String) {
        _library.update { it.copy(query = query) }
        publishLibrary(isLoading = false, error = null)
    }

    override fun setLibraryFilter(filter: LibraryFilter) {
        _library.update { it.copy(filter = filter) }
        publishLibrary(isLoading = false, error = null)
    }

    override fun openResource(resourceId: String) {
        scope.launch {
            val loaded = try {
                loadResource(resourceId, markOpened = true)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _library.update {
                    it.copy(
                        error = UiError("打开失败，请稍后重试", ErrorKind.UNKNOWN, retryable = true),
                    )
                }
                return@launch
            }
            if (!loaded) {
                _library.update {
                    it.copy(error = UiError("找不到该资源", ErrorKind.UNKNOWN))
                }
            } else {
                lastOpenedId = readLastOpenedId()
                publishLibrary(isLoading = false, error = null)
            }
        }
    }

    override fun deleteResource(resourceId: String) {
        scope.launch {
            val existing = try {
                libraryStore.getResource(resourceId)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                reportDeleteFailure()
                return@launch
            }
            if (existing == null) {
                _library.update {
                    it.copy(error = UiError("该内容已不存在，可能已被删除", ErrorKind.UNKNOWN))
                }
                return@launch
            }
            try {
                libraryStore.deleteResource(resourceId)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                reportDeleteFailure()
                return@launch
            }
            if (_player.value.resourceId == resourceId) closePlayer()
            allItems = runCatching { libraryStore.observeItems().first() }
                .getOrDefault(allItems.filterNot { it.id == resourceId })
            lastOpenedId = readLastOpenedId()
            publishLibrary(isLoading = false, error = null)
        }
    }

    private fun reportDeleteFailure() {
        _library.update {
            it.copy(error = UiError("删除失败，请稍后重试", ErrorKind.UNKNOWN, retryable = true))
        }
    }

    // --- Capture / 预览 ---

    override fun beginUrlCapture(url: String?) {
        captureEpoch++
        invalidateL2()
        pendingMarkdown = null
        pendingHtml = null
        pendingPageText = null
        val requested = url?.trim().orEmpty()
        _capture.value = CaptureState(
            mode = CaptureMode.URL,
            url = requested,
            page = PageUiState.Idle,
            sessionId = _capture.value.sessionId + 1,
        )
        if (requested.isNotEmpty() && !openUrl(requested)) {
            _capture.update {
                it.copy(error = UiError("仅支持 http/https 网址", ErrorKind.PAGE))
            }
        }
    }

    override fun beginTextCapture(text: String) {
        captureEpoch++
        invalidateL2()
        pendingMarkdown = null
        pendingHtml = null
        pendingPageText = null
        val sessionId = _capture.value.sessionId + 1
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) {
            _capture.value = CaptureState(
                mode = CaptureMode.TEXT,
                url = text,
                page = PageUiState.Idle,
                error = UiError("请先粘贴要朗读的文本", ErrorKind.UNKNOWN),
                sessionId = sessionId,
            )
            return
        }
        val (title, body) = splitTextTitle(normalized)
        val drafts = RuleSegmenter.segment(speechTitle(title), body)
        val configured = scriptCleaner.isConfigured()
        val revision = _capture.value.previewRevision + 1
        _capture.value = CaptureState(
            mode = CaptureMode.TEXT,
            url = text,
            page = PageUiState.Idle,
            extraction = ExtractionInfo(
                title = title,
                extractor = TEXT_EXTRACTOR,
                score = 1.0,
                confidence = 1.0,
                needsExpand = false,
            ),
            preview = PreviewState(
                title = title,
                body = body,
                charCount = body.length,
                segmentDrafts = drafts,
                segmentation = SegmentationSource.RULE,
                l2Status = if (configured) L2Status.RUNNING else L2Status.NOT_CONFIGURED,
                l2Error = if (configured) null else NO_LLM_MESSAGE,
                sourceKind = SourceKind.TEXT,
                sourceLabel = TEXT_SOURCE_LABEL,
                extractor = TEXT_EXTRACTOR,
                score = 1.0,
            ),
            sessionId = sessionId,
            previewRevision = revision,
        )
        if (configured) {
            startL2(captureEpoch, title, body)
        }
    }

    override fun handleShareIntent(text: String) {
        val url = ShareText.firstUrl(text)
        if (url != null) beginUrlCapture(url) else beginTextCapture(text)
    }

    override fun loadUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _capture.update {
                it.copy(url = "", page = PageUiState.Idle, error = UiError("请输入网址", ErrorKind.PAGE))
            }
            return
        }
        if (!openUrl(trimmed)) {
            _capture.update {
                it.copy(url = trimmed, error = UiError("仅支持 http/https 网址", ErrorKind.PAGE))
            }
        }
    }

    /** 校验并开始加载网址；返回是否合法。 */
    private fun openUrl(raw: String): Boolean {
        val normalized = ShareText.normalizeUrl(raw) ?: return false
        captureEpoch++
        invalidateL2()
        pendingMarkdown = null
        pendingHtml = null
        pendingPageText = null
        _capture.update {
            it.copy(
                mode = CaptureMode.URL,
                url = normalized,
                page = PageUiState.Loading(0),
                extraction = null,
                preview = null,
                isExtracting = false,
                error = null,
            )
        }
        pageSession.load(normalized)
        return true
    }

    override fun reloadPage() {
        val state = _capture.value
        if (state.mode != CaptureMode.URL || state.url.isBlank()) return
        pendingMarkdown = null
        pendingHtml = null
        pendingPageText = null
        _capture.update {
            it.copy(page = PageUiState.Loading(0), error = null, extraction = null)
        }
        pageSession.reload()
    }

    override fun pageBack(): Boolean {
        if (_capture.value.mode != CaptureMode.URL) return false
        return pageSession.goBack()
    }

    override fun createPageView(context: Context): View? = try {
        pageSession.createView(context)
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        _capture.update {
            it.copy(
                page = PageUiState.Failed("网页视图不可用，请返回后重试"),
                error = UiError("网页视图不可用，请返回后重试", ErrorKind.PAGE, retryable = true),
            )
        }
        null
    }

    override fun releasePageView() {
        pageSession.release()
        if (_capture.value.mode == CaptureMode.URL) {
            _capture.update { it.copy(page = PageUiState.Idle) }
        }
    }

    override fun extractPage() {
        val state = _capture.value
        if (state.mode != CaptureMode.URL) {
            _capture.update {
                it.copy(error = UiError("请先打开网页", ErrorKind.PAGE))
            }
            return
        }
        if (state.url.isBlank()) {
            _capture.update {
                it.copy(error = UiError("请输入网址", ErrorKind.PAGE))
            }
            return
        }
        when (val page = state.page) {
            is PageUiState.NeedsUserAction -> {
                _capture.update { it.copy(error = UiError(page.reason, ErrorKind.PAGE, retryable = true)) }
                return
            }
            is PageUiState.Failed -> {
                _capture.update { it.copy(error = UiError(page.message, ErrorKind.PAGE, retryable = true)) }
                return
            }
            else -> Unit
        }
        if (state.isExtracting) return
        val epoch = captureEpoch
        _capture.update {
            it.copy(isExtracting = true, error = null, extractionStage = "正在提取正文…")
        }
        scope.launch {
            val progressJob = launch {
                pageSession.expandProgress.collect { message ->
                    if (message != null) {
                        _capture.update { current ->
                            if (current.isExtracting) current.copy(extractionStage = message) else current
                        }
                    }
                }
            }
            try {
                // expandAndExtract 内部先跑 L1；只有 needsExpand 时才做 L3 规则展开。
                val extraction = pageSession.expandAndExtract()
                if (epoch != captureEpoch) return@launch
                applyPageExtraction(extraction)
            } catch (c: CancellationException) {
                throw c
            } catch (e: PageExtractionException) {
                if (epoch == captureEpoch) {
                    _capture.update {
                        it.copy(
                            isExtracting = false,
                            extractionStage = null,
                            error = UiError(e.message ?: "无法提取正文", ErrorKind.PAGE, retryable = true),
                        )
                    }
                }
            } catch (t: Throwable) {
                if (epoch == captureEpoch) {
                    _capture.update {
                        it.copy(
                            isExtracting = false,
                            extractionStage = null,
                            error = UiError("正文提取失败，请稍后重试", ErrorKind.PAGE, retryable = true),
                        )
                    }
                }
            } finally {
                progressJob.cancel()
            }
        }
    }

    private fun applyPageExtraction(extraction: PageExtraction) {
        val url = _capture.value.url
        val title = extraction.title.trim().ifBlank { ShareText.sourceLabel(url) }
        val body = stripLeadingTitle(title, extraction.text.ifBlank { extraction.markdown }.trim())
        if (body.isBlank()) {
            _capture.update {
                it.copy(
                    isExtracting = false,
                    extractionStage = null,
                    page = PageUiState.NeedsUserAction("没有提取到正文，请在页面内确认正文可见或改用「粘贴文本」"),
                    error = UiError("没有提取到正文，请确认页面正文可见", ErrorKind.PAGE, retryable = true),
                )
            }
            return
        }
        pendingMarkdown = extraction.markdown.ifBlank { null }
        pendingHtml = extraction.html
        pendingPageText = extraction.pageText.trim().ifBlank { null }
        val configured = scriptCleaner.isConfigured()
        val preview = PreviewState(
            title = title,
            body = body,
            charCount = body.length,
            segmentDrafts = RuleSegmenter.segment(title, body),
            segmentation = SegmentationSource.RULE,
            l2Status = if (configured) L2Status.RUNNING else L2Status.NOT_CONFIGURED,
            l2Error = if (configured) null else NO_LLM_MESSAGE,
            sourceKind = SourceKind.URL,
            sourceLabel = ShareText.sourceLabel(url),
            extractor = extraction.extractor,
            score = extraction.score,
            sourceUrl = url,
            byline = extraction.byline,
            publishedAt = extraction.publishedAt,
        )
        _capture.update {
            it.copy(
                page = PageUiState.Ready,
                isExtracting = false,
                extractionStage = null,
                error = null,
                savedResourceId = null,
                extraction = ExtractionInfo(
                    title = title,
                    extractor = extraction.extractor,
                    score = extraction.score,
                    confidence = extraction.confidence,
                    needsExpand = extraction.needsExpand,
                    degradedReason = extraction.degradedReason,
                    expandRounds = extraction.expandRounds,
                ),
                preview = preview,
                previewRevision = it.previewRevision + 1,
            )
        }
        if (configured) startL2(captureEpoch, title, body)
    }

    /** 有些提取器会把标题也放进正文；避免预览里重复一句标题。 */
    private fun stripLeadingTitle(title: String, body: String): String {
        if (title.isBlank() || !body.startsWith(title)) return body
        val rest = body.substring(title.length)
        if (rest.isEmpty()) return title
        if (!rest.first().isWhitespace()) return body
        return rest.trimStart().ifEmpty { title }
    }

    override fun useFullPage() {
        val state = _capture.value
        if (state.mode != CaptureMode.URL) return
        if (state.isExtracting) return
        val pageText = pendingPageText?.trim().orEmpty()
        if (pageText.isBlank()) {
            _capture.update {
                it.copy(error = UiError("还没有可用的页面全文，请先提取正文", ErrorKind.PAGE, retryable = true))
            }
            return
        }
        val title = state.extraction?.title?.trim().takeUnless { it.isNullOrBlank() }
            ?: ShareText.sourceLabel(state.url)
        val body = stripLeadingTitle(title, pageText)
        if (body.isBlank()) {
            _capture.update {
                it.copy(error = UiError("页面全文为空，请在页面内确认正文可见", ErrorKind.PAGE, retryable = true))
            }
            return
        }
        invalidateL2()
        val configured = scriptCleaner.isConfigured()
        val previous = state.preview
        val preview = PreviewState(
            title = title,
            body = body,
            charCount = body.length,
            segmentDrafts = RuleSegmenter.segment(title, body),
            segmentation = SegmentationSource.RULE,
            l2Status = if (configured) L2Status.RUNNING else L2Status.NOT_CONFIGURED,
            l2Error = if (configured) null else NO_LLM_MESSAGE,
            sourceKind = SourceKind.URL,
            sourceLabel = ShareText.sourceLabel(state.url),
            extractor = "page-text",
            score = null,
            sourceUrl = state.url,
            byline = previous?.byline,
            publishedAt = previous?.publishedAt,
        )
        _capture.update {
            it.copy(
                isExtracting = false,
                extractionStage = null,
                error = null,
                savedResourceId = null,
                extraction = ExtractionInfo(
                    title = title,
                    extractor = "page-text",
                    score = 0.3,
                    confidence = 0.3,
                    needsExpand = true,
                    usedFullPage = true,
                    degradedReason = "已使用页面全文，可能包含导航、页脚或推荐内容",
                    expandRounds = state.extraction?.expandRounds ?: 0,
                ),
                preview = preview,
                previewRevision = it.previewRevision + 1,
            )
        }
        if (configured) startL2(captureEpoch, title, body)
    }

    override fun retryL2() {
        val preview = _capture.value.preview ?: return
        invalidateL2()
        if (!scriptCleaner.isConfigured()) {
            _capture.update { state ->
                val current = state.preview ?: return@update state
                state.copy(
                    preview = current.copy(
                        segmentDrafts = RuleSegmenter.segment(speechTitle(current.title), current.body),
                        segmentation = SegmentationSource.RULE,
                        l2Status = L2Status.NOT_CONFIGURED,
                        l2Error = NO_LLM_MESSAGE,
                    ),
                )
            }
            return
        }
        startL2(captureEpoch, preview.title, preview.body)
    }

    override fun updatePreviewTitle(title: String) {
        invalidateL2()
        _capture.update { state ->
            val preview = state.preview ?: return@update state
            state.copy(preview = rebuildPreview(preview, title = title))
        }
    }

    override fun updatePreviewBody(body: String) {
        invalidateL2()
        _capture.update { state ->
            val preview = state.preview ?: return@update state
            state.copy(preview = rebuildPreview(preview, body = body))
        }
    }

    override fun saveCapture(): String? {
        if (_capture.value.isSaving) return null
        val preview = _capture.value.preview
        if (preview == null) {
            _capture.update {
                it.copy(error = UiError("没有可保存的预览内容", ErrorKind.UNKNOWN))
            }
            return null
        }
        val sourceKind = preview.sourceKind
        val sourceUrl = if (sourceKind == SourceKind.URL) {
            preview.sourceUrl ?: _capture.value.url.ifBlank { null }
        } else {
            null
        }
        val title = preview.title.trim().ifBlank {
            if (sourceKind == SourceKind.URL) ShareText.sourceLabel(sourceUrl.orEmpty()) else TEXT_SOURCE_LABEL
        }
        val body = preview.body.trim()
        // 保存当前预览里的分段：L2 成功时是 LLM 分段，失败/未配置时是规则分段。
        val drafts = preview.segmentDrafts.ifEmpty { RuleSegmenter.segment(speechTitle(title), body) }
        if (drafts.isEmpty()) {
            _capture.update {
                it.copy(error = UiError("正文为空，无法保存", ErrorKind.UNKNOWN))
            }
            return null
        }
        val resourceId = UUID.randomUUID().toString()
        val epoch = captureEpoch
        _capture.update { it.copy(isSaving = true, error = null, savedResourceId = null) }
        scope.launch {
            try {
                val now = timeProvider.now()
                val resource = Resource(
                    id = resourceId,
                    title = title,
                    sourceKind = sourceKind,
                    sourceUrl = sourceUrl,
                    byline = preview.byline,
                    publishedAt = preview.publishedAt,
                    extractor = preview.extractor
                        ?: if (sourceKind == SourceKind.URL) "l1" else TEXT_EXTRACTOR,
                    snapshotPath = null,
                    currentSegmentIndex = 0,
                    currentPositionMs = 0,
                    activeTrackId = null,
                    createdAt = now,
                    lastOpenedAt = 0,
                )
                // 网页快照保存 L1 Markdown（用户可编辑正文不覆盖原始提取），HTML 可选。
                val snapshotText = if (sourceKind == SourceKind.URL) {
                    pendingMarkdown ?: body
                } else {
                    body
                }
                val snapshotHtml = if (sourceKind == SourceKind.URL) pendingHtml else null
                libraryStore.createResource(resource, snapshotText, snapshotHtml, drafts)
                if (epoch == captureEpoch) {
                    _capture.update {
                        it.copy(isSaving = false, savedResourceId = resourceId)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (epoch == captureEpoch) {
                    _capture.update {
                        it.copy(
                            isSaving = false,
                            savedResourceId = null,
                            error = UiError("保存失败，请稍后重试", ErrorKind.UNKNOWN, retryable = true),
                        )
                    }
                }
            }
        }
        return resourceId
    }

    override fun discardCapture() {
        captureEpoch++
        invalidateL2()
        pendingMarkdown = null
        pendingHtml = null
        pendingPageText = null
        _capture.value = CaptureState(sessionId = _capture.value.sessionId + 1)
    }

    // --- 播放 ---

    override fun play() {
        val state = _player.value
        if (state.resourceId == null || state.segments.isEmpty()) return
        wantPlaying = true
        _player.update { it.copy(isPlaying = true, error = null) }
        startEnginePlay(state.currentIndex, state.positionInSegmentMs)
    }

    override fun pause() {
        wantPlaying = false
        pendingEngineStart = false
        _player.update { it.copy(isPlaying = false) }
        playbackEngine.pause()
        val state = _player.value
        persistProgress(state.currentIndex, state.positionInSegmentMs)
    }

    override fun togglePlay() {
        if (_player.value.isPlaying) pause() else play()
    }

    override fun nextSegment() {
        val state = _player.value
        if (state.segments.isEmpty()) return
        switchTo(state.currentIndex + 1, autoPlay = wantPlaying)
    }

    override fun previousSegment() {
        val state = _player.value
        if (state.segments.isEmpty()) return
        switchTo(state.currentIndex - 1, autoPlay = wantPlaying)
    }

    override fun seekToSegment(index: Int) {
        val state = _player.value
        if (state.resourceId == null || state.segments.isEmpty()) return
        switchTo(index, autoPlay = wantPlaying)
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _player.update { it.copy(speed = clamped) }
        playbackEngine.setSpeed(clamped)
        persistSettings { settingsStore.setPlaybackSpeed(clamped) }
    }

    override fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        val totalMs = minutes?.takeIf { it > 0 }?.let { it * 60_000L }
        if (totalMs == null) {
            _player.update { it.copy(sleepTimerMinutes = null, sleepTimerRemainingMs = null) }
            return
        }
        _player.update { it.copy(sleepTimerMinutes = minutes, sleepTimerRemainingMs = totalMs) }
        sleepTimerJob = scope.launch {
            var remainingMs = totalMs
            while (remainingMs > 0) {
                delay(SLEEP_TIMER_TICK_MS)
                remainingMs -= SLEEP_TIMER_TICK_MS
                _player.update { it.copy(sleepTimerRemainingMs = remainingMs.coerceAtLeast(0)) }
            }
            pause()
            _player.update { it.copy(sleepTimerMinutes = null, sleepTimerRemainingMs = null) }
        }
    }

    override fun setVoice(voiceId: String) {
        _settings.update { it.copy(voice = voiceId) }
        persistSettings { settingsStore.setVoice(voiceId) }
        switchTrack(model = _settings.value.ttsModel, voice = voiceId)
    }

    override fun setModel(modelId: String) {
        _settings.update { it.copy(ttsModel = modelId) }
        persistSettings { settingsStore.setTtsModel(modelId) }
        switchTrack(model = modelId, voice = _settings.value.voice)
    }

    /**
     * 为当前 Resource 切到与 [model]/[voice] 匹配的 AudioTrack：已有同配置轨则复用，
     * 否则新建；旧轨与文件保留，由设置页清理。切轨后从当前 Segment 重新合成，
     * 并恢复原来的播放位置与播放态。
     */
    private fun switchTrack(model: String, voice: String) {
        val resourceId = _player.value.resourceId ?: return
        scope.launch {
            if (_player.value.resourceId != resourceId) return@launch
            val existing = runCatching { libraryStore.getTracks(resourceId) }
                .getOrDefault(emptyList())
                .firstOrNull { it.model == model && it.voice == voice }
            val target = existing ?: AudioTrack(
                id = UUID.randomUUID().toString(),
                resourceId = resourceId,
                model = model,
                voice = voice,
                createdAt = timeProvider.now(),
            ).also { track -> runCatching { libraryStore.createTrack(track) } }
            if (activeTrack?.id == target.id) return@launch

            cancelPrefetch()
            cancelPlaybackJob()
            trackEpoch++
            runCatching { libraryStore.setActiveTrack(resourceId, target.id) }
            activeTrack = target
            currentClips = runCatching { libraryStore.getClips(target.id) }
                .getOrDefault(emptyList())
                .associateBy { it.segmentIndex }
                .toMutableMap()

            val wasPlaying = wantPlaying
            val index = _player.value.currentIndex
            val positionMs = _player.value.positionInSegmentMs
            endedHandledFor = null
            _player.update { state ->
                state.copy(
                    segments = state.segments.map { segment ->
                        val clip = currentClips[segment.index]
                        segment.copy(
                            status = clip?.status ?: ClipStatus.PENDING,
                            durationMs = clip?.durationMs ?: 0L,
                        )
                    },
                    activeTrack = AudioTrackInfo(target.id, target.model, target.voice),
                    error = null,
                    isPreparing = false,
                )
            }
            refreshTotalDuration()

            val cached = currentClips[index]?.takeIf { it.isReadyFile() }?.filePath
            if (cached != null) {
                // 切轨时的 prepare 也是服务主动发起的：Ready(isPlaying=false) 不能当成外部暂停。
                pendingEngineStart = true
                runCatching {
                    playbackEngine.setSpeed(_player.value.speed)
                    playbackEngine.prepare(File(cached), positionMs)
                }
                if (wasPlaying) playbackEngine.play()
                _player.update {
                    it.copy(isPlaying = wasPlaying, positionInSegmentMs = positionMs)
                }
                if (wasPlaying) schedulePrefetch()
            } else {
                startEnginePlay(index, positionMs, autoPlay = wasPlaying)
            }
        }
    }

    override fun retrySegment(index: Int) {
        val track = activeTrack ?: return
        if (currentClips[index].isReadyFile()) return
        if (index == _player.value.currentIndex) endedHandledFor = null
        updateSegment(index) { it.copy(status = ClipStatus.PENDING) }
        val epoch = trackEpoch
        scope.launch {
            val ready = synthLock(index).withLock {
                synthesizeSegment(index, track, foreground = true, epoch = epoch)
            }
            if (ready != null && epoch == trackEpoch &&
                index == _player.value.currentIndex && wantPlaying
            ) {
                startEnginePlay(index, 0)
            }
        }
    }

    override fun toggleFollow() {
        _player.update { it.copy(follow = !it.follow) }
    }

    override fun closePlayer() {
        val state = _player.value
        if (state.resourceId != null) persistProgress(state.currentIndex, state.positionInSegmentMs)
        wantPlaying = false
        pendingEngineStart = false
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        cancelPlaybackJob()
        cancelPrefetch()
        playbackEngine.pause()
        currentResource = null
        currentSegments = emptyList()
        currentClips = mutableMapOf()
        activeTrack = null
        endedHandledFor = null
        _player.value = PlayerState()
    }

    // --- 设置 ---

    override fun saveStepFunKey(key: String) {
        scope.launch {
            try {
                settingsStore.setStepFunKey(key)
                _settings.update {
                    it.copy(
                        stepFunKeyConfigured = key.isNotBlank(),
                        error = null,
                        message = if (key.isNotBlank()) "已保存 StepFun Key" else "已清除 StepFun Key",
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _settings.update {
                    it.copy(
                        error = UiError("无法安全保存 StepFun Key，请重试", ErrorKind.UNKNOWN, retryable = true),
                    )
                }
            }
        }
    }

    override fun saveLlmConfig(endpoint: String, key: String, model: String) {
        val normalizedEndpoint = endpoint.trim().ifBlank { LlmDefaults.ENDPOINT }
        val normalizedModel = model.trim().ifBlank { LlmDefaults.MODEL }
        val typedKey = key.trim()
        val previousKeyConfigured = _settings.value.llmKeyConfigured
        _settings.update {
            it.copy(
                llmEndpoint = normalizedEndpoint,
                llmModel = normalizedModel,
                llmKeyConfigured = typedKey.isNotEmpty() || it.llmKeyConfigured,
                message = "已保存 LLM 配置",
                error = null,
            )
        }
        llmConfigSaveJob = scope.launch {
            try {
                // 留空表示保留已保存的 Key；清除请用 setLlmKey("")。
                val existingKey = settingsStore.llmKey()
                val keyToStore = if (typedKey.isNotEmpty()) typedKey else existingKey
                settingsStore.setLlmConfig(normalizedEndpoint, normalizedModel, keyToStore)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _settings.update {
                    it.copy(
                        llmKeyConfigured = previousKeyConfigured,
                        message = null,
                        error = UiError("无法保存 LLM 配置，请重试", ErrorKind.UNKNOWN, retryable = true),
                    )
                }
            }
        }
    }

    override fun setLlmKey(key: String) {
        val current = _settings.value
        val trimmed = key.trim()
        _settings.update {
            it.copy(
                llmKeyConfigured = trimmed.isNotEmpty(),
                message = if (trimmed.isEmpty()) "已清除 LLM Key" else "已保存 LLM Key",
                error = null,
            )
        }
        llmConfigSaveJob = scope.launch {
            try {
                settingsStore.setLlmConfig(current.llmEndpoint, current.llmModel, trimmed.ifEmpty { null })
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _settings.update {
                    it.copy(
                        llmKeyConfigured = current.llmKeyConfigured,
                        message = null,
                        error = UiError("无法保存 LLM Key，请重试", ErrorKind.UNKNOWN, retryable = true),
                    )
                }
            }
        }
    }

    override fun setThemeMode(mode: ThemeMode) {
        _settings.update { it.copy(themeMode = mode) }
        persistSettings { settingsStore.setThemeMode(mode) }
    }

    override fun setPrefetchCount(count: Int) {
        val normalized = count.coerceIn(0, 8)
        _settings.update { it.copy(prefetchCount = normalized) }
        persistSettings { settingsStore.setPrefetchCount(normalized) }
        if (wantPlaying) schedulePrefetch()
    }

    /** 设置持久化失败不崩溃，统一写入 [SettingsState.error]。 */
    private fun persistSettings(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                _settings.update { it.copy(error = null) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _settings.update {
                    it.copy(
                        error = UiError("设置保存失败，请重试", ErrorKind.UNKNOWN, retryable = true),
                    )
                }
            }
        }
    }

    override fun testStepFun() {
        _settings.update { it.copy(stepFunTest = TestState.Running, error = null) }
        scope.launch {
            try {
                val result = speechSynthesizer.testConnection(
                    _settings.value.ttsModel,
                    _settings.value.voice,
                )
                _settings.update {
                    it.copy(
                        stepFunTest = if (result.ok) TestState.Success(result.message)
                        else TestState.Failure(result.message),
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _settings.update { it.copy(stepFunTest = TestState.Failure("连接测试失败，请稍后重试")) }
            }
        }
    }

    override fun testLlm() {
        _settings.update { it.copy(llmTest = TestState.Running) }
        scope.launch {
            llmConfigSaveJob?.join()
            val result = scriptCleaner.testConnection()
            _settings.update {
                it.copy(
                    llmTest = if (result.ok) {
                        TestState.Success(result.message)
                    } else {
                        TestState.Failure(result.message)
                    },
                )
            }
        }
    }

    override fun previewVoice(voiceId: String) {
        val model = _settings.value.ttsModel
        if (!_settings.value.stepFunKeyConfigured) {
            _settings.update {
                it.copy(
                    auditionVoice = voiceId,
                    isAuditioning = false,
                    auditionError = "尚未配置 StepFun Key，请到设置填写",
                )
            }
            return
        }
        _settings.update {
            it.copy(auditionVoice = voiceId, isAuditioning = true, auditionError = null)
        }
        auditionJob?.cancel()
        auditionJob = scope.launch {
            try {
                val directory = previewDir
                    ?: throw SpeechException(ErrorKind.UNKNOWN, "试听缓存目录不可用")
                val file = File(directory, "${model}_${voiceId}.mp3")
                if (!file.isFile || file.length() <= 0L) {
                    directory.mkdirs()
                    speechSynthesizer.synthesize(PREVIEW_TEXT, model, voiceId, file)
                }
                auditionPlayer.play(file)
                _settings.update { it.copy(isAuditioning = false, auditionError = null) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: SpeechException) {
                val message = if (e.kind == ErrorKind.NETWORK) OFFLINE_MESSAGE else e.message
                _settings.update {
                    it.copy(isAuditioning = false, auditionError = message)
                }
            } catch (t: Throwable) {
                _settings.update {
                    it.copy(isAuditioning = false, auditionError = "试听失败，请稍后重试")
                }
            }
        }
    }

    override fun clearOldAudioTracks() {
        _settings.update { it.copy(isClearing = true, error = null) }
        scope.launch {
            try {
                val freed = libraryStore.deleteUnusedTracks()
                val usage = libraryStore.computeStorageUsage()
                _settings.update {
                    it.copy(
                        isClearing = false,
                        storage = usage,
                        message = if (freed > 0) "已清理 ${freed / 1024} KB" else "没有可清理的旧音轨",
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _settings.update {
                    it.copy(
                        isClearing = false,
                        error = UiError("清理失败，请稍后重试", ErrorKind.UNKNOWN, retryable = true),
                    )
                }
            }
        }
    }

    override fun refreshStorageUsage() {
        scope.launch {
            try {
                val usage = libraryStore.computeStorageUsage()
                _settings.update { it.copy(storage = usage) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _settings.update {
                    it.copy(
                        error = UiError("存储统计失败，请稍后重试", ErrorKind.UNKNOWN, retryable = true),
                    )
                }
            }
        }
    }

    // --- 播放 internals ---

    private fun startEnginePlay(index: Int, positionMs: Long, autoPlay: Boolean = true) {
        playbackJob?.cancel()
        pendingEngineStart = true
        playbackJob = scope.launch {
            val ok = try {
                performPlay(index, positionMs, autoPlay)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _player.update {
                    it.copy(error = UiError("播放失败，请稍后重试", ErrorKind.UNKNOWN, retryable = true))
                }
                false
            }
            if (!ok) {
                wantPlaying = false
                pendingEngineStart = false
                _player.update { it.copy(isPlaying = false, isPreparing = false) }
            }
        }
    }

    private suspend fun performPlay(index: Int, positionMs: Long, autoPlay: Boolean): Boolean {
        endedHandledFor = null
        val cachedFile = currentClips[index].takeIf { it.isReadyFile() }?.filePath
        // 已缓存音频直接离线播放，不查 Key、不发起任何合成请求。
        if (cachedFile == null && _settings.value.stepFunKeyConfigured.not()) {
            _player.update {
                it.copy(
                    isPreparing = false,
                    error = UiError(
                        "尚未配置 StepFun Key，请到设置填写后再播放",
                        ErrorKind.NO_KEY,
                    ),
                )
            }
            return false
        }
        val clip = if (cachedFile != null) {
            currentClips[index]!!
        } else {
            ensureClip(index) ?: return false
        }
        val file = clip.filePath?.let { File(it) }
        if (file == null || !file.isFile) {
            markClipError(index, "音频文件缺失，请重试朗读")
            return false
        }
        _player.update { it.copy(isPreparing = false) }
        playbackEngine.prepare(file, positionMs)
        playbackEngine.setSpeed(_player.value.speed)
        // 合成期间用户手动暂停或睡眠定时到点：不要再把引擎拉起来。
        if (autoPlay && wantPlaying) {
            playbackEngine.play()
            _player.update { it.copy(isPlaying = true) }
            schedulePrefetch()
        } else {
            pendingEngineStart = false
            _player.update { it.copy(isPlaying = false) }
        }
        return true
    }

    private suspend fun ensureClip(index: Int): AudioClip? {
        val track = activeTrack ?: return null
        val cached = currentClips[index]
        if (cached.isReadyFile()) return cached
        return synthLock(index).withLock {
            val rechecked = currentClips[index]
            if (rechecked.isReadyFile()) {
                rechecked
            } else {
                synthesizeSegment(index, track, foreground = true)
            }
        }
    }

    private fun synthLock(index: Int): Mutex = synthLocks.getOrPut(index) { Mutex() }

    /**
     * 合成单个 Segment。调用方必须持有 [synthLock]；同一句的串行由该锁保证，
     * 不同句因此可以并行合成。
     * [foreground] 为 false（预取）时失败只标 ERROR，不打断当前播放，也不写全局错误。
     * [epoch] 与当前 [trackEpoch] 不一致说明已切轨，结果只落旧轨文件/DB，不污染新轨状态。
     */
    private suspend fun synthesizeSegment(
        index: Int,
        track: AudioTrack,
        foreground: Boolean,
        epoch: Int = trackEpoch,
    ): AudioClip? {
        if (epoch != trackEpoch) return null
        val segment = currentSegments.firstOrNull { it.index == index } ?: return null
        val file = libraryStore.audioFile(track.resourceId, track.id, index)
        val pendingClip = AudioClip(
            id = clipId(track, index),
            trackId = track.id,
            segmentIndex = index,
            filePath = null,
            durationMs = 0,
            status = ClipStatus.SYNTHESIZING,
            error = null,
        )
        if (epoch == trackEpoch) {
            currentClips[index] = pendingClip
            runCatching { libraryStore.upsertClip(pendingClip) }
            updateSegment(index) { it.copy(status = ClipStatus.SYNTHESIZING, durationMs = 0) }
            refreshPreparing()
        }

        return try {
            val durationMs = speechSynthesizer.synthesize(
                text = segment.text,
                model = track.model,
                voice = track.voice,
                destination = file,
            )
            val ready = pendingClip.copy(
                filePath = file.absolutePath,
                durationMs = durationMs,
                status = ClipStatus.READY,
            )
            runCatching { libraryStore.upsertClip(ready) }
            if (epoch == trackEpoch) {
                currentClips[index] = ready
                updateSegment(index) { it.copy(status = ClipStatus.READY, durationMs = durationMs) }
                refreshTotalDuration()
                refreshPreparing()
            }
            ready
        } catch (c: CancellationException) {
            throw c
        } catch (e: SpeechException) {
            val message = if (e.kind == ErrorKind.NETWORK) OFFLINE_MESSAGE else e.message
            if (epoch == trackEpoch) {
                markClipError(index, message)
                if (foreground) {
                    _player.update { it.copy(error = UiError(message, e.kind, e.retryable)) }
                }
            }
            null
        } catch (t: Throwable) {
            if (epoch == trackEpoch) {
                markClipError(index, "合成失败，请重试朗读")
                if (foreground) {
                    _player.update {
                        it.copy(
                            error = UiError("合成失败，请稍后重试", ErrorKind.UNKNOWN, retryable = true),
                        )
                    }
                }
            }
            null
        }
    }

    private fun markClipError(index: Int, message: String) {
        val existing = currentClips[index]
        val track = activeTrack
        val failed = (existing ?: AudioClip(
            id = if (track != null) clipId(track, index) else "$index",
            trackId = track?.id.orEmpty(),
            segmentIndex = index,
            filePath = null,
            durationMs = 0,
            status = ClipStatus.ERROR,
            error = message,
        )).copy(status = ClipStatus.ERROR, error = message)
        currentClips[index] = failed
        scope.launch { runCatching { libraryStore.upsertClip(failed) } }
        updateSegment(index) { it.copy(status = ClipStatus.ERROR) }
        refreshPreparing()
    }

    /**
     * 为当前段之后的 [SettingsState.prefetchCount] 段补齐预取窗口。
     * 每次调用都按当前索引重新计算窗口：已在进行中的索引跳过，窗口移动时旧任务继续跑完，
     * 因此不会因为旧任务仍活跃而让窗口停留在过期的位置。
     */
    private fun schedulePrefetch() {
        if (!_settings.value.stepFunKeyConfigured) return
        val window = _settings.value.prefetchCount
        if (window <= 0) return
        val track = activeTrack ?: return
        if (currentSegments.isEmpty()) return
        val fromIndex = _player.value.currentIndex
        val lastIndex = minOf(fromIndex + window, currentSegments.lastIndex)
        if (lastIndex <= fromIndex) return
        val epoch = trackEpoch
        for (index in fromIndex + 1..lastIndex) {
            val clip = currentClips[index]
            if (clip.isReadyFile()) continue
            if (clip?.status == ClipStatus.ERROR) continue
            if (prefetchJobs[index]?.isActive == true) continue
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    prefetchSemaphore.withPermit { prefetchOne(index, track, epoch) }
                } finally {
                    if (prefetchJobs[index] === coroutineContext[Job]) prefetchJobs.remove(index)
                    refreshPreparing()
                }
            }
            prefetchJobs[index] = job
            job.start()
        }
    }

    /** 预取单句：同一索引由 [synthLock] 去重，不同索引受 [prefetchSemaphore] 限流。 */
    private suspend fun prefetchOne(index: Int, track: AudioTrack, epoch: Int) {
        synthLock(index).withLock {
            val clip = currentClips[index]
            if (clip.isReadyFile() || clip?.status == ClipStatus.ERROR) return@withLock
            synthesizeSegment(index, track, foreground = false, epoch = epoch)
        }
    }

    private fun cancelPrefetch() {
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
    }

    /** `isPreparing` 由当前/下一句的合成状态派生，避免预取与前台合成互相覆盖。 */
    private fun refreshPreparing() {
        _player.update { state ->
            val current = state.segments.getOrNull(state.currentIndex)
            val next = state.segments.getOrNull(state.currentIndex + 1)
            state.copy(
                isPreparing = current?.status == ClipStatus.SYNTHESIZING ||
                    next?.status == ClipStatus.SYNTHESIZING,
            )
        }
    }

    private fun switchTo(index: Int, autoPlay: Boolean) {
        val state = _player.value
        if (state.segments.isEmpty()) return
        val target = index.coerceIn(0, state.segments.lastIndex)
        persistProgress(state.currentIndex, state.positionInSegmentMs)
        endedHandledFor = null
        cancelPrefetch()
        cancelPlaybackJob()
        _player.update {
            it.copy(currentIndex = target, positionInSegmentMs = 0, error = null)
        }
        persistProgress(target, 0)
        wantPlaying = autoPlay
        if (autoPlay) {
            _player.update { it.copy(isPlaying = true) }
            startEnginePlay(target, 0)
        } else {
            val clip = currentClips[target]
            val path = clip?.takeIf { it.isReadyFile() }?.filePath
            if (path != null) {
                pendingEngineStart = true
                scope.launch {
                    runCatching {
                        playbackEngine.setSpeed(_player.value.speed)
                        playbackEngine.prepare(File(path), 0)
                    }
                }
            }
        }
    }

    private fun onEngineState(state: PlaybackEngineState) {
        when (state) {
            PlaybackEngineState.Idle -> Unit
            PlaybackEngineState.Preparing -> _player.update { it.copy(isPreparing = true) }
            is PlaybackEngineState.Ready -> {
                val wasPendingStart = pendingEngineStart
                if (state.isPlaying || !wantPlaying) pendingEngineStart = false
                // 通知栏 / 锁屏暂停会直接改引擎状态；同步回用户意图与 UI。
                if ((!wasPendingStart || state.isPlaying) && state.isPlaying != wantPlaying) {
                    wantPlaying = state.isPlaying
                    if (!state.isPlaying) {
                        persistProgress(_player.value.currentIndex, state.positionMs)
                    }
                }
                _player.update {
                    it.copy(
                        positionInSegmentMs = state.positionMs,
                        isPlaying = if (pendingEngineStart) wantPlaying else state.isPlaying,
                    )
                }
                refreshPreparing()
            }
            is PlaybackEngineState.Ended -> {
                pendingEngineStart = false
                handleEnded(state.durationMs)
            }
            is PlaybackEngineState.Error -> {
                wantPlaying = false
                pendingEngineStart = false
                _player.update {
                    it.copy(
                        isPlaying = false,
                        isPreparing = false,
                        error = UiError(state.message, ErrorKind.UNKNOWN, retryable = true),
                    )
                }
            }
        }
    }

    private fun handleEnded(engineDurationMs: Long) {
        val state = _player.value
        if (state.resourceId == null || state.segments.isEmpty()) return
        val index = state.currentIndex
        if (endedHandledFor == index) return
        endedHandledFor = index
        val duration = maxOf(engineDurationMs, state.segments.getOrNull(index)?.durationMs ?: 0)
        persistProgress(index, duration)
        if (index >= state.segments.lastIndex) {
            wantPlaying = false
            _player.update { it.copy(isPlaying = false, positionInSegmentMs = duration) }
            return
        }
        val next = index + 1
        _player.update { it.copy(currentIndex = next, positionInSegmentMs = 0) }
        refreshPreparing()
        if (wantPlaying) startEnginePlay(next, 0)
        schedulePrefetch()
    }

    private fun persistProgress(index: Int, positionMs: Long) {
        val resourceId = _player.value.resourceId ?: return
        scope.launch {
            runCatching { libraryStore.updateProgress(resourceId, index, positionMs) }
            allItems = allItems.map { item ->
                if (item.id == resourceId) {
                    item.copy(currentSegmentIndex = index, currentPositionMs = positionMs)
                } else {
                    item
                }
            }
            publishLibrary(isLoading = false, error = null)
        }
    }

    private fun updateSegment(index: Int, transform: (PlayerSegment) -> PlayerSegment) {
        _player.update { state ->
            state.copy(
                segments = state.segments.map { if (it.index == index) transform(it) else it },
            )
        }
    }

    private fun refreshTotalDuration() {
        _player.update { it.copy(totalDurationMs = estimateTotalDuration(it.segments)) }
    }

    private fun estimateTotalDuration(segments: List<PlayerSegment>): Long =
        segments.sumOf { segment ->
            if (segment.status == ClipStatus.READY && segment.durationMs > 0) {
                segment.durationMs
            } else {
                estimateSegmentDuration(segment.text)
            }
        }

    private fun estimateSegmentDuration(text: String): Long =
        (text.length * 1000L / CHARS_PER_SECOND).toLong().coerceAtLeast(500L)

    private fun AudioClip?.isReadyFile(): Boolean =
        this != null && status == ClipStatus.READY && !filePath.isNullOrBlank() &&
            File(filePath!!).isFile

    private fun clipId(track: AudioTrack, index: Int): String = "${track.id}-$index"

    // --- 资料库 internals ---

    private suspend fun loadResource(resourceId: String, markOpened: Boolean): Boolean {
        val resource = libraryStore.getResource(resourceId) ?: return false
        val segments = libraryStore.getSegments(resourceId).sortedBy { it.index }
        val tracks = libraryStore.getTracks(resourceId)
        val track = tracks.firstOrNull { it.id == resource.activeTrackId } ?: tracks.lastOrNull()
            ?: createInitialTrack(resourceId)
        if (track != null && resource.activeTrackId != track.id) {
            libraryStore.setActiveTrack(resourceId, track.id)
        }
        val clips = track?.let { libraryStore.getClips(it.id) }.orEmpty()
            .associateBy { it.segmentIndex }
            .toMutableMap()

        currentResource = resource
        currentSegments = segments
        activeTrack = track
        currentClips = clips
        endedHandledFor = null
        trackEpoch++
        cancelPrefetch()
        cancelPlaybackJob()
        wantPlaying = false

        if (markOpened) {
            libraryStore.markOpened(resourceId, timeProvider.now())
            libraryStore.saveLastOpenedResourceId(resourceId)
        }

        val appSettings = settingsStore.settings.first()
        val speed = appSettings.playbackSpeed.coerceIn(0.5f, 2.0f)
        val previousTimer = _player.value
        val segmentStates = segments.map { segment ->
            val clip = clips[segment.index]
            PlayerSegment(
                index = segment.index,
                text = segment.text,
                kind = segment.kind,
                durationMs = clip?.durationMs ?: 0,
                status = clip?.status ?: ClipStatus.PENDING,
            )
        }
        val currentIndex = if (segments.isEmpty()) {
            resource.currentSegmentIndex
        } else {
            resource.currentSegmentIndex.coerceIn(0, segments.lastIndex)
        }
        val positionMs = if (currentIndex == resource.currentSegmentIndex) {
            resource.currentPositionMs.coerceAtLeast(0)
        } else {
            0
        }
        val (snapshotText, snapshotError) = loadSnapshot(resourceId)
        _player.value = PlayerState(
            resourceId = resourceId,
            title = resource.title,
            sourceKind = resource.sourceKind,
            sourceLabel = sourceLabelFor(resource.sourceKind, resource.sourceUrl),
            segments = segmentStates,
            currentIndex = currentIndex,
            positionInSegmentMs = positionMs,
            totalDurationMs = estimateTotalDuration(segmentStates),
            isPlaying = false,
            isPreparing = false,
            speed = speed,
            sleepTimerMinutes = previousTimer.sleepTimerMinutes,
            sleepTimerRemainingMs = previousTimer.sleepTimerRemainingMs,
            activeTrack = track?.let { AudioTrackInfo(it.id, it.model, it.voice) },
            snapshotText = snapshotText,
            snapshotError = snapshotError,
        )
        playbackEngine.setSpeed(speed)

        val currentClip = clips[currentIndex]
        val currentPath = currentClip?.takeIf { it.isReadyFile() }?.filePath
        if (currentPath != null) {
            runCatching { playbackEngine.prepare(File(currentPath), positionMs) }
        }
        return true
    }

    /** 读取原文 Markdown 快照；返回 (文本, 不可读原因)，缺失或失败不抛异常。 */
    private suspend fun loadSnapshot(resourceId: String): Pair<String?, String?> =
        try {
            val text = libraryStore.snapshotText(resourceId)
            if (text.isNullOrBlank()) null to "原文快照不可用" else text to null
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null to "原文快照读取失败"
        }

    private fun cancelPlaybackJob() {
        playbackJob?.cancel()
        playbackJob = null
        pendingEngineStart = false
    }

    private suspend fun createInitialTrack(resourceId: String): AudioTrack? {
        val appSettings = settingsStore.settings.first()
        val track = AudioTrack(
            id = UUID.randomUUID().toString(),
            resourceId = resourceId,
            model = appSettings.ttsModel,
            voice = appSettings.voice,
            createdAt = timeProvider.now(),
        )
        return runCatching {
            libraryStore.createTrack(track)
            libraryStore.setActiveTrack(resourceId, track.id)
            track
        }.getOrNull()
    }

    /** 丢弃进行中的 L2 结果，并让已返回的结果失效。 */
    private fun invalidateL2() {
        l2Job?.cancel()
        l2Job = null
        l2Epoch++
    }

    /** 异步清洗当前预览；结果只在会话与 L2 纪元都未变时写回。 */
    private fun startL2(epoch: Int, title: String, body: String) {
        l2Epoch++
        val runEpoch = l2Epoch
        val cleanTitle = speechTitle(title)
        val ruleDrafts = RuleSegmenter.segment(cleanTitle, body)
        _capture.update { state ->
            val preview = state.preview ?: return@update state
            state.copy(
                preview = preview.copy(
                    segmentDrafts = ruleDrafts,
                    segmentation = SegmentationSource.RULE,
                    l2Status = L2Status.RUNNING,
                    l2Error = null,
                ),
            )
        }
        l2Job = scope.launch {
            val result = try {
                scriptCleaner.clean(cleanTitle, body)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                CleanResult.Failure("L2 清洗失败，请稍后重试", retryable = true)
            }
            if (epoch != captureEpoch || runEpoch != l2Epoch) return@launch
            _capture.update { state ->
                val preview = state.preview ?: return@update state
                if (preview.title != title || preview.body != body) return@update state
                when (result) {
                    is CleanResult.Success -> {
                        if (result.segments.isEmpty()) {
                            state.copy(
                                preview = preview.copy(
                                    segmentDrafts = ruleDrafts,
                                    segmentation = SegmentationSource.RULE,
                                    l2Status = L2Status.FAILED,
                                    l2Error = "LLM 没有返回任何分段，当前为规则分段",
                                ),
                            )
                        } else {
                            state.copy(
                                preview = preview.copy(
                                    segmentDrafts = result.segments,
                                    segmentation = SegmentationSource.L2,
                                    l2Status = L2Status.SUCCEEDED,
                                    l2Error = null,
                                ),
                            )
                        }
                    }
                    is CleanResult.Failure -> state.copy(
                        preview = preview.copy(
                            segmentDrafts = ruleDrafts,
                            segmentation = SegmentationSource.RULE,
                            l2Status = L2Status.FAILED,
                            l2Error = result.message.ifBlank { "L2 清洗失败，当前为规则分段" },
                        ),
                    )
                }
            }
        }
    }

    private fun rebuildPreview(
        preview: PreviewState,
        title: String = preview.title,
        body: String = preview.body,
    ): PreviewState {
        val unconfigured = preview.l2Status == L2Status.NOT_CONFIGURED
        return preview.copy(
            title = title,
            body = body,
            charCount = body.length,
            segmentDrafts = RuleSegmenter.segment(speechTitle(title), body),
            segmentation = SegmentationSource.RULE,
            l2Status = if (unconfigured) L2Status.NOT_CONFIGURED else L2Status.NOT_RUN,
            l2Error = if (unconfigured) NO_LLM_MESSAGE else null,
        )
    }

    /** 朗读/L2 使用的标题：粘贴文本的占位标题只是展示信息，不该被念出来。 */
    private fun speechTitle(title: String): String =
        title.trim().takeUnless { it == TEXT_SOURCE_LABEL } ?: ""

    /** 首行短且不像正文句子时作为标题，其余保留为正文；否则整段作为正文。 */
    private fun splitTextTitle(text: String): Pair<String, String> {
        val lines = text.lines()
        val firstIndex = lines.indexOfFirst { it.isNotBlank() }
        if (firstIndex < 0) return TEXT_SOURCE_LABEL to text
        val first = lines[firstIndex].trim()
        val rest = lines.drop(firstIndex + 1).joinToString("\n").trim()
        val titleLike = first.length <= 80 && first.last() !in TITLE_TERMINATORS
        return if (titleLike && rest.isNotEmpty()) first to rest else TEXT_SOURCE_LABEL to text
    }

    private fun publishLibrary(isLoading: Boolean, error: UiError?) {
        _library.update { state ->
            val visible = allItems
                .filter { matchesQuery(it, state.query) }
                .filter { matchesFilter(it, state.filter) }
            val continuation = lastOpenedId?.let { id -> allItems.firstOrNull { it.id == id } }
            state.copy(
                isLoading = isLoading,
                items = visible,
                continueListening = continuation,
                error = error,
            )
        }
    }

    /** 读取持久化的上次打开记录；失败时当作没有记录，不影响资料库展示。 */
    private suspend fun readLastOpenedId(): String? =
        runCatching { libraryStore.lastOpenedResourceId() }.getOrNull()

    private fun matchesQuery(item: LibraryItem, query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim()
        return item.title.contains(needle, ignoreCase = true) ||
            item.sourceLabel.contains(needle, ignoreCase = true)
    }

    private fun matchesFilter(item: LibraryItem, filter: LibraryFilter): Boolean = when (filter) {
        LibraryFilter.ALL -> true
        LibraryFilter.WEB -> item.sourceKind == SourceKind.URL
        LibraryFilter.TEXT -> item.sourceKind == SourceKind.TEXT
        LibraryFilter.CACHED -> item.hasCachedAudio
    }

    private fun sourceLabelFor(kind: SourceKind, url: String?): String {
        if (kind == SourceKind.TEXT) return TEXT_SOURCE_LABEL
        return ShareText.sourceLabel(url.orEmpty())
    }

    private companion object {
        const val TEXT_SOURCE_LABEL = "粘贴文本"
        const val TEXT_EXTRACTOR = "文本输入"
        const val CHARS_PER_SECOND = 4.5
        const val SLEEP_TIMER_TICK_MS = 1_000L
        const val MAX_CONCURRENT_PREFETCH = 2
        const val NO_LLM_MESSAGE = "尚未配置 LLM Key，当前使用规则分段；到设置填写后可重新 L2"
        const val OFFLINE_MESSAGE = "需要联网合成，请联网后重试"
        const val PREVIEW_TEXT = "你好，这是听我读的音色试听。"
        val TITLE_TERMINATORS = charArrayOf('。', '！', '？', '!', '?', '；', ';', '…')
    }
}

private fun PageSessionState.toUiState(): PageUiState = when (this) {
    is PageSessionState.Idle -> PageUiState.Idle
    is PageSessionState.Loading -> PageUiState.Loading(progress)
    is PageSessionState.Ready -> PageUiState.Ready
    is PageSessionState.NeedsUserAction -> PageUiState.NeedsUserAction(reason)
    is PageSessionState.Failed -> PageUiState.Failed(message)
}
