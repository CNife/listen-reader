package com.cnife.listenreader.testing

import android.content.Context
import android.view.View
import com.cnife.listenreader.core.RuleSegmenter
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.model.LibraryFilter
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.SegmentationSource
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.CaptureMode
import com.cnife.listenreader.service.CaptureState
import com.cnife.listenreader.service.ExtractionInfo
import com.cnife.listenreader.service.LibraryState
import com.cnife.listenreader.service.PlayerState
import com.cnife.listenreader.service.PreviewState
import com.cnife.listenreader.service.ReadingService
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.service.TestState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 可配置的 UI 测试替身：实现 ReadingService 全部成员，只在测试 source 使用。
 */
class FakeReadingService(
    initialLibrary: LibraryState = LibraryState(isLoading = false),
    initialSettings: SettingsState = SettingsState(),
    initialPlayer: PlayerState = PlayerState(),
    initialCapture: CaptureState = CaptureState(),
) : ReadingService {

    private val _library = MutableStateFlow(initialLibrary)
    override val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _player = MutableStateFlow(initialPlayer)
    override val player: StateFlow<PlayerState> = _player.asStateFlow()

    private val _capture = MutableStateFlow(initialCapture)
    override val capture: StateFlow<CaptureState> = _capture.asStateFlow()

    private val _settings = MutableStateFlow(initialSettings)
    override val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    var lastThemeMode: ThemeMode? = null
        private set

    var lastSeekIndex: Int? = null
        private set

    val speedChanges = mutableListOf<Float>()

    /** 测试连接时设置的状态。 */
    var stepFunTestResult: TestState = TestState.Success("连接成功，已合成试听音频")

    /** 保存 Key 时记录的明文，便于断言表单传递。 */
    var lastSavedStepFunKey: String? = null
        private set

    var lastLlmSave: Triple<String, String, String>? = null
        private set

    var llmKeyCleared: Boolean = false
        private set

    var retryL2Calls: Int = 0
        private set

    val voiceChanges = mutableListOf<String>()
    val previewedVoices = mutableListOf<String>()
    val retriedSegments = mutableListOf<Int>()
    var clearOldAudioTracksCalls: Int = 0
        private set

    /** 测试里可预设的存储占用；清理后旧音轨归零。 */
    var storageUsage: com.cnife.listenreader.service.StorageUsage =
        com.cnife.listenreader.service.StorageUsage(0, 0)

    private var savedCount = 0

    override fun refreshLibrary() {
        _library.update { it.copy(isLoading = false) }
    }

    override fun setLibraryQuery(query: String) {
        _library.update { it.copy(query = query) }
    }

    override fun setLibraryFilter(filter: LibraryFilter) {
        _library.update { it.copy(filter = filter) }
    }

    override fun openResource(resourceId: String) {
        val item = _library.value.items.firstOrNull { it.id == resourceId }
        _player.update {
            it.copy(resourceId = resourceId, title = item?.title.orEmpty())
        }
    }

    override fun deleteResource(resourceId: String) = Unit

    override fun beginUrlCapture(url: String?) {
        _capture.value = CaptureState(mode = CaptureMode.URL, url = url.orEmpty())
    }

    override fun beginTextCapture(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            _capture.value = CaptureState(
                mode = CaptureMode.TEXT,
                url = text,
                error = com.cnife.listenreader.core.UiError(
                    "请先粘贴要朗读的文本",
                    com.cnife.listenreader.core.ErrorKind.UNKNOWN,
                ),
            )
            return
        }
        val (title, body) = splitTitle(normalized)
        _capture.value = CaptureState(
            mode = CaptureMode.TEXT,
            url = text,
            extraction = ExtractionInfo(
                title = title,
                extractor = "文本输入",
                score = 1.0,
                confidence = 1.0,
                needsExpand = false,
            ),
            preview = newPreview(title, body),
        )
    }

    override fun handleShareIntent(text: String) {
        if (text.contains("http")) beginUrlCapture(text) else beginTextCapture(text)
    }

    override fun loadUrl(url: String) {
        _capture.update { it.copy(mode = CaptureMode.URL, url = url) }
    }

    override fun reloadPage() = Unit

    override fun pageBack(): Boolean = false

    override fun createPageView(context: Context): View? = null

    override fun releasePageView() = Unit

    override fun extractPage() = Unit

    override fun useFullPage() = Unit

    override fun retryL2() {
        retryL2Calls++
    }

    override fun updatePreviewTitle(title: String) {
        _capture.update { state ->
            val preview = state.preview ?: return@update state
            state.copy(preview = newPreview(title, preview.body))
        }
    }

    override fun updatePreviewBody(body: String) {
        _capture.update { state ->
            val preview = state.preview ?: return@update state
            state.copy(preview = newPreview(preview.title, body))
        }
    }

    override fun saveCapture(): String? {
        val preview = _capture.value.preview ?: return null
        val resourceId = "fake-resource-${++savedCount}"
        val item = LibraryItem(
            id = resourceId,
            title = preview.title,
            sourceKind = SourceKind.TEXT,
            sourceLabel = preview.sourceLabel,
            segmentCount = preview.segmentDrafts.size,
            durationMs = 0,
            hasCachedAudio = false,
            currentSegmentIndex = 0,
            currentPositionMs = 0,
            lastOpenedAt = 0,
        )
        _library.update { it.copy(isLoading = false, items = it.items + item) }
        _capture.update { it.copy(isSaving = false, savedResourceId = resourceId) }
        return resourceId
    }

    private fun newPreview(title: String, body: String) = PreviewState(
        title = title,
        body = body,
        charCount = body.length,
        segmentDrafts = RuleSegmenter.segment(title, body),
        segmentation = SegmentationSource.RULE,
        l2Status = L2Status.NOT_RUN,
        sourceKind = SourceKind.TEXT,
        sourceLabel = "粘贴文本",
        extractor = "文本输入",
        score = 1.0,
    )

    private fun splitTitle(text: String): Pair<String, String> {
        val lines = text.lines()
        val first = lines.firstOrNull { it.isNotBlank() }?.trim() ?: return "粘贴文本" to text
        val rest = lines.drop(lines.indexOfFirst { it.isNotBlank() } + 1).joinToString("\n").trim()
        val titleLike = first.length <= 80 && first.last() !in charArrayOf('。', '！', '？', '!', '?', '；', ';', '…')
        return if (titleLike && rest.isNotEmpty()) first to rest else "粘贴文本" to text
    }

    override fun discardCapture() {
        _capture.value = CaptureState()
    }

    override fun play() {
        _player.update { it.copy(isPlaying = true) }
    }

    override fun pause() {
        _player.update { it.copy(isPlaying = false) }
    }

    override fun togglePlay() {
        _player.update { it.copy(isPlaying = !it.isPlaying) }
    }

    override fun nextSegment() {
        _player.update { it.copy(currentIndex = it.currentIndex + 1) }
    }

    override fun previousSegment() {
        _player.update { it.copy(currentIndex = (it.currentIndex - 1).coerceAtLeast(0)) }
    }

    override fun seekToSegment(index: Int) {
        lastSeekIndex = index
        _player.update { it.copy(currentIndex = index, positionInSegmentMs = 0) }
    }

    override fun setSpeed(speed: Float) {
        speedChanges += speed
        _player.update { it.copy(speed = speed) }
    }

    override fun setSleepTimer(minutes: Int?) {
        _player.update { it.copy(sleepTimerMinutes = minutes) }
    }

    override fun setVoice(voiceId: String) {
        voiceChanges += voiceId
        _settings.update { it.copy(voice = voiceId) }
    }

    override fun setModel(modelId: String) {
        _settings.update { it.copy(ttsModel = modelId) }
    }

    override fun retrySegment(index: Int) {
        retriedSegments += index
    }

    override fun toggleFollow() {
        _player.update { it.copy(follow = !it.follow) }
    }

    override fun closePlayer() {
        _player.value = PlayerState()
    }

    override fun saveStepFunKey(key: String) {
        lastSavedStepFunKey = key
        _settings.update { it.copy(stepFunKeyConfigured = key.isNotBlank()) }
    }

    override fun saveLlmConfig(endpoint: String, key: String, model: String) {
        lastLlmSave = Triple(endpoint, key, model)
        _settings.update {
            it.copy(
                llmEndpoint = endpoint.ifBlank { it.llmEndpoint },
                llmModel = model.ifBlank { it.llmModel },
                llmKeyConfigured = key.isNotBlank() || it.llmKeyConfigured,
            )
        }
    }

    override fun setLlmKey(key: String) {
        llmKeyCleared = key.isBlank()
        _settings.update { it.copy(llmKeyConfigured = key.isNotBlank()) }
    }

    override fun setThemeMode(mode: ThemeMode) {
        lastThemeMode = mode
        _settings.update { it.copy(themeMode = mode) }
    }

    override fun setPrefetchCount(count: Int) {
        _settings.update { it.copy(prefetchCount = count) }
    }

    override fun testStepFun() {
        _settings.update { it.copy(stepFunTest = TestState.Running) }
        _settings.update { it.copy(stepFunTest = stepFunTestResult) }
    }

    override fun testLlm() {
        _settings.update { it.copy(llmTest = TestState.Success("LLM 连接正常")) }
    }

    override fun previewVoice(voiceId: String) {
        previewedVoices += voiceId
        _settings.update { it.copy(auditionVoice = voiceId, isAuditioning = false, auditionError = null) }
    }

    override fun clearOldAudioTracks() {
        clearOldAudioTracksCalls++
        _settings.update {
            it.copy(
                storage = com.cnife.listenreader.service.StorageUsage(storageUsage.activeBytes, 0),
                message = "已清理旧音轨",
            )
        }
    }

    override fun refreshStorageUsage() {
        _settings.update { it.copy(storage = storageUsage) }
    }
}
