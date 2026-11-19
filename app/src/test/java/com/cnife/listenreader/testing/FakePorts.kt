package com.cnife.listenreader.testing

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

class FakeLibraryStore(
    initialItems: List<LibraryItem> = emptyList(),
) : LibraryStore {
    private val items = MutableStateFlow(initialItems)
    private var lastOpened: String? = null

    override fun observeItems(): Flow<List<LibraryItem>> = items

    override suspend fun getResource(id: String): Resource? = null

    override suspend fun getSegments(resourceId: String): List<Segment> = emptyList()

    override suspend fun getTracks(resourceId: String): List<AudioTrack> = emptyList()

    override suspend fun getClips(trackId: String): List<AudioClip> = emptyList()

    override suspend fun createResource(resource: Resource, sourceText: String, html: String?): String =
        resource.id

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
        File("build/tmp/audio/$resourceId/$trackId/$segmentIndex.mp3")

    override fun snapshotFile(resourceId: String): File = File("build/tmp/sources/$resourceId.md")

    override fun fileSize(path: String?): Long = 0L

    override suspend fun deleteUnusedTracks(): Long = 0L

    override fun lastOpenedResourceId(): String? = lastOpened

    override fun saveLastOpenedResourceId(resourceId: String?) {
        lastOpened = resourceId
    }

    override fun totalActiveAudioBytes(): Long = 0L

    override fun computeStorageUsage(): StorageUsage = StorageUsage(0, 0)
}

class FakeSettingsStore(
    initial: AppSettings = AppSettings(),
) : SettingsStore {
    private val state = MutableStateFlow(initial)
    private var stepFunKey: String? = null
    private var llmKey: String? = null

    override val settings: Flow<AppSettings> = state

    val current: AppSettings get() = state.value

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

class FakePageSession : PageSession {
    private val pageState = MutableStateFlow<PageSessionState>(PageSessionState.Idle)
    override val state: StateFlow<PageSessionState> = pageState.asStateFlow()

    var loadedUrl: String? = null

    override fun createView(context: Context): View = FrameLayout(context)

    override fun load(url: String) {
        loadedUrl = url
    }

    override fun reload() = Unit

    override fun goBack(): Boolean = false

    override suspend fun extract(): PageExtraction = PageExtraction(
        title = "",
        byline = null,
        publishedAt = null,
        markdown = "",
        text = "",
        pageText = "",
        extractor = "fake",
        score = 0.0,
        confidence = 0.0,
        needsExpand = false,
    )

    override suspend fun expandAndExtract(): PageExtraction = extract()

    override fun release() = Unit
}

class FakeScriptCleaner : ScriptCleaner {
    override fun isConfigured(): Boolean = false
    override suspend fun clean(title: String, markdown: String): CleanResult =
        CleanResult.Failure("not configured")
    override suspend fun testConnection(): ConnectionTestResult =
        ConnectionTestResult(false, "not configured")
}

class FakeSpeechSynthesizer : SpeechSynthesizer {
    override fun isConfigured(): Boolean = false
    override suspend fun synthesize(text: String, model: String, voice: String, destination: File): Long =
        0L
    override suspend fun testConnection(model: String, voice: String): ConnectionTestResult =
        ConnectionTestResult(false, "not configured")
}

class FakePlaybackEngine : PlaybackEngine {
    private val engineState = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Idle)
    override val state: StateFlow<PlaybackEngineState> = engineState.asStateFlow()

    override suspend fun prepare(file: File, startPositionMs: Long) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(ms: Long) = Unit
    override fun setSpeed(speed: Float) = Unit
    override fun release() = Unit
}

class FixedTimeProvider(private val millis: Long = 1_000L) : TimeProvider {
    override fun now(): Long = millis
}
