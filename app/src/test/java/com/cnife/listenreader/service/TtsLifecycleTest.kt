package com.cnife.listenreader.service

import com.cnife.listenreader.core.ErrorKind
import com.cnife.listenreader.model.AudioTrack
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.ports.AppSettings
import com.cnife.listenreader.service.ports.PlaybackEngineState
import com.cnife.listenreader.testing.FakeAuditionPlayer
import com.cnife.listenreader.testing.FakeLibraryStore
import com.cnife.listenreader.testing.FakePlaybackEngine
import com.cnife.listenreader.testing.FakeSettingsStore
import com.cnife.listenreader.testing.FakeSpeechSynthesizer
import com.cnife.listenreader.testing.testClip
import com.cnife.listenreader.testing.testSegment
import com.cnife.listenreader.testing.testReadingService
import com.cnife.listenreader.tts.SpeechException
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TtsLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class Harness(
        val service: DefaultReadingService,
        val store: FakeLibraryStore,
        val synth: FakeSpeechSynthesizer,
        val engine: FakePlaybackEngine,
        val settings: FakeSettingsStore,
        val audition: FakeAuditionPlayer,
        val previewDir: File,
    )

    private fun TestScope.harness(
        store: FakeLibraryStore,
        synth: FakeSpeechSynthesizer = FakeSpeechSynthesizer(),
        engine: FakePlaybackEngine = FakePlaybackEngine(),
        appSettings: AppSettings = AppSettings(stepFunKeyConfigured = true),
    ): Harness {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val settings = FakeSettingsStore(appSettings)
        val audition = FakeAuditionPlayer()
        val previewDir = tempFolder.newFolder("previews")
        val service = testReadingService(
            scope = scope,
            speechSynthesizer = synth,
            libraryStore = store,
            playbackEngine = engine,
            settingsStore = settings,
            auditionPlayer = audition,
            previewDir = previewDir,
        )
        return Harness(service, store, synth, engine, settings, audition, previewDir)
    }

    private fun newStore(): FakeLibraryStore = FakeLibraryStore().apply {
        audioRoot = tempFolder.newFolder("audio-${System.nanoTime()}")
    }

    private fun addSegments(
        store: FakeLibraryStore,
        count: Int = 5,
        resourceId: String = RESOURCE_ID,
        currentIndex: Int = 0,
        positionMs: Long = 0L,
        activeTrackId: String = trackId(resourceId),
        tracks: List<AudioTrack> = listOf(track(resourceId, activeTrackId)),
        clips: List<com.cnife.listenreader.model.AudioClip> = emptyList(),
    ) {
        store.addResource(
            resource = resource(resourceId, currentIndex, positionMs, activeTrackId),
            segments = (0 until count).map { index -> testSegment(resourceId, index, segmentText(index)) },
            tracks = tracks,
            clips = clips,
        )
    }

    private fun readyClipFile(store: FakeLibraryStore, resourceId: String, trackId: String, index: Int, bytes: Int = 3): File =
        store.audioFile(resourceId, trackId, index).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes) { 0x49 })
        }

    // --- 预取 ---

    @Test
    fun `prefetch synthesizes the next segments in order after playback starts`() = runTest {
        val store = newStore()
        addSegments(store, count = 5)
        val h = harness(
            store = store,
            appSettings = AppSettings(stepFunKeyConfigured = true, prefetchCount = 2),
        )
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()

        assertEquals(listOf(segmentText(0), segmentText(1), segmentText(2)), h.synth.calls.map { it.text })
        assertEquals(
            listOf(0, 1, 2),
            h.synth.calls.map { call -> call.destination.name.removeSuffix(".mp3").toInt() },
        )
        val player = h.service.player.value
        assertEquals(ClipStatus.READY, player.segments[0].status)
        assertEquals(ClipStatus.READY, player.segments[1].status)
        assertEquals(ClipStatus.READY, player.segments[2].status)
        assertEquals(ClipStatus.PENDING, player.segments[3].status)
        assertTrue(player.isPlaying)
        assertFalse(player.isPreparing)
        assertTrue(h.engine.events.contains("play"))
    }

    @Test
    fun `prefetch respects the configured count`() = runTest {
        val store = newStore()
        addSegments(store, count = 5)
        val h = harness(
            store = store,
            appSettings = AppSettings(stepFunKeyConfigured = true, prefetchCount = 1),
        )
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()

        assertEquals(listOf(segmentText(0), segmentText(1)), h.synth.calls.map { it.text })
    }

    @Test
    fun `prefetch runs at most two syntheses at once and uses both slots`() = runTest {
        val store = newStore()
        addSegments(store, count = 5)
        val synth = FakeSpeechSynthesizer().apply { synthDelayMs = 1_000L }
        val h = harness(
            store = store,
            synth = synth,
            appSettings = AppSettings(stepFunKeyConfigured = true, prefetchCount = 4),
        )
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        // 当前句已完成，两个预取请求同时合成：此时应显示“正在合成”。
        assertTrue(h.service.player.value.isPreparing)
        assertEquals(2, synth.concurrent)

        advanceUntilIdle()
        assertEquals(2, synth.maxConcurrent)
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            synth.calls.map { call -> call.destination.name.removeSuffix(".mp3").toInt() }.sorted(),
        )
        assertFalse(h.service.player.value.isPreparing)
    }

    @Test
    fun `prefetch window follows the current segment and fills every configured slot`() = runTest {
        val store = newStore()
        addSegments(store, count = 6)
        val h = harness(
            store = store,
            appSettings = AppSettings(stepFunKeyConfigured = true, prefetchCount = 4),
        )
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()

        // 当前句 0 加后续 4 句都已合成，第 6 句不在窗口内。
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            h.synth.calls.map { call -> call.destination.name.removeSuffix(".mp3").toInt() }.sorted(),
        )
        val player = h.service.player.value
        assertEquals(ClipStatus.READY, player.segments[4].status)
        assertEquals(ClipStatus.PENDING, player.segments[5].status)
    }

    @Test
    fun `repeated prefetch scheduling does not synthesize an in-flight index twice`() = runTest {
        val store = newStore()
        addSegments(store, count = 5)
        val synth = FakeSpeechSynthesizer().apply { synthDelayMs = 1_000L }
        val h = harness(
            store = store,
            synth = synth,
            appSettings = AppSettings(stepFunKeyConfigured = true, prefetchCount = 2),
        )
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        // 第 1、2 句的预取正在合成；再次触发窗口计算不应重复请求同一句。
        h.service.setPrefetchCount(2)
        h.engine.emit(PlaybackEngineState.Ended(1_200L))
        runCurrent()
        advanceUntilIdle()

        val indices = synth.calls.map { call -> call.destination.name.removeSuffix(".mp3").toInt() }
        assertEquals(indices.distinct(), indices)
        assertEquals(listOf(0, 1, 2, 3), indices.sorted())
    }

    @Test
    fun `already cached segments are not synthesized again`() = runTest {
        val store = newStore()
        addSegments(store, count = 5)
        val h = harness(
            store = store,
            appSettings = AppSettings(stepFunKeyConfigured = true, prefetchCount = 2),
        )
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()
        assertEquals(3, h.synth.calls.size)

        h.service.seekToSegment(1)
        advanceUntilIdle()

        // 跳转后可以预取新的后续段，但已缓存的第 2 句不会再合成一次。
        assertEquals(1, h.synth.calls.count { it.text == segmentText(1) })
        assertEquals(1, h.service.player.value.currentIndex)
        assertEquals(ClipStatus.READY, h.service.player.value.segments[1].status)
    }

    @Test
    fun `prefetch failure marks only that clip and keeps current playback`() = runTest {
        val store = newStore()
        addSegments(store, count = 5)
        val synth = FakeSpeechSynthesizer().apply {
            failureFor = { text ->
                if (text == segmentText(1)) {
                    SpeechException(ErrorKind.NETWORK, "网络连接失败", retryable = true)
                } else {
                    null
                }
            }
        }
        val h = harness(
            store = store,
            synth = synth,
            appSettings = AppSettings(stepFunKeyConfigured = true, prefetchCount = 2),
        )
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()

        val player = h.service.player.value
        assertNull(player.error)
        assertTrue(player.isPlaying)
        assertEquals(ClipStatus.READY, player.segments[0].status)
        assertEquals(ClipStatus.ERROR, player.segments[1].status)
        assertEquals(ClipStatus.READY, player.segments[2].status)
        assertTrue(h.store.clip(TRACK_ID, 1)?.error.orEmpty().contains("需要联网合成"))

        synth.failureFor = null
        h.service.retrySegment(1)
        advanceUntilIdle()
        assertEquals(ClipStatus.READY, h.service.player.value.segments[1].status)
        assertEquals(ClipStatus.READY, h.store.clip(TRACK_ID, 1)?.status)
    }

    // --- 切轨 ---

    @Test
    fun `switching voice creates a new track and resumes position and playback`() = runTest {
        val store = newStore()
        val cachedFile = readyClipFile(store, RESOURCE_ID, TRACK_ID, 1)
        addSegments(
            store,
            count = 3,
            currentIndex = 1,
            positionMs = 500L,
            clips = listOf(testClip(TRACK_ID, 1, cachedFile)),
        )
        val h = harness(
            store = store,
            appSettings = AppSettings(stepFunKeyConfigured = true, prefetchCount = 0),
        )
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()

        assertTrue(h.service.player.value.isPlaying)
        assertEquals(500L, h.engine.preparedStartMs)
        assertTrue(h.synth.calls.isEmpty())

        h.service.setVoice(Voices.CIXING)
        advanceUntilIdle()

        assertEquals(Voices.CIXING, h.settings.current.voice)
        assertEquals(Voices.CIXING, h.service.player.value.activeTrack?.voice)
        val tracks = h.store.getTracks(RESOURCE_ID)
        assertEquals(2, tracks.size)
        val newTrack = tracks.first { it.voice == Voices.CIXING }
        assertEquals(newTrack.id, h.store.getResource(RESOURCE_ID)?.activeTrackId)
        assertEquals(1, h.synth.calls.size)
        assertEquals(Voices.CIXING, h.synth.calls.single().voice)
        assertTrue(h.synth.calls.single().destination.absolutePath.contains(newTrack.id))
        assertEquals(500L, h.engine.preparedStartMs)
        assertTrue(h.service.player.value.isPlaying)
        assertEquals(ClipStatus.READY, h.store.clip(TRACK_ID, 1)?.status)
        assertTrue(store.audioFile(RESOURCE_ID, TRACK_ID, 1).isFile)

        // 切回旧音色时复用旧轨，不重新合成。
        h.service.setVoice(Voices.WENROU)
        advanceUntilIdle()
        assertEquals(TRACK_ID, h.store.getResource(RESOURCE_ID)?.activeTrackId)
        assertEquals(Voices.WENROU, h.service.player.value.activeTrack?.voice)
        assertEquals(1, h.synth.calls.size)
        assertTrue(h.service.player.value.isPlaying)
        assertEquals(ClipStatus.READY, h.service.player.value.segments[1].status)
    }

    @Test
    fun `switching back to a cached track keeps playing without extra progress writes`() = runTest {
        val store = newStore()
        val cachedFile = readyClipFile(store, RESOURCE_ID, TRACK_ID, 1)
        addSegments(
            store,
            count = 3,
            currentIndex = 1,
            positionMs = 500L,
            clips = listOf(testClip(TRACK_ID, 1, cachedFile)),
        )
        val h = harness(
            store = store,
            appSettings = AppSettings(stepFunKeyConfigured = true, prefetchCount = 0),
        )
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()
        assertTrue(h.service.player.value.isPlaying)

        // 切到新音色会合成新轨；再切回旧音色命中缓存，走 prepare + play 的复用分支。
        h.service.setVoice(Voices.CIXING)
        advanceUntilIdle()
        assertTrue(h.service.player.value.isPlaying)

        h.store.progressWrites.clear()
        h.service.setVoice(Voices.WENROU)
        advanceUntilIdle()

        assertTrue(h.service.player.value.isPlaying)
        assertTrue(
            "切轨时的内部 prepare 被误判成外部暂停：${h.store.progressWrites}",
            h.store.progressWrites.isEmpty(),
        )
    }

    @Test
    fun `switching model creates a track with the new model and normalizes voice`() = runTest {
        val store = newStore()
        addSegments(store, count = 2)
        val h = harness(
            store = store,
            appSettings = AppSettings(stepFunKeyConfigured = true, qwenKeyConfigured = true, prefetchCount = 0),
        )
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        h.service.setModel(TtsModels.QWEN_FLASH)
        advanceUntilIdle()

        assertEquals(TtsModels.QWEN_FLASH, h.settings.current.ttsModel)
        assertEquals(Voices.LONGANHUAN, h.settings.current.voice)
        assertEquals(TtsModels.QWEN_FLASH, h.service.player.value.activeTrack?.model)
        assertEquals(Voices.LONGANHUAN, h.service.player.value.activeTrack?.voice)
        assertEquals(2, h.store.getTracks(RESOURCE_ID).size)
        assertEquals(1, h.synth.calls.size)
        assertEquals(TtsModels.QWEN_FLASH, h.synth.calls.single().model)
        assertEquals(Voices.LONGANHUAN, h.synth.calls.single().voice)
        assertTrue(h.store.getTracks(RESOURCE_ID).any { it.id == TRACK_ID })
    }

    // --- 离线 ---

    @Test
    fun `cached segment plays offline without any key or synthesis request`() = runTest {
        val store = newStore()
        val file = readyClipFile(store, RESOURCE_ID, TRACK_ID, 0)
        addSegments(store, count = 2, clips = listOf(testClip(TRACK_ID, 0, file)))
        val synth = FakeSpeechSynthesizer()
        val h = harness(
            store = store,
            synth = synth,
            appSettings = AppSettings(stepFunKeyConfigured = false, prefetchCount = 2),
        )
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()

        val player = h.service.player.value
        assertNull(player.error)
        assertTrue(player.isPlaying)
        assertTrue(h.synth.calls.isEmpty())
        assertTrue(h.engine.events.contains("play"))
        assertEquals(file.absolutePath, h.engine.preparedFile?.absolutePath)
    }

    @Test
    fun `uncached segment offline asks to connect and stays retryable`() = runTest {
        val store = newStore()
        addSegments(store, count = 2)
        val synth = FakeSpeechSynthesizer(
            failure = SpeechException(ErrorKind.NETWORK, "网络连接失败", retryable = true),
        )
        val h = harness(store = store, synth = synth)
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()

        val player = h.service.player.value
        assertEquals(ErrorKind.NETWORK, player.error?.kind)
        assertTrue(player.error?.message.orEmpty().contains("需要联网合成"))
        assertFalse(player.isPlaying)
        assertEquals(ClipStatus.ERROR, player.segments[0].status)
        assertFalse(h.engine.events.contains("play"))

        h.service.retrySegment(0)
        advanceUntilIdle()
        assertTrue(h.service.player.value.error?.message.orEmpty().contains("需要联网合成"))
    }

    // --- 存储清理 ---

    @Test
    fun `clearing old tracks removes only inactive audio and updates usage`() = runTest {
        val store = newStore()
        val oldTrack = AudioTrack(OLD_TRACK_ID, RESOURCE_ID, TtsModels.MINI, Voices.WENROU, 1L)
        val activeTrack = AudioTrack(TRACK_ID, RESOURCE_ID, TtsModels.MINI, Voices.CIXING, 2L)
        addSegments(
            store,
            count = 2,
            activeTrackId = TRACK_ID,
            tracks = listOf(oldTrack, activeTrack),
        )
        addSegments(
            store,
            count = 1,
            resourceId = OTHER_RESOURCE_ID,
            activeTrackId = OTHER_TRACK_ID,
            tracks = listOf(AudioTrack(OTHER_TRACK_ID, OTHER_RESOURCE_ID, TtsModels.MINI, Voices.LINJIA, 3L)),
        )
        writeBytes(store.audioFile(RESOURCE_ID, OLD_TRACK_ID, 0), 2_048)
        writeBytes(store.audioFile(RESOURCE_ID, TRACK_ID, 0), 4_096)
        writeBytes(store.audioFile(OTHER_RESOURCE_ID, OTHER_TRACK_ID, 0), 1_024)

        val h = harness(
            store = store,
            appSettings = AppSettings(stepFunKeyConfigured = true, prefetchCount = 0),
        )
        advanceUntilIdle()
        h.service.refreshStorageUsage()
        advanceUntilIdle()
        assertEquals(5_120L, h.service.settings.value.storage.activeBytes)
        assertEquals(2_048L, h.service.settings.value.storage.oldTracksBytes)

        h.service.clearOldAudioTracks()
        advanceUntilIdle()

        val settings = h.service.settings.value
        assertFalse(settings.isClearing)
        assertEquals(5_120L, settings.storage.activeBytes)
        assertEquals(0L, settings.storage.oldTracksBytes)
        assertEquals(listOf(OLD_TRACK_ID), store.deletedTrackIds)
        assertTrue(store.audioFile(RESOURCE_ID, TRACK_ID, 0).isFile)
        assertTrue(store.audioFile(OTHER_RESOURCE_ID, OTHER_TRACK_ID, 0).isFile)
        assertFalse(store.audioFile(RESOURCE_ID, OLD_TRACK_ID, 0).isFile)
        assertTrue(settings.message.orEmpty().contains("已清理"))
    }

    // --- 试听 ---

    @Test
    fun `preview voice synthesizes once per model and voice`() = runTest {
        val store = newStore()
        addSegments(store, count = 1)
        val h = harness(
            store = store,
            appSettings = AppSettings(stepFunKeyConfigured = true, qwenKeyConfigured = true),
        )
        advanceUntilIdle()
        h.service.previewVoice(Voices.WENROU)
        advanceUntilIdle()

        assertEquals(1, h.synth.calls.size)
        val expected = File(h.previewDir, "${TtsModels.MINI}_${Voices.WENROU}.mp3")
        assertEquals(expected.absolutePath, h.synth.calls.single().destination.absolutePath)
        assertEquals(expected.absolutePath, h.audition.played.single().absolutePath)
        assertTrue(expected.isFile)
        assertEquals(Voices.WENROU, h.service.settings.value.auditionVoice)
        assertNull(h.service.settings.value.auditionError)

        h.service.previewVoice(Voices.WENROU)
        advanceUntilIdle()
        assertEquals(1, h.synth.calls.size)
        assertEquals(2, h.audition.played.size)

        h.service.setModel(TtsModels.QWEN_FLASH)
        h.service.previewVoice(Voices.LONGANHUAN)
        advanceUntilIdle()
        assertEquals(2, h.synth.calls.size)
        assertTrue(h.synth.calls.last().destination.name.startsWith(TtsModels.QWEN_FLASH))
        assertEquals(3, h.audition.played.size)
    }

    @Test
    fun `preview voice without a key explains how to configure it`() = runTest {
        val store = newStore()
        addSegments(store, count = 1)
        val h = harness(
            store = store,
            synth = FakeSpeechSynthesizer(),
            appSettings = AppSettings(stepFunKeyConfigured = false),
        )
        advanceUntilIdle()
        h.service.previewVoice(Voices.YUANQI)
        advanceUntilIdle()

        assertTrue(h.synth.calls.isEmpty())
        assertTrue(h.audition.played.isEmpty())
        assertTrue(h.service.settings.value.auditionError.orEmpty().contains("StepFun Key"))
    }

    // --- helpers ---

    private fun writeBytes(file: File, bytes: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes) { 0x49 })
    }

    private fun resource(
        resourceId: String,
        currentIndex: Int,
        positionMs: Long,
        activeTrackId: String,
    ) = Resource(
        id = resourceId,
        title = "测试标题",
        sourceKind = SourceKind.TEXT,
        sourceUrl = null,
        byline = null,
        publishedAt = null,
        extractor = "文本输入",
        snapshotPath = null,
        currentSegmentIndex = currentIndex,
        currentPositionMs = positionMs,
        activeTrackId = activeTrackId,
        createdAt = 1L,
        lastOpenedAt = 0L,
    )

    private fun track(resourceId: String, trackId: String) = AudioTrack(
        id = trackId,
        resourceId = resourceId,
        model = TtsModels.MINI,
        voice = Voices.WENROU,
        createdAt = 1L,
    )

    private fun trackId(resourceId: String) =
        if (resourceId == RESOURCE_ID) TRACK_ID else OTHER_TRACK_ID

    private fun segmentText(index: Int): String = when (index) {
        0 -> "第一句要朗读的文字。"
        1 -> "第二句要朗读的文字。"
        2 -> "第三句要朗读的文字。"
        3 -> "第四句要朗读的文字。"
        else -> "第${index + 1}句要朗读的文字。"
    }

    private companion object {
        const val RESOURCE_ID = "r1"
        const val TRACK_ID = "t1"
        const val OLD_TRACK_ID = "t-old"
        const val OTHER_RESOURCE_ID = "r2"
        const val OTHER_TRACK_ID = "t2"
    }
}
