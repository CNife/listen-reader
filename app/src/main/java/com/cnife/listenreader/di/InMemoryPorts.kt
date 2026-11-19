package com.cnife.listenreader.di

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.cnife.listenreader.model.AudioClip
import com.cnife.listenreader.model.AudioTrack
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.Segment
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.StorageUsage
import com.cnife.listenreader.service.ports.AppSettings
import com.cnife.listenreader.service.ports.CleanResult
import com.cnife.listenreader.service.ports.ConnectionTestResult
import com.cnife.listenreader.service.ports.LibraryStore
import com.cnife.listenreader.service.ports.PageExtraction
import com.cnife.listenreader.service.ports.PageSession
import com.cnife.listenreader.service.ports.PageSessionState
import com.cnife.listenreader.service.ports.PlaybackEngine
import com.cnife.listenreader.service.ports.PlaybackEngineState
import com.cnife.listenreader.service.ports.ScriptCleaner
import com.cnife.listenreader.service.ports.SettingsStore
import com.cnife.listenreader.service.ports.SpeechSynthesizer
import com.cnife.listenreader.service.ports.TimeProvider
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * T1 的生产占位端口实现：资料库为空、设置保存在内存中。
 * 后续 ticket 会用真实 Room / DataStore / WebView / TTS / Media3 适配器替换。
 */
class InMemoryLibraryStore(context: Context) : LibraryStore {

    private val audioRoot = File(context.filesDir, "audio")
    private val snapshotRoot = File(context.filesDir, "sources")
    private val items = MutableStateFlow<List<LibraryItem>>(emptyList())
    private var lastOpened: String? = null

    override fun observeItems(): Flow<List<LibraryItem>> = items

    override suspend fun getResource(id: String): Resource? = null

    override suspend fun getSegments(resourceId: String): List<Segment> = emptyList()

    override suspend fun getTracks(resourceId: String): List<AudioTrack> = emptyList()

    override suspend fun getClips(trackId: String): List<AudioClip> = emptyList()

    override suspend fun createResource(resource: Resource, sourceText: String, html: String?): String {
        throw UnsupportedOperationException("RoomLibraryStore 将在 T2 接入")
    }

    override suspend fun updateProgress(resourceId: String, segmentIndex: Int, positionMs: Long) = Unit

    override suspend fun markOpened(resourceId: String, at: Long) {
        lastOpened = resourceId
    }

    override suspend fun setActiveTrack(resourceId: String, trackId: String?) = Unit

    override suspend fun createTrack(track: AudioTrack) = Unit

    override suspend fun upsertClip(clip: AudioClip) = Unit

    override suspend fun deleteResource(resourceId: String) = Unit

    override suspend fun snapshotText(resourceId: String): String? = null

    override suspend fun readSnapshot(path: String): String? = null

    override fun audioFile(resourceId: String, trackId: String, segmentIndex: Int): File =
        File(audioRoot, "$resourceId/$trackId/$segmentIndex.mp3")

    override fun snapshotFile(resourceId: String): File = File(snapshotRoot, "$resourceId.md")

    override fun fileSize(path: String?): Long =
        if (path.isNullOrBlank()) 0L else File(path).takeIf { it.exists() }?.length() ?: 0L

    override suspend fun deleteUnusedTracks(): Long = 0L

    override fun lastOpenedResourceId(): String? = lastOpened

    override fun saveLastOpenedResourceId(resourceId: String?) {
        lastOpened = resourceId
    }

    override fun totalActiveAudioBytes(): Long = 0L

    override fun computeStorageUsage(): StorageUsage = StorageUsage(0, 0)
}

class InMemorySettingsStore : SettingsStore {

    private val state = MutableStateFlow(AppSettings())
    private var stepFunKey: String? = null
    private var llmKey: String? = null

    override val settings: Flow<AppSettings> = state

    override suspend fun setThemeMode(mode: ThemeMode) {
        state.update { it.copy(themeMode = mode) }
    }

    override suspend fun setTtsModel(model: String) {
        state.update { it.copy(ttsModel = model) }
    }

    override suspend fun setVoice(voice: String) {
        state.update { it.copy(voice = voice) }
    }

    override suspend fun setPrefetchCount(count: Int) {
        state.update { it.copy(prefetchCount = count) }
    }

    override suspend fun setPlaybackSpeed(speed: Float) = Unit

    override suspend fun setStepFunKey(key: String) {
        stepFunKey = key.ifBlank { null }
        state.update { it.copy(stepFunKeyConfigured = stepFunKey != null) }
    }

    override suspend fun stepFunKey(): String? = stepFunKey

    override suspend fun setLlmConfig(endpoint: String, model: String, key: String?) {
        llmKey = key
        state.update {
            it.copy(llmEndpoint = endpoint, llmModel = model, llmKeyConfigured = key != null)
        }
    }

    override suspend fun llmEndpoint(): String = state.value.llmEndpoint

    override suspend fun llmModel(): String = state.value.llmModel

    override suspend fun llmKey(): String? = llmKey
}

class SystemTimeProvider : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}

/** T6 之前的空页面会话。 */
class UnavailablePageSession : PageSession {
    private val pageState = MutableStateFlow<PageSessionState>(PageSessionState.Idle)
    override val state: StateFlow<PageSessionState> = pageState.asStateFlow()

    override fun createView(context: Context): View = FrameLayout(context)

    override fun load(url: String) {
        pageState.value = PageSessionState.Failed("页面采集将在后续版本接入")
    }

    override fun reload() = Unit

    override fun goBack(): Boolean = false

    override suspend fun extract(): PageExtraction = emptyExtraction()

    override suspend fun expandAndExtract(): PageExtraction = extract()

    override fun release() = Unit

    private fun emptyExtraction() = PageExtraction(
        title = "",
        byline = null,
        publishedAt = null,
        markdown = "",
        text = "",
        pageText = "",
        extractor = "unavailable",
        score = 0.0,
        confidence = 0.0,
        needsExpand = false,
    )
}

/** T4 之前的空 L2 清洗器。 */
class UnconfiguredScriptCleaner : ScriptCleaner {
    override fun isConfigured(): Boolean = false

    override suspend fun clean(title: String, markdown: String): CleanResult =
        CleanResult.Failure("L2 清洗将在后续版本接入")

    override suspend fun testConnection(): ConnectionTestResult =
        ConnectionTestResult(false, "尚未配置 LLM")
}

/** T3 之前的空 TTS 客户端。 */
class UnconfiguredSpeechSynthesizer : SpeechSynthesizer {
    override fun isConfigured(): Boolean = false

    override suspend fun synthesize(text: String, model: String, voice: String, destination: File): Long {
        throw UnsupportedOperationException("StepFun 合成将在 T3 接入")
    }

    override suspend fun testConnection(model: String, voice: String): ConnectionTestResult =
        ConnectionTestResult(false, "尚未配置 StepFun Key")
}

/** T3/T8 之前的空播放引擎。 */
class NoopPlaybackEngine : PlaybackEngine {
    private val engineState = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Idle)
    override val state: StateFlow<PlaybackEngineState> = engineState.asStateFlow()

    override suspend fun prepare(file: File, startPositionMs: Long) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(ms: Long) = Unit

    override fun setSpeed(speed: Float) = Unit

    override fun release() = Unit
}
