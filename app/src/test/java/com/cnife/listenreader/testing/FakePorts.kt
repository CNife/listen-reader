package com.cnife.listenreader.testing

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.cnife.listenreader.model.AudioClip
import com.cnife.listenreader.model.AudioTrack
import com.cnife.listenreader.model.ClipStatus
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
import com.cnife.listenreader.tts.SpeechException
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
    private val resources = mutableMapOf<String, Resource>()
    private val segmentsByResource = mutableMapOf<String, List<Segment>>()
    private val tracksByResource = mutableMapOf<String, MutableList<AudioTrack>>()
    private val clipsByTrack = mutableMapOf<String, MutableList<AudioClip>>()

    /** 让行为测试能模拟保存失败。 */
    var failCreate: Boolean = false

    var lastOpened: String? = null
    var audioRoot: File = File("build/tmp/audio")
    val progressWrites = mutableListOf<Pair<Int, Long>>()
    val openedCalls = mutableListOf<String>()

    fun addResource(
        resource: Resource,
        segments: List<Segment> = emptyList(),
        tracks: List<AudioTrack> = emptyList(),
        clips: List<AudioClip> = emptyList(),
    ) {
        resources[resource.id] = resource
        segmentsByResource[resource.id] = segments
        tracksByResource[resource.id] = tracks.toMutableList()
        clips.forEach { clip -> clipsByTrack.getOrPut(clip.trackId) { mutableListOf() }.add(clip) }
    }

    fun clip(trackId: String, segmentIndex: Int): AudioClip? =
        clipsByTrack[trackId]?.firstOrNull { it.segmentIndex == segmentIndex }

    override fun observeItems(): Flow<List<LibraryItem>> = items

    override suspend fun getResource(id: String): Resource? = resources[id]

    override suspend fun getSegments(resourceId: String): List<Segment> =
        segmentsByResource[resourceId].orEmpty()

    override suspend fun getTracks(resourceId: String): List<AudioTrack> =
        tracksByResource[resourceId].orEmpty()

    override suspend fun getClips(trackId: String): List<AudioClip> =
        clipsByTrack[trackId].orEmpty()

    override suspend fun createResource(resource: Resource, sourceText: String, html: String?): String {
        if (failCreate) throw IllegalStateException("disk full")
        return resource.id
    }

    override suspend fun updateProgress(resourceId: String, segmentIndex: Int, positionMs: Long) {
        progressWrites += segmentIndex to positionMs
        resources[resourceId]?.let { resources[resourceId] = it.copy(currentSegmentIndex = segmentIndex, currentPositionMs = positionMs) }
    }

    override suspend fun markOpened(resourceId: String, at: Long) {
        openedCalls += resourceId
        lastOpened = resourceId
        resources[resourceId]?.let { resources[resourceId] = it.copy(lastOpenedAt = at) }
    }

    override suspend fun setActiveTrack(resourceId: String, trackId: String?) {
        resources[resourceId]?.let { resources[resourceId] = it.copy(activeTrackId = trackId) }
    }

    override suspend fun createTrack(track: AudioTrack) {
        tracksByResource.getOrPut(track.resourceId) { mutableListOf() }.add(track)
    }

    override suspend fun upsertClip(clip: AudioClip) {
        val list = clipsByTrack.getOrPut(clip.trackId) { mutableListOf() }
        list.removeAll { it.id == clip.id || it.segmentIndex == clip.segmentIndex }
        list.add(clip)
    }

    override suspend fun deleteResource(resourceId: String) = Unit

    override suspend fun snapshotText(resourceId: String): String? = null

    override suspend fun readSnapshot(path: String): String? = null

    override fun audioFile(resourceId: String, trackId: String, segmentIndex: Int): File =
        File(audioRoot, "$resourceId/$trackId/$segmentIndex.mp3")

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

    override suspend fun setPlaybackSpeed(speed: Float) {
        state.update { it.copy(playbackSpeed = speed) }
    }

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

class FakeSpeechSynthesizer(
    var configured: Boolean = false,
    var durationMs: Long = 1_200L,
    var failure: SpeechException? = null,
) : SpeechSynthesizer {
    data class Call(val text: String, val model: String, val voice: String, val destination: File)

    val calls = mutableListOf<Call>()
    var testResult: ConnectionTestResult = ConnectionTestResult(false, "not configured")
    var lastTestModel: String? = null
    var lastTestVoice: String? = null

    override fun isConfigured(): Boolean = configured

    override suspend fun synthesize(
        text: String,
        model: String,
        voice: String,
        destination: File,
    ): Long {
        calls += Call(text, model, voice, destination)
        failure?.let { throw it }
        destination.parentFile?.mkdirs()
        destination.writeBytes(byteArrayOf(0x49, 0x44, 0x33))
        return durationMs
    }

    override suspend fun testConnection(model: String, voice: String): ConnectionTestResult {
        lastTestModel = model
        lastTestVoice = voice
        return testResult
    }
}

class FakePlaybackEngine : PlaybackEngine {
    private val engineState = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Idle)
    override val state: StateFlow<PlaybackEngineState> = engineState.asStateFlow()

    var preparedFile: File? = null
    var preparedStartMs: Long = 0L
    var readyDurationMs: Long = 0L
    var playbackSpeed: Float = 1.0f
    val events = mutableListOf<String>()

    override suspend fun prepare(file: File, startPositionMs: Long) {
        preparedFile = file
        preparedStartMs = startPositionMs
        events += "prepare"
        engineState.value = PlaybackEngineState.Ready(readyDurationMs, false, startPositionMs)
    }

    override fun play() {
        events += "play"
        engineState.value = PlaybackEngineState.Ready(readyDurationMs, true, preparedStartMs)
    }

    override fun pause() {
        events += "pause"
        val current = engineState.value as? PlaybackEngineState.Ready
        engineState.value = PlaybackEngineState.Ready(
            durationMs = current?.durationMs ?: readyDurationMs,
            isPlaying = false,
            positionMs = current?.positionMs ?: 0,
        )
    }

    override fun seekTo(ms: Long) {
        events += "seek:$ms"
        engineState.value = PlaybackEngineState.Ready(readyDurationMs, false, ms)
    }

    override fun setSpeed(speed: Float) {
        this.playbackSpeed = speed
    }

    override fun release() {
        events += "release"
        engineState.value = PlaybackEngineState.Idle
    }

    fun emit(state: PlaybackEngineState) {
        engineState.value = state
    }
}

class FixedTimeProvider(private val millis: Long = 1_000L) : TimeProvider {
    override fun now(): Long = millis
}

fun testSegment(resourceId: String, index: Int, text: String) = Segment(
    resourceId = resourceId,
    index = index,
    text = text,
    kind = com.cnife.listenreader.model.SegmentKind.PARAGRAPH,
)

fun testClip(
    trackId: String,
    index: Int,
    file: File,
    durationMs: Long = 1_000L,
    status: ClipStatus = ClipStatus.READY,
) = AudioClip(
    id = "$trackId-$index",
    trackId = trackId,
    segmentIndex = index,
    filePath = file.absolutePath,
    durationMs = durationMs,
    status = status,
    error = null,
)
