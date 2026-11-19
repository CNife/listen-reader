package com.cnife.listenreader.service

import com.cnife.listenreader.model.AudioTrack
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
import com.cnife.listenreader.testing.testSegment
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    private class Harness(
        val service: DefaultReadingService,
        val engine: FakePlaybackEngine,
        val store: FakeLibraryStore,
    )

    private fun TestScope.harness(
        synth: FakeSpeechSynthesizer = FakeSpeechSynthesizer(configured = true),
        appSettings: AppSettings = AppSettings(stepFunKeyConfigured = true),
    ): Harness {
        val store = FakeLibraryStore()
        store.addResource(
            resource = resource(),
            segments = listOf(
                testSegment(RESOURCE_ID, 0, "第一句要朗读的文字。"),
                testSegment(RESOURCE_ID, 1, "第二句要朗读的文字。"),
                testSegment(RESOURCE_ID, 2, "第三句要朗读的文字。"),
            ),
            tracks = listOf(track()),
        )
        val engine = FakePlaybackEngine()
        val service = DefaultReadingService(
            pageSession = FakePageSession(),
            scriptCleaner = FakeScriptCleaner(),
            speechSynthesizer = synth,
            libraryStore = store,
            playbackEngine = engine,
            settingsStore = FakeSettingsStore(appSettings),
            timeProvider = FixedTimeProvider(),
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        return Harness(service, engine, store)
    }

    @Test
    fun `five minute timer pauses playback at zero and clears itself`() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()
        assertTrue(h.service.player.value.isPlaying)

        h.service.setSleepTimer(5)
        advanceTimeBy(5 * 60 * 1_000L)
        runCurrent()

        val player = h.service.player.value
        assertFalse(player.isPlaying)
        assertNull(player.sleepTimerMinutes)
        assertNull(player.sleepTimerRemainingMs)
        assertTrue(h.engine.events.contains("pause"))
    }

    @Test
    fun `remaining time counts down every second`() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        h.service.setSleepTimer(5)
        runCurrent()
        assertEquals(300_000L, h.service.player.value.sleepTimerRemainingMs)

        advanceTimeBy(61_000L)
        runCurrent()
        assertEquals(239_000L, h.service.player.value.sleepTimerRemainingMs)
    }

    @Test
    fun `all supported durations are accepted`() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        listOf(5, 15, 30, 60).forEach { minutes ->
            h.service.setSleepTimer(minutes)
            runCurrent()
            val player = h.service.player.value
            assertEquals(minutes, player.sleepTimerMinutes)
            assertEquals(minutes * 60_000L, player.sleepTimerRemainingMs)
        }
    }

    @Test
    fun `cancelling the timer keeps playback running`() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()

        h.service.setSleepTimer(5)
        advanceTimeBy(60_000L)
        runCurrent()
        h.service.setSleepTimer(null)
        advanceTimeBy(10 * 60 * 1_000L)
        runCurrent()

        val player = h.service.player.value
        assertTrue(player.isPlaying)
        assertNull(player.sleepTimerMinutes)
        assertNull(player.sleepTimerRemainingMs)
        assertFalse(h.engine.events.contains("pause"))
    }

    @Test
    fun `switching segments does not clear or retrigger the timer`() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()

        h.service.setSleepTimer(15)
        h.service.nextSegment()
        runCurrent()
        assertEquals(15, h.service.player.value.sleepTimerMinutes)
        assertEquals(900_000L, h.service.player.value.sleepTimerRemainingMs)

        advanceTimeBy(15 * 60 * 1_000L)
        runCurrent()
        assertFalse(h.service.player.value.isPlaying)
        assertNull(h.service.player.value.sleepTimerMinutes)
    }

    @Test
    fun `closing the player cancels a pending timer`() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.setSleepTimer(5)

        h.service.closePlayer()
        runCurrent()
        assertNull(h.service.player.value.sleepTimerMinutes)

        advanceTimeBy(10 * 60 * 1_000L)
        runCurrent()
        assertEquals(1, h.engine.events.count { it == "pause" })
    }

    @Test
    fun `timer firing while a segment is still synthesizing does not start playback`() = runTest {
        val synth = FakeSpeechSynthesizer(configured = true).apply { synthDelayMs = 120_000L }
        val h = harness(
            synth = synth,
            appSettings = AppSettings(stepFunKeyConfigured = true, prefetchCount = 0),
        )
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        h.service.setSleepTimer(1)
        h.service.play()
        runCurrent()
        assertTrue(h.service.player.value.isPreparing)

        advanceTimeBy(60_000L)
        runCurrent()
        assertFalse(h.service.player.value.isPlaying)

        // 合成在定时器到点后才完成：不能再把引擎拉起来。
        advanceTimeBy(120_000L)
        runCurrent()
        assertFalse(h.service.player.value.isPlaying)
        assertTrue(h.service.player.value.sleepTimerMinutes == null)
        assertEquals(0, h.engine.events.count { it == "play" })
    }

    @Test
    fun `pause from the media notification keeps the player state in sync`() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()
        h.service.play()
        advanceUntilIdle()
        assertTrue(h.service.player.value.isPlaying)

        h.engine.emit(PlaybackEngineState.Ready(1_200L, false, 700L))
        advanceUntilIdle()

        val player = h.service.player.value
        assertFalse(player.isPlaying)
        assertEquals(700L, player.positionInSegmentMs)
        assertTrue(h.store.progressWrites.contains(0 to 700L))
    }

    private fun resource() = Resource(
        id = RESOURCE_ID,
        title = "测试标题",
        sourceKind = SourceKind.TEXT,
        sourceUrl = null,
        byline = null,
        publishedAt = null,
        extractor = "文本输入",
        snapshotPath = null,
        currentSegmentIndex = 0,
        currentPositionMs = 0,
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
