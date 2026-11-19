package com.cnife.listenreader.service

import android.content.Context
import android.view.View
import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.core.RuleSegmenter
import com.cnife.listenreader.core.UiError
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.model.LibraryFilter
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.LlmDefaults
import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.SegmentationSource
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.ports.CleanResult
import com.cnife.listenreader.service.ports.LibraryStore
import com.cnife.listenreader.service.ports.PageSession
import com.cnife.listenreader.service.ports.PlaybackEngine
import com.cnife.listenreader.service.ports.ScriptCleaner
import com.cnife.listenreader.service.ports.SettingsStore
import com.cnife.listenreader.service.ports.SpeechSynthesizer
import com.cnife.listenreader.service.ports.TimeProvider
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 应用级协调器。T1 只实现资料库空态、主题设置与占位命令；
 * Capture / L2 / TTS / 播放的编排留给后续 ticket，签名保持不变。
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

    /** 每次开始新的采集会话自增，用于丢弃已过期会话的异步保存结果。 */
    private var captureEpoch = 0

    /** 每次 L2 运行自增，用于丢弃已过期的清洗结果。 */
    private var l2Epoch = 0
    private var l2Job: Job? = null

    /** 最近一次 LLM 配置写入，供 testLlm 等待保存完成。 */
    private var llmConfigSaveJob: Job? = null

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
                publishLibrary(isLoading = false, error = null)
            }
        }
    }

    // --- 资料库 ---

    override fun refreshLibrary() {
        _library.update { it.copy(isLoading = true, error = null) }
        scope.launch {
            val items = libraryStore.observeItems().first()
            allItems = items
            publishLibrary(isLoading = false, error = null)
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
            val resource = libraryStore.getResource(resourceId)
            if (resource == null) {
                _library.update {
                    it.copy(error = UiError("找不到该资源", ErrorKind.UNKNOWN))
                }
                return@launch
            }
            val segments = libraryStore.getSegments(resourceId).sortedBy { it.index }
            val tracks = libraryStore.getTracks(resourceId)
            val track = tracks.firstOrNull { it.id == resource.activeTrackId } ?: tracks.lastOrNull()
            val clips = track?.let { libraryStore.getClips(it.id) }.orEmpty()
            val clipByIndex = clips.associateBy { it.segmentIndex }
            libraryStore.markOpened(resourceId, timeProvider.now())

            _player.value = PlayerState(
                resourceId = resourceId,
                title = resource.title,
                sourceKind = resource.sourceKind,
                sourceLabel = sourceLabelFor(resource.sourceKind, resource.sourceUrl),
                segments = segments.map { segment ->
                    val clip = clipByIndex[segment.index]
                    PlayerSegment(
                        index = segment.index,
                        text = segment.text,
                        kind = segment.kind,
                        durationMs = clip?.durationMs ?: 0,
                        status = clip?.status ?: com.cnife.listenreader.model.ClipStatus.PENDING,
                    )
                },
                currentIndex = resource.currentSegmentIndex,
                positionInSegmentMs = resource.currentPositionMs,
                totalDurationMs = clips.sumOf { it.durationMs },
                isPlaying = false,
                activeTrack = track?.let { AudioTrackInfo(it.id, it.model, it.voice) },
            )
        }
    }

    override fun deleteResource(resourceId: String) {
        scope.launch {
            libraryStore.deleteResource(resourceId)
            if (_player.value.resourceId == resourceId) {
                _player.value = PlayerState()
            }
            val items = libraryStore.observeItems().first()
            allItems = items
            publishLibrary(isLoading = false, error = null)
        }
    }

    // --- Capture / 预览 ---

    override fun beginUrlCapture(url: String?) {
        captureEpoch++
        invalidateL2()
        _capture.value = CaptureState(
            mode = CaptureMode.URL,
            url = url.orEmpty(),
            page = PageUiState.Idle,
        )
    }

    override fun beginTextCapture(text: String) {
        captureEpoch++
        invalidateL2()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) {
            _capture.value = CaptureState(
                mode = CaptureMode.TEXT,
                url = text,
                page = PageUiState.Idle,
                error = UiError("请先粘贴要朗读的文本", ErrorKind.UNKNOWN),
            )
            return
        }
        val (title, body) = splitTextTitle(normalized)
        val drafts = RuleSegmenter.segment(title, body)
        val configured = scriptCleaner.isConfigured()
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
        )
        if (configured) {
            startL2(captureEpoch, title, body)
        }
    }

    override fun handleShareIntent(text: String) {
        val url = URL_PATTERN.find(text)?.value
        if (url != null) {
            beginUrlCapture(url)
        } else {
            beginTextCapture(text)
        }
    }

    override fun loadUrl(url: String) {
        _capture.update {
            it.copy(mode = CaptureMode.URL, url = url, page = PageUiState.Idle, error = null)
        }
    }

    override fun reloadPage() {
        // T6 接入真实 WebView 后实现。
    }

    override fun pageBack(): Boolean = false

    override fun createPageView(context: Context): View? = null

    override fun releasePageView() {
        // T6 接入真实 WebView 后实现。
    }

    override fun extractPage() {
        _capture.update {
            it.copy(
                isExtracting = false,
                error = UiError("正文提取将在后续版本接入", ErrorKind.UNKNOWN),
            )
        }
    }

    override fun useFullPage() {
        // T7 接入 L3「使用页面全文」后实现。
    }

    override fun retryL2() {
        val preview = _capture.value.preview ?: return
        invalidateL2()
        if (!scriptCleaner.isConfigured()) {
            _capture.update { state ->
                val current = state.preview ?: return@update state
                state.copy(
                    preview = current.copy(
                        segmentDrafts = RuleSegmenter.segment(current.title, current.body),
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
        val title = preview.title.trim().ifBlank { TEXT_SOURCE_LABEL }
        val body = preview.body.trim()
        // 保存当前预览里的分段：L2 成功时是 LLM 分段，失败/未配置时是规则分段。
        val drafts = preview.segmentDrafts.ifEmpty { RuleSegmenter.segment(title, body) }
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
                    sourceKind = SourceKind.TEXT,
                    sourceUrl = null,
                    byline = null,
                    publishedAt = null,
                    extractor = preview.extractor ?: TEXT_EXTRACTOR,
                    snapshotPath = null,
                    currentSegmentIndex = 0,
                    currentPositionMs = 0,
                    activeTrackId = null,
                    createdAt = now,
                    lastOpenedAt = 0,
                )
                libraryStore.createResource(resource, body, null, drafts)
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
        _capture.value = CaptureState()
    }

    // --- 播放 ---

    override fun play() {
        if (_player.value.resourceId == null) return
        _player.update { it.copy(isPlaying = true) }
        playbackEngine.play()
    }

    override fun pause() {
        _player.update { it.copy(isPlaying = false) }
        playbackEngine.pause()
    }

    override fun togglePlay() {
        if (_player.value.isPlaying) pause() else play()
    }

    override fun nextSegment() {
        val state = _player.value
        if (state.segments.isEmpty()) return
        seekToSegment(state.currentIndex + 1)
    }

    override fun previousSegment() {
        val state = _player.value
        if (state.segments.isEmpty()) return
        seekToSegment(state.currentIndex - 1)
    }

    override fun seekToSegment(index: Int) {
        _player.update { state ->
            if (state.segments.isEmpty()) return@update state
            val target = index.coerceIn(0, state.segments.lastIndex)
            state.copy(currentIndex = target, positionInSegmentMs = 0)
        }
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _player.update { it.copy(speed = clamped) }
        playbackEngine.setSpeed(clamped)
        scope.launch { settingsStore.setPlaybackSpeed(clamped) }
    }

    override fun setSleepTimer(minutes: Int?) {
        val normalized = minutes?.takeIf { it > 0 }
        _player.update {
            it.copy(
                sleepTimerMinutes = normalized,
                sleepTimerRemainingMs = normalized?.let { value -> value * 60_000L },
            )
        }
    }

    override fun setVoice(voiceId: String) {
        _settings.update { it.copy(voice = voiceId) }
        _player.update { it.copy(activeTrack = it.activeTrack?.copy(voice = voiceId)) }
        scope.launch { settingsStore.setVoice(voiceId) }
    }

    override fun setModel(modelId: String) {
        _settings.update { it.copy(ttsModel = modelId) }
        scope.launch { settingsStore.setTtsModel(modelId) }
    }

    override fun retrySegment(index: Int) {
        // T3/T8 接入合成与播放后实现。
    }

    override fun toggleFollow() {
        _player.update { it.copy(follow = !it.follow) }
    }

    override fun closePlayer() {
        playbackEngine.pause()
        _player.value = PlayerState()
    }

    // --- 设置 ---

    override fun saveStepFunKey(key: String) {
        val configured = key.isNotBlank()
        _settings.update { it.copy(stepFunKeyConfigured = configured, message = "已保存 StepFun Key") }
        scope.launch { settingsStore.setStepFunKey(key) }
    }

    override fun saveLlmConfig(endpoint: String, key: String, model: String) {
        val normalizedEndpoint = endpoint.trim().ifBlank { LlmDefaults.ENDPOINT }
        val normalizedModel = model.trim().ifBlank { LlmDefaults.MODEL }
        val typedKey = key.trim()
        _settings.update {
            it.copy(
                llmEndpoint = normalizedEndpoint,
                llmModel = normalizedModel,
                llmKeyConfigured = typedKey.isNotEmpty() || it.llmKeyConfigured,
                message = "已保存 LLM 配置",
            )
        }
        llmConfigSaveJob = scope.launch {
            // 留空表示保留已保存的 Key；清除请用 setLlmKey("")。
            val existingKey = settingsStore.llmKey()
            val keyToStore = if (typedKey.isNotEmpty()) typedKey else existingKey
            settingsStore.setLlmConfig(normalizedEndpoint, normalizedModel, keyToStore)
        }
    }

    override fun setLlmKey(key: String) {
        val current = _settings.value
        val trimmed = key.trim()
        _settings.update {
            it.copy(
                llmKeyConfigured = trimmed.isNotEmpty(),
                message = if (trimmed.isEmpty()) "已清除 LLM Key" else "已保存 LLM Key",
            )
        }
        llmConfigSaveJob = scope.launch {
            settingsStore.setLlmConfig(current.llmEndpoint, current.llmModel, trimmed.ifEmpty { null })
        }
    }

    override fun setThemeMode(mode: ThemeMode) {
        _settings.update { it.copy(themeMode = mode) }
        scope.launch { settingsStore.setThemeMode(mode) }
    }

    override fun setPrefetchCount(count: Int) {
        val normalized = count.coerceIn(0, 2)
        _settings.update { it.copy(prefetchCount = normalized) }
        scope.launch { settingsStore.setPrefetchCount(normalized) }
    }

    override fun testStepFun() {
        _settings.update { it.copy(stepFunTest = TestState.Running) }
        scope.launch {
            _settings.update {
                it.copy(stepFunTest = TestState.Failure("StepFun 连接测试将在后续版本接入"))
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
        _settings.update { it.copy(message = "试听功能将在后续版本接入") }
    }

    override fun clearOldAudioTracks() {
        _settings.update { it.copy(isClearing = true) }
        scope.launch {
            val freed = libraryStore.deleteUnusedTracks()
            val usage = libraryStore.computeStorageUsage()
            _settings.update {
                it.copy(
                    isClearing = false,
                    storage = usage,
                    message = if (freed > 0) "已清理 ${freed / 1024} KB" else "没有可清理的旧音轨",
                )
            }
        }
    }

    override fun refreshStorageUsage() {
        scope.launch {
            val usage = libraryStore.computeStorageUsage()
            _settings.update { it.copy(storage = usage) }
        }
    }

    // --- internals ---

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
        val ruleDrafts = RuleSegmenter.segment(title, body)
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
                scriptCleaner.clean(title, body)
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
            segmentDrafts = RuleSegmenter.segment(title, body),
            segmentation = SegmentationSource.RULE,
            l2Status = if (unconfigured) L2Status.NOT_CONFIGURED else L2Status.NOT_RUN,
            l2Error = if (unconfigured) NO_LLM_MESSAGE else null,
        )
    }

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
            val continuation = allItems
                .filter { it.currentSegmentIndex > 0 || it.currentPositionMs > 0 }
                .maxByOrNull { it.lastOpenedAt }
            state.copy(
                isLoading = isLoading,
                items = visible,
                continueListening = continuation,
                error = error,
            )
        }
    }

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
        if (kind == SourceKind.TEXT) return "粘贴文本"
        val host = runCatching { URI(url.orEmpty()).host }.getOrNull()
        return host?.removePrefix("www.").orEmpty().ifBlank { "网页" }
    }

    private companion object {
        val URL_PATTERN = Regex("""https?://\S+""")
        const val TEXT_SOURCE_LABEL = "粘贴文本"
        const val TEXT_EXTRACTOR = "文本输入"
        const val NO_LLM_MESSAGE = "尚未配置 LLM Key，当前使用规则分段；到设置填写后可重新 L2"
        val TITLE_TERMINATORS = charArrayOf('。', '！', '？', '!', '?', '；', ';', '…')
    }
}
