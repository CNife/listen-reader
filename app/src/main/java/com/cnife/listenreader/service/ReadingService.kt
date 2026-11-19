package com.cnife.listenreader.service

import android.content.Context
import android.view.View
import com.cnife.listenreader.core.UiError
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.model.LibraryFilter
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.LlmDefaults
import com.cnife.listenreader.model.SegmentDraft
import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.model.SegmentationSource
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import kotlinx.coroutines.flow.StateFlow

interface ReadingService {
    val library: StateFlow<LibraryState>
    val player: StateFlow<PlayerState>
    val capture: StateFlow<CaptureState>
    val settings: StateFlow<SettingsState>

    // --- 资料库 ---
    fun refreshLibrary()
    fun setLibraryQuery(query: String)
    fun setLibraryFilter(filter: LibraryFilter)
    fun openResource(resourceId: String)
    fun deleteResource(resourceId: String)

    // --- Capture / 预览 ---
    fun beginUrlCapture(url: String? = null)
    fun beginTextCapture(text: String = "")
    fun handleShareIntent(text: String)
    fun loadUrl(url: String)
    fun reloadPage()
    fun pageBack(): Boolean
    fun createPageView(context: Context): View?
    fun releasePageView()
    fun extractPage()
    fun useFullPage()
    fun retryL2()
    fun updatePreviewTitle(title: String)
    fun updatePreviewBody(body: String)
    fun saveCapture(): String?
    fun discardCapture()

    // --- 播放 ---
    fun play()
    fun pause()
    fun togglePlay()
    fun nextSegment()
    fun previousSegment()
    fun seekToSegment(index: Int)
    fun setSpeed(speed: Float)
    fun setSleepTimer(minutes: Int?)
    fun setVoice(voiceId: String)
    fun setModel(modelId: String)
    fun retrySegment(index: Int)
    fun toggleFollow()
    fun closePlayer()

    // --- 设置 ---
    fun saveStepFunKey(key: String)
    fun saveLlmConfig(endpoint: String, key: String, model: String)
    fun setLlmKey(key: String)
    fun setThemeMode(mode: ThemeMode)
    fun setPrefetchCount(count: Int)
    fun testStepFun()
    fun testLlm()
    fun previewVoice(voiceId: String)
    fun clearOldAudioTracks()
    fun refreshStorageUsage()
}

data class LibraryState(
    val isLoading: Boolean = true,
    val items: List<LibraryItem> = emptyList(),
    val continueListening: LibraryItem? = null,
    val query: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val error: UiError? = null,
)

data class PlayerSegment(
    val index: Int,
    val text: String,
    val kind: SegmentKind,
    val durationMs: Long,
    val status: ClipStatus,
)

data class AudioTrackInfo(val id: String, val model: String, val voice: String)

data class PlayerState(
    val resourceId: String? = null,
    val title: String = "",
    val sourceKind: SourceKind? = null,
    val sourceLabel: String = "",
    val segments: List<PlayerSegment> = emptyList(),
    val currentIndex: Int = 0,
    val positionInSegmentMs: Long = 0,
    val totalDurationMs: Long = 0,
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val speed: Float = 1.0f,
    val sleepTimerMinutes: Int? = null,
    val sleepTimerRemainingMs: Long? = null,
    val activeTrack: AudioTrackInfo? = null,
    val follow: Boolean = true,
    val error: UiError? = null,
    /** 原文 Markdown 快照；读取失败或缺失时为 null。 */
    val snapshotText: String? = null,
    /** 快照不可用时的可读原因；快照正常时为 null。 */
    val snapshotError: String? = null,
)

data class CaptureState(
    val mode: CaptureMode = CaptureMode.IDLE,
    val url: String = "",
    val page: PageUiState = PageUiState.Idle,
    val isExtracting: Boolean = false,
    val extraction: ExtractionInfo? = null,
    val preview: PreviewState? = null,
    val isSaving: Boolean = false,
    val savedResourceId: String? = null,
    val error: UiError? = null,
    /** 每次开始/丢弃采集会话自增；UI 用它避免重复导航。 */
    val sessionId: Int = 0,
    /** 每次（重新）生成预览自增；返回采集页后再次提取仍能导航到预览。 */
    val previewRevision: Int = 0,
)

enum class CaptureMode { IDLE, URL, TEXT }

sealed interface PageUiState {
    data object Idle : PageUiState
    data class Loading(val progress: Int = 0) : PageUiState
    data object Ready : PageUiState
    data class NeedsUserAction(val reason: String) : PageUiState
    data class Failed(val message: String) : PageUiState
}

data class ExtractionInfo(
    val title: String,
    val extractor: String,
    val score: Double,
    val confidence: Double,
    val needsExpand: Boolean,
    val usedFullPage: Boolean = false,
    val degradedReason: String? = null,
)

data class PreviewState(
    val title: String,
    val body: String,
    val charCount: Int,
    val segmentDrafts: List<SegmentDraft>,
    val segmentation: SegmentationSource,
    val l2Status: L2Status,
    val l2Error: String? = null,
    val sourceKind: SourceKind,
    val sourceLabel: String,
    val extractor: String? = null,
    val score: Double? = null,
    val sourceUrl: String? = null,
    val byline: String? = null,
    val publishedAt: String? = null,
)

data class SettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val ttsModel: String = TtsModels.DEFAULT,
    val voice: String = Voices.DEFAULT,
    val stepFunKeyConfigured: Boolean = false,
    val llmEndpoint: String = LlmDefaults.ENDPOINT,
    val llmModel: String = LlmDefaults.MODEL,
    val llmKeyConfigured: Boolean = false,
    val prefetchCount: Int = 2,
    val stepFunTest: TestState = TestState.Idle,
    val llmTest: TestState = TestState.Idle,
    val storage: StorageUsage = StorageUsage(0, 0),
    val isClearing: Boolean = false,
    val auditionVoice: String? = null,
    val isAuditioning: Boolean = false,
    val auditionError: String? = null,
    val message: String? = null,
    val error: UiError? = null,
)

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val message: String) : TestState
    data class Failure(val message: String) : TestState
}

data class StorageUsage(val activeBytes: Long, val oldTracksBytes: Long)
