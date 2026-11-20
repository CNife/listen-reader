package com.cnife.listenreader.testing

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.cnife.listenreader.core.RuleSegmenter
import com.cnife.listenreader.model.AudioClip
import com.cnife.listenreader.model.AudioTrack
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.Segment
import com.cnife.listenreader.model.SegmentDraft
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.StorageUsage
import com.cnife.listenreader.service.ports.AppSettings
import com.cnife.listenreader.service.ports.AuditionPlayer
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
import kotlinx.coroutines.CompletableDeferred
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

    /** 让行为测试能模拟删除失败。 */
    var failDelete: Boolean = false

    /** 让行为测试能模拟快照读取失败。 */
    var failSnapshot: Boolean = false

    private val snapshots = mutableMapOf<String, String>()

    var lastOpened: String? = null
    var audioRoot: File = File("build/tmp/audio")
    val progressWrites = mutableListOf<Pair<Int, Long>>()
    val openedCalls = mutableListOf<String>()

    /** 保存时传入的 Segment 草稿，供行为测试断言持久化的是当前预览。 */
    var savedSegments: List<SegmentDraft> = emptyList()
        private set
    var createdResource: Resource? = null
        private set
    var createdSourceText: String? = null
        private set
    var createdHtml: String? = null
        private set
    var createCount: Int = 0
        private set

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
        upsertItem(resource)
    }

    /** 与 RoomLibraryStore 一致，把 Resource 映射成资料库列表项。 */
    private fun upsertItem(resource: Resource) {
        val segments = segmentsByResource[resource.id].orEmpty()
        val activeTrackId = resource.activeTrackId
        val readyCount = activeTrackId?.let { id ->
            clipsByTrack[id].orEmpty().count { it.status == ClipStatus.READY }
        } ?: 0
        val durationMs = activeTrackId?.let { id ->
            clipsByTrack[id].orEmpty().filter { it.status == ClipStatus.READY }.sumOf { it.durationMs }
        } ?: 0L
        val item = LibraryItem(
            id = resource.id,
            title = resource.title,
            sourceKind = resource.sourceKind,
            sourceLabel = when (resource.sourceKind) {
                com.cnife.listenreader.model.SourceKind.TEXT -> "粘贴文本"
                com.cnife.listenreader.model.SourceKind.URL ->
                    resource.sourceUrl?.removePrefix("https://")?.removePrefix("http://")
                        ?.substringBefore('/')?.removePrefix("www.").orEmpty()
            },
            segmentCount = segments.size,
            durationMs = durationMs,
            hasCachedAudio = activeTrackId != null && segments.isNotEmpty() && readyCount >= segments.size,
            currentSegmentIndex = resource.currentSegmentIndex,
            currentPositionMs = resource.currentPositionMs,
            lastOpenedAt = resource.lastOpenedAt,
        )
        items.update { current -> current.filterNot { it.id == item.id } + item }
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

    override suspend fun createResource(
        resource: Resource,
        sourceText: String,
        html: String?,
        segments: List<SegmentDraft>,
    ): String {
        if (failCreate) throw IllegalStateException("disk full")
        createdResource = resource
        createdSourceText = sourceText
        createdHtml = html
        savedSegments = segments.ifEmpty { RuleSegmenter.segment(resource.title, sourceText) }
        createCount++
        return resource.id
    }

    override suspend fun updateProgress(resourceId: String, segmentIndex: Int, positionMs: Long) {
        progressWrites += segmentIndex to positionMs
        resources[resourceId]?.let {
            resources[resourceId] = it.copy(currentSegmentIndex = segmentIndex, currentPositionMs = positionMs)
            upsertItem(resources.getValue(resourceId))
        }
    }

    override suspend fun markOpened(resourceId: String, at: Long) {
        openedCalls += resourceId
        lastOpened = resourceId
        resources[resourceId]?.let {
            resources[resourceId] = it.copy(lastOpenedAt = at)
            upsertItem(resources.getValue(resourceId))
        }
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

    override suspend fun deleteResource(resourceId: String) {
        if (failDelete) throw IllegalStateException("delete failed")
        resources.remove(resourceId)
        segmentsByResource.remove(resourceId)
        tracksByResource.remove(resourceId)?.forEach { clipsByTrack.remove(it.id) }
        if (lastOpened == resourceId) lastOpened = null
        items.update { current -> current.filterNot { it.id == resourceId } }
    }

    override suspend fun snapshotText(resourceId: String): String? {
        if (failSnapshot) throw IllegalStateException("snapshot unreadable")
        return snapshots[resourceId]
    }

    /** 写入某 Resource 的 Markdown 快照，模拟离线可读。 */
    fun setSnapshot(resourceId: String, text: String) {
        snapshots[resourceId] = text
    }

    override fun audioFile(resourceId: String, trackId: String, segmentIndex: Int): File =
        File(audioRoot, "$resourceId/$trackId/$segmentIndex.mp3")

    override fun snapshotFile(resourceId: String): File = File("build/tmp/sources/$resourceId.md")

    val deletedTrackIds = mutableListOf<String>()

    override suspend fun deleteUnusedTracks(): Long {
        val active = resources.values.mapNotNull { it.activeTrackId }.toSet()
        val unused = tracksByResource.values.flatten().filter { it.id !in active }
        var freed = 0L
        unused.forEach { track ->
            val directory = File(audioRoot, "${track.resourceId}/${track.id}")
            freed += directorySize(directory)
            deletedTrackIds += track.id
            tracksByResource[track.resourceId]?.removeAll { it.id == track.id }
            clipsByTrack.remove(track.id)
            directory.deleteRecursively()
        }
        return freed
    }

    override suspend fun lastOpenedResourceId(): String? = lastOpened

    override fun saveLastOpenedResourceId(resourceId: String?) {
        lastOpened = resourceId
    }

    override suspend fun computeStorageUsage(): StorageUsage = computeUsage()

    private fun computeUsage(): StorageUsage {
        var activeBytes = 0L
        var oldBytes = 0L
        val resourceDirs = audioRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        resourceDirs.forEach { resourceDir ->
            val activeTrackId = resources[resourceDir.name]?.activeTrackId
            val trackDirs = resourceDir.listFiles()?.filter { it.isDirectory }.orEmpty()
            trackDirs.forEach { trackDir ->
                val bytes = directorySize(trackDir)
                if (trackDir.name == activeTrackId) {
                    activeBytes += bytes
                } else {
                    oldBytes += bytes
                }
            }
        }
        return StorageUsage(activeBytes, oldBytes)
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

class FakeSettingsStore(
    initial: AppSettings = AppSettings(),
) : SettingsStore {
    private val state = MutableStateFlow(initial)
    private var stepFunKey: String? = null
    private var qwenKey: String? = null
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

    override suspend fun setQwenKey(key: String) {
        qwenKey = key.ifBlank { null }
        state.update { it.copy(qwenKeyConfigured = qwenKey != null) }
    }

    override suspend fun qwenKey(): String? = qwenKey
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
    var reloadCount: Int = 0
    var backCalls: Int = 0
    var releaseCount: Int = 0
    var canGoBack: Boolean = false

    /** 页面状态可由行为测试驱动。 */
    var pageStateValue: PageSessionState
        get() = pageState.value
        set(value) { pageState.value = value }

    /** extract() 返回值；失败场景直接抛异常。 */
    var extraction: PageExtraction = PageExtraction(
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
    var extractionError: Throwable? = null

    /** expandAndExtract 的返回值；为空时退回 [extraction]。 */
    var expandedExtraction: PageExtraction? = null
    var expandCalls: Int = 0
    var expandError: Throwable? = null

    override fun createView(context: Context): View = FrameLayout(context)

    override fun load(url: String) {
        loadedUrl = url
        pageState.value = PageSessionState.Loading(0)
    }

    override fun reload() {
        reloadCount++
        pageState.value = PageSessionState.Loading(0)
    }

    override fun goBack(): Boolean {
        backCalls++
        return canGoBack
    }

    override suspend fun extract(): PageExtraction {
        extractionError?.let { throw it }
        return extraction
    }

    override suspend fun expandAndExtract(): PageExtraction {
        expandCalls++
        expandError?.let { throw it }
        expandedExtraction?.let { return it }
        return extract()
    }

    override fun release() {
        releaseCount++
        pageState.value = PageSessionState.Idle
    }
}

class FakeScriptCleaner(
    var configured: Boolean = false,
    var result: CleanResult = CleanResult.Failure("not configured", retryable = false),
    var testResult: ConnectionTestResult = ConnectionTestResult(false, "not configured"),
    var gate: CompletableDeferred<Unit>? = null,
) : ScriptCleaner {

    val calls = mutableListOf<Pair<String, String>>()

    override fun isConfigured(): Boolean = configured

    override suspend fun clean(title: String, markdown: String): CleanResult {
        calls += title to markdown
        gate?.await()
        return result
    }

    override suspend fun testConnection(): ConnectionTestResult = testResult
}

class FakeSpeechSynthesizer(
    var durationMs: Long = 1_200L,
    var failure: SpeechException? = null,
) : SpeechSynthesizer {
    data class Call(val text: String, val model: String, val voice: String, val destination: File)

    val calls = mutableListOf<Call>()
    var testResult: ConnectionTestResult = ConnectionTestResult(false, "not configured")
    var lastTestModel: String? = null
    var lastTestVoice: String? = null

    /** 按文本注入失败，用于只让某个预取 Segment 失败。 */
    var failureFor: ((String) -> SpeechException?)? = null

    /** 让合成挂起一段时间，用于观察并发上限与合成中状态。 */
    var synthDelayMs: Long = 0L

    var concurrent: Int = 0
        private set
    var maxConcurrent: Int = 0
        private set

    override suspend fun synthesize(
        text: String,
        model: String,
        voice: String,
        destination: File,
    ): Long {
        calls += Call(text, model, voice, destination)
        concurrent++
        maxConcurrent = maxOf(maxConcurrent, concurrent)
        try {
            if (synthDelayMs > 0L) kotlinx.coroutines.delay(synthDelayMs)
            failureFor?.invoke(text)?.let { throw it }
            failure?.let { throw it }
            destination.parentFile?.mkdirs()
            destination.writeBytes(byteArrayOf(0x49, 0x44, 0x33))
            return durationMs
        } finally {
            concurrent--
        }
    }

    override suspend fun testConnection(model: String, voice: String): ConnectionTestResult {
        lastTestModel = model
        lastTestVoice = voice
        return testResult
    }
}

class FakeAuditionPlayer : AuditionPlayer {
    val played = mutableListOf<File>()
    var stops: Int = 0
        private set

    override fun play(file: File) {
        played += file
    }

    override fun stop() {
        stops++
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
