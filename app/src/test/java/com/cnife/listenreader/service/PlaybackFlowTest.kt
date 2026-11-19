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
import com.cnife.listenreader.testing.FakeLibraryStore
import com.cnife.listenreader.testing.FakePageSession
import com.cnife.listenreader.testing.FakePlaybackEngine
import com.cnife.listenreader.testing.FakeScriptCleaner
import com.cnife.listenreader.testing.FakeSettingsStore
import com.cnife.listenreader.testing.FakeSpeechSynthesizer
import com.cnife.listenreader.testing.FixedTimeProvider
import com.cnife.listenreader.testing.testClip
import com.cnife.listenreader.testing.testSegment
import com.cnife.listenreader.tts.SpeechException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFlowTest {

    private class Harness(
        val service: DefaultReadingService,
        val scope: CoroutineScope,
        val store: FakeLibraryStore,
        val synth: FakeSpeechSynthesizer,
        val engine: FakePlaybackEngine,
        val settings: FakeSettingsStore,
    )

    private fun TestScope.harness(
        store: FakeLibraryStore = FakeLibraryStore(),
        synth: FakeSpeechSynthesizer = FakeSpeechSynthesizer(),
        engine: FakePlaybackEngine = FakePlaybackEngine(),
        settings: FakeSettingsStore = FakeSettingsStore(AppSettings(stepFunKeyConfigured = true)),
    ): Harness {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = DefaultReadingService(
            pageSession = FakePageSession(),
            scriptCleaner = FakeScriptCleaner(),
            speechSynthesizer = synth,
            libraryStore = store,
            playbackEngine = engine,
            settingsStore = settings,
            timeProvider = FixedTimeProvider(),
            scope = scope,
        )
        return Harness(service, scope, store, synth, engine, settings)
    }

    private fun addThreeSegmentResource(
        store: FakeLibraryStore,
        currentIndex: Int = 0,
        positionMs: Long = 0,
        clips: List<com.cnife.listenreader.model.AudioClip> = emptyList(),
    ) {
        store.addResource(
            resource = resource(currentIndex, positionMs),
            segments = listOf(
                testSegment(RESOURCE_ID, 0, "第一句要朗读的文字。"),
                testSegment(RESOURCE_ID, 1, "第二句要朗读的文字。"),
                testSegment(RESOURCE_ID, 2, "第三句要朗读的文字。"),
            ),
            tracks = listOf(track()),
            clips = clips,
        )
    }

    @Test
    fun `opening a resource restores segment position and speed`() = runTest {
        val store = FakeLibraryStore()
        addThreeSegmentResource(store, currentIndex = 1, positionMs = 500)
        val h = harness(store = store)
        advanceUntilIdle()

        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        val player = h.service.player.value
        assertEquals(RESOURCE_ID, player.resourceId)
        assertEquals("测试标题", player.title)
        assertEquals(1, player.currentIndex)
        assertEquals(500L, player.positionInSegmentMs)
        assertEquals(3, player.segments.size)
        assertEquals("温柔女声", Voices.DISPLAY[player.activeTrack?.voice])
        assertTrue(player.totalDurationMs > 0)
        assertFalse(player.isPlaying)
        assertEquals(listOf(RESOURCE_ID), h.store.openedCalls)
    }

    @Test
    fun `play synthesizes the current segment caches the clip and starts the engine`() = runTest {
        val store = FakeLibraryStore()
        addThreeSegmentResource(store, currentIndex = 1, positionMs = 500)
        val h = harness(store = store)
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        h.service.play()
        advanceUntilIdle()

        // 当前句（index 1）先合成，随后预取 index 2。
        assertEquals(2, h.synth.calls.size)
        val call = h.synth.calls.first()
        assertEquals("第二句要朗读的文字。", call.text)
        assertEquals(TtsModels.AUDIO3, call.model)
        assertEquals(Voices.WENROU, call.voice)
        assertEquals(store.audioFile(RESOURCE_ID, TRACK_ID, 1), call.destination)
        assertTrue(call.destination.isFile)

        val clip = h.store.clip(TRACK_ID, 1)
        assertNotNull(clip)
        assertEquals(ClipStatus.READY, clip?.status)
        assertEquals(1_200L, clip?.durationMs)
        assertEquals(call.destination.absolutePath, clip?.filePath)

        assertEquals(call.destination.absolutePath, h.engine.preparedFile?.absolutePath)
        assertEquals(500L, h.engine.preparedStartMs)
        assertTrue(h.engine.events.contains("play"))

        val player = h.service.player.value
        assertTrue(player.isPlaying)
        assertFalse(player.isPreparing)
        assertEquals(ClipStatus.READY, player.segments[1].status)
        assertEquals(1_200L, player.segments[1].durationMs)
        assertNull(player.error)
    }

    @Test
    fun `synthesis failure marks only the current segment and keeps cached audio`() = runTest {
        val store = FakeLibraryStore()
        val firstFile = store.audioFile(RESOURCE_ID, TRACK_ID, 0).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x49, 0x44, 0x33))
        }
        addThreeSegmentResource(
            store,
            currentIndex = 1,
            clips = listOf(testClip(TRACK_ID, 0, firstFile)),
        )
        val synth = FakeSpeechSynthesizer(
            failure = SpeechException(ErrorKind.AUTH, "StepFun Key 无效或已过期", retryable = false),
        )
        val h = harness(store = store, synth = synth)
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        h.service.play()
        advanceUntilIdle()

        val player = h.service.player.value
        assertFalse(player.isPlaying)
        assertEquals(ErrorKind.AUTH, player.error?.kind)
        assertEquals(ClipStatus.READY, player.segments[0].status)
        assertEquals(ClipStatus.ERROR, player.segments[1].status)
        assertEquals(ClipStatus.ERROR, h.store.clip(TRACK_ID, 1)?.status)
        assertFalse(h.engine.events.contains("play"))
        assertTrue(store.audioFile(RESOURCE_ID, TRACK_ID, 0).isFile)
    }

    @Test
    fun `play without a StepFun key shows NO_KEY guidance and does not synthesize`() = runTest {
        val store = FakeLibraryStore()
        addThreeSegmentResource(store)
        val synth = FakeSpeechSynthesizer()
        val h = harness(
            store = store,
            synth = synth,
            settings = FakeSettingsStore(AppSettings(stepFunKeyConfigured = false)),
        )
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        h.service.play()
        advanceUntilIdle()

        val player = h.service.player.value
        assertEquals(ErrorKind.NO_KEY, player.error?.kind)
        assertTrue(player.error?.message.orEmpty().contains("设置"))
        assertFalse(player.isPlaying)
        assertTrue(synth.calls.isEmpty())
        assertTrue(h.engine.events.isEmpty())
    }

    @Test
    fun `pause saves progress at the current segment position`() = runTest {
        val store = FakeLibraryStore()
        addThreeSegmentResource(store)
        val h = harness(store = store)
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()

        h.engine.emit(PlaybackEngineState.Ready(1_200L, true, 700L))
        advanceUntilIdle()
        h.service.pause()
        advanceUntilIdle()

        assertTrue(h.store.progressWrites.contains(0 to 700L))
        assertFalse(h.service.player.value.isPlaying)
        assertTrue(h.engine.events.contains("pause"))
    }

    @Test
    fun `ready-not-playing during a service initiated start is not treated as a user pause`() = runTest {
        val store = FakeLibraryStore()
        addThreeSegmentResource(store)
        val synth = FakeSpeechSynthesizer().apply { synthDelayMs = 5_000L }
        val h = harness(store = store, synth = synth)
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        h.service.play()
        runCurrent()
        assertTrue(h.service.player.value.isPreparing)
        h.store.progressWrites.clear()

        // Media3 在 prepare 完成、play 生效前会先发一次 Ready(isPlaying=false)。
        h.engine.emit(PlaybackEngineState.Ready(1_200L, false, 0))
        runCurrent()
        assertTrue(h.service.player.value.isPlaying)
        assertTrue(h.store.progressWrites.isEmpty())

        advanceUntilIdle()
        assertTrue(h.service.player.value.isPlaying)
    }

    @Test
    fun `ended segment advances and the last segment stops playback`() = runTest {
        val store = FakeLibraryStore()
        addThreeSegmentResource(store)
        val h = harness(store = store)
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()
        // 预取已经缓存了后两句，播放推进时复用缓存。
        assertEquals(3, h.synth.calls.size)

        h.engine.emit(PlaybackEngineState.Ended(1_200L))
        advanceUntilIdle()
        assertEquals(1, h.service.player.value.currentIndex)
        assertEquals(3, h.synth.calls.size)
        assertTrue(h.service.player.value.isPlaying)

        h.engine.emit(PlaybackEngineState.Ended(1_200L))
        advanceUntilIdle()
        assertEquals(2, h.service.player.value.currentIndex)
        assertEquals(3, h.synth.calls.size)

        h.engine.emit(PlaybackEngineState.Ended(1_200L))
        advanceUntilIdle()
        val player = h.service.player.value
        assertEquals(2, player.currentIndex)
        assertFalse(player.isPlaying)
        assertTrue(h.store.progressWrites.contains(2 to 1_200L))
    }

    @Test
    fun `speed change persists and does not synthesize`() = runTest {
        val store = FakeLibraryStore()
        addThreeSegmentResource(store)
        val h = harness(store = store)
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        h.service.setSpeed(1.75f)
        advanceUntilIdle()

        assertEquals(1.75f, h.service.player.value.speed)
        assertEquals(1.75f, h.engine.playbackSpeed)
        assertEquals(1.75f, h.settings.current.playbackSpeed)
        assertTrue(h.synth.calls.isEmpty())
    }

    @Test
    fun `retry after failure re-synthesizes the failed segment`() = runTest {
        val store = FakeLibraryStore()
        addThreeSegmentResource(store)
        val synth = FakeSpeechSynthesizer(
            failure = SpeechException(ErrorKind.SERVER, "StepFun 服务暂时不可用", retryable = true),
        )
        val h = harness(store = store, synth = synth)
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()
        assertEquals(ClipStatus.ERROR, h.service.player.value.segments[0].status)

        synth.failure = null
        h.service.retrySegment(0)
        advanceUntilIdle()

        assertEquals(ClipStatus.READY, h.service.player.value.segments[0].status)
        assertEquals(ClipStatus.READY, h.store.clip(TRACK_ID, 0)?.status)
    }

    @Test
    fun `startup restores the last opened resource without marking it opened or playing`() = runTest {
        val store = FakeLibraryStore()
        store.lastOpened = RESOURCE_ID
        addThreeSegmentResource(store, currentIndex = 2, positionMs = 100)
        val h = harness(store = store)
        advanceUntilIdle()

        val player = h.service.player.value
        assertEquals(RESOURCE_ID, player.resourceId)
        assertEquals(2, player.currentIndex)
        assertEquals(100L, player.positionInSegmentMs)
        assertFalse(player.isPlaying)
        assertTrue(store.openedCalls.isEmpty())
        assertFalse(h.engine.events.contains("play"))
    }

    @Test
    fun `seeking to a cached segment saves the old position and prepares the target`() = runTest {
        val store = FakeLibraryStore()
        val firstFile = store.audioFile(RESOURCE_ID, TRACK_ID, 0).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x49, 0x44, 0x33))
        }
        val secondFile = store.audioFile(RESOURCE_ID, TRACK_ID, 1).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x49, 0x44, 0x33))
        }
        addThreeSegmentResource(
            store,
            clips = listOf(
                testClip(TRACK_ID, 0, firstFile),
                testClip(TRACK_ID, 1, secondFile),
            ),
        )
        val h = harness(store = store)
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        h.engine.emit(PlaybackEngineState.Ready(1_000L, false, 300L))
        advanceUntilIdle()
        h.service.seekToSegment(1)
        advanceUntilIdle()

        assertEquals(1, h.service.player.value.currentIndex)
        assertTrue(h.store.progressWrites.contains(0 to 300L))
        assertTrue(h.store.progressWrites.contains(1 to 0L))
        assertEquals(secondFile.absolutePath, h.engine.preparedFile?.absolutePath)
        assertEquals(0L, h.engine.preparedStartMs)
    }

    private fun resource(currentIndex: Int, positionMs: Long) = Resource(
        id = RESOURCE_ID,
        title = "测试标题",
        sourceKind = SourceKind.TEXT,
        sourceUrl = null,
        byline = null,
        publishedAt = null,
        extractor = "文本输入",
        snapshotPath = null,
        currentSegmentIndex = currentIndex,
        currentPositionMs = positionMs,
        activeTrackId = TRACK_ID,
        createdAt = 1L,
        lastOpenedAt = 0L,
    )

    private fun track() = AudioTrack(
        id = TRACK_ID,
        resourceId = RESOURCE_ID,
        model = TtsModels.AUDIO3,
        voice = Voices.WENROU,
        createdAt = 1L,
    )

    private companion object {
        const val RESOURCE_ID = "r1"
        const val TRACK_ID = "t1"
    }
}
