package com.cnife.listenreader.service

import android.content.Context
import android.view.View
import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.core.RuleSegmenter
import com.cnife.listenreader.core.UiError
import com.cnife.listenreader.model.AudioClip
import com.cnife.listenreader.model.AudioTrack
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.model.LibraryFilter
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.Segment
import com.cnife.listenreader.model.SegmentationSource
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.ports.LibraryStore
import com.cnife.listenreader.service.ports.PageSession
import com.cnife.listenreader.service.ports.PlaybackEngine
import com.cnife.listenreader.service.ports.PlaybackEngineState
import com.cnife.listenreader.service.ports.ScriptCleaner
import com.cnife.listenreader.service.ports.SettingsStore
import com.cnife.listenreader.service.ports.SpeechSynthesizer
import com.cnife.listenreader.service.ports.TimeProvider
import com.cnife.listenreader.tts.SpeechException
import java.io.File
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private var currentResource: Resource? = null
    private var currentSegments: List<Segment> = emptyList()
    private var currentClips: MutableMap<Int, AudioClip> = mutableMapOf()
    private var activeTrack: AudioTrack? = null

    /** 用户意图：是否应该正在播放（区别于引擎瞬时状态）。 */
    private var wantPlaying = false
    private var endedHandledFor: Int? = null
    private var playbackJob: Job? = null
    private val synthMutex = Mutex()

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
        scope.launch {
            playbackEngine.state.collect { state -> onEngineState(state) }
        }
        scope.launch {
            val resourceId = runCatching { libraryStore.lastOpenedResourceId() }.getOrNull()
            if (resourceId != null && _player.value.resourceId == null) {
                loadResource(resourceId, markOpened = false)
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
            val loaded = loadResource(resourceId, markOpened = true)
            if (!loaded) {
                _library.update {
                    it.copy(error = UiError("找不到该资源", ErrorKind.UNKNOWN))
                }
            }
        }
    }

    override fun deleteResource(resourceId: String) {
        scope.launch {
            libraryStore.deleteResource(resourceId)
            if (_player.value.resourceId == resourceId) closePlayer()
            val items = libraryStore.observeItems().first()
            allItems = items
            publishLibrary(isLoading = false, error = null)
        }
    }

    // --- Capture / 预览 ---

    override fun beginUrlCapture(url: String?) {
        captureEpoch++
        _capture.value = CaptureState(
            mode = CaptureMode.URL,
            url = url.orEmpty(),
            page = PageUiState.Idle,
        )
    }

    override fun beginTextCapture(text: String) {
        captureEpoch++
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
                l2Status = L2Status.NOT_RUN,
                sourceKind = SourceKind.TEXT,
                sourceLabel = TEXT_SOURCE_LABEL,
                extractor = TEXT_EXTRACTOR,
                score = 1.0,
            ),
        )
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
        // T4 接入 L2 后实现。
    }

    override fun updatePreviewTitle(title: String) {
        _capture.update { state ->
            val preview = state.preview ?: return@update state
            state.copy(preview = rebuildPreview(preview, title = title))
        }
    }

    override fun updatePreviewBody(body: String) {
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
        val drafts = RuleSegmenter.segment(title, body)
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
                libraryStore.createResource(resource, body, null)
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
        _capture.value = CaptureState()
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
        scope.launch { runCatching { settingsStore.setPlaybackSpeed(clamped) } }
    }

    override fun setSleepTimer(minutes: Int?) {
        // T8 接入睡眠定时后生效。
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
        scope.launch { runCatching { settingsStore.setVoice(voiceId) } }
    }

    override fun setModel(modelId: String) {
        _settings.update { it.copy(ttsModel = modelId) }
        scope.launch { runCatching { settingsStore.setTtsModel(modelId) } }
    }

    override fun retrySegment(index: Int) {
        val track = activeTrack ?: return
        if (index == _player.value.currentIndex) endedHandledFor = null
        scope.launch {
            val retried = synthMutex.withLock { synthesizeSegment(index, track) != null }
            if (retried && index == _player.value.currentIndex && wantPlaying) {
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
        playbackJob?.cancel()
        playbackJob = null
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
        _settings.update {
            it.copy(
                llmEndpoint = endpoint,
                llmModel = model,
                llmKeyConfigured = key.isNotBlank(),
                message = "已保存 LLM 配置",
            )
        }
        scope.launch { settingsStore.setLlmConfig(endpoint, model, key.ifBlank { null }) }
    }

    override fun setLlmKey(key: String) {
        val current = _settings.value
        _settings.update { it.copy(llmKeyConfigured = key.isNotBlank()) }
        scope.launch { settingsStore.setLlmConfig(current.llmEndpoint, current.llmModel, key.ifBlank { null }) }
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
            _settings.update {
                it.copy(llmTest = TestState.Failure("LLM 连接测试将在后续版本接入"))
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

    // --- 播放 internals ---

    private fun startEnginePlay(index: Int, positionMs: Long) {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            val ok = try {
                performPlay(index, positionMs)
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
                _player.update { it.copy(isPlaying = false, isPreparing = false) }
            }
        }
    }

    private suspend fun performPlay(index: Int, positionMs: Long): Boolean {
        if (_settings.value.stepFunKeyConfigured.not()) {
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
        endedHandledFor = null
        val clip = ensureClip(index) ?: return false
        val file = clip.filePath?.let { File(it) }
        if (file == null || !file.isFile) {
            markClipError(index, "音频文件缺失，请重试朗读")
            return false
        }
        _player.update { it.copy(isPreparing = false) }
        playbackEngine.prepare(file, positionMs)
        playbackEngine.setSpeed(_player.value.speed)
        playbackEngine.play()
        return true
    }

    private suspend fun ensureClip(index: Int): AudioClip? {
        val track = activeTrack ?: return null
        val cached = currentClips[index]
        if (cached.isReadyFile()) return cached
        return synthMutex.withLock {
            val rechecked = currentClips[index]
            if (rechecked.isReadyFile()) {
                rechecked
            } else {
                synthesizeSegment(index, track)
            }
        }
    }

    private suspend fun synthesizeSegment(index: Int, track: AudioTrack): AudioClip? {
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
        currentClips[index] = pendingClip
        runCatching { libraryStore.upsertClip(pendingClip) }
        updateSegment(index) { it.copy(status = ClipStatus.SYNTHESIZING, durationMs = 0) }
        _player.update { it.copy(isPreparing = true) }

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
            currentClips[index] = ready
            libraryStore.upsertClip(ready)
            updateSegment(index) { it.copy(status = ClipStatus.READY, durationMs = durationMs) }
            refreshTotalDuration()
            _player.update { it.copy(isPreparing = false) }
            ready
        } catch (c: CancellationException) {
            throw c
        } catch (e: SpeechException) {
            markClipError(index, e.message)
            _player.update { it.copy(error = UiError(e.message, e.kind, e.retryable)) }
            null
        } catch (t: Throwable) {
            markClipError(index, "合成失败，请重试朗读")
            _player.update {
                it.copy(error = UiError("合成失败，请稍后重试", ErrorKind.UNKNOWN, retryable = true))
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
        _player.update { it.copy(isPreparing = false) }
    }

    private fun switchTo(index: Int, autoPlay: Boolean) {
        val state = _player.value
        if (state.segments.isEmpty()) return
        val target = index.coerceIn(0, state.segments.lastIndex)
        persistProgress(state.currentIndex, state.positionInSegmentMs)
        endedHandledFor = null
        playbackJob?.cancel()
        playbackJob = null
        _player.update {
            it.copy(currentIndex = target, positionInSegmentMs = 0, error = null)
        }
        wantPlaying = autoPlay
        if (autoPlay) {
            _player.update { it.copy(isPlaying = true) }
            startEnginePlay(target, 0)
        } else {
            val clip = currentClips[target]
            val path = clip?.takeIf { it.isReadyFile() }?.filePath
            if (path != null) {
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
            is PlaybackEngineState.Ready -> _player.update {
                it.copy(positionInSegmentMs = state.positionMs, isPlaying = wantPlaying)
            }
            is PlaybackEngineState.Ended -> handleEnded(state.durationMs)
            is PlaybackEngineState.Error -> {
                wantPlaying = false
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
        if (wantPlaying) startEnginePlay(next, 0)
    }

    private fun persistProgress(index: Int, positionMs: Long) {
        val resourceId = _player.value.resourceId ?: return
        scope.launch {
            runCatching { libraryStore.updateProgress(resourceId, index, positionMs) }
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
        cancelPlaybackJob()
        wantPlaying = false

        if (markOpened) {
            libraryStore.markOpened(resourceId, timeProvider.now())
            libraryStore.saveLastOpenedResourceId(resourceId)
        }

        val appSettings = settingsStore.settings.first()
        val speed = appSettings.playbackSpeed.coerceIn(0.5f, 2.0f)
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
            activeTrack = track?.let { AudioTrackInfo(it.id, it.model, it.voice) },
        )
        playbackEngine.setSpeed(speed)

        val currentClip = clips[currentIndex]
        val currentPath = currentClip?.takeIf { it.isReadyFile() }?.filePath
        if (currentPath != null) {
            runCatching { playbackEngine.prepare(File(currentPath), positionMs) }
        }
        return true
    }

    private fun cancelPlaybackJob() {
        playbackJob?.cancel()
        playbackJob = null
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

    private fun rebuildPreview(
        preview: PreviewState,
        title: String = preview.title,
        body: String = preview.body,
    ): PreviewState = preview.copy(
        title = title,
        body = body,
        charCount = body.length,
        segmentDrafts = RuleSegmenter.segment(title, body),
        segmentation = SegmentationSource.RULE,
        l2Status = L2Status.NOT_RUN,
        l2Error = null,
    )

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
        const val CHARS_PER_SECOND = 4.5
        val TITLE_TERMINATORS = charArrayOf('。', '！', '？', '!', '?', '；', ';', '…')
    }
}
