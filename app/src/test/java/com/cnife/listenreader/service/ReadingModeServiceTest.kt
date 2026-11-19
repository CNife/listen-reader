package com.cnife.listenreader.service

import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.service.ports.AppSettings
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingModeServiceTest {

    private class Harness(val service: DefaultReadingService, val store: FakeLibraryStore)

    private fun TestScope.harness(store: FakeLibraryStore): Harness {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = DefaultReadingService(
            pageSession = FakePageSession(),
            scriptCleaner = FakeScriptCleaner(),
            speechSynthesizer = FakeSpeechSynthesizer(configured = true),
            libraryStore = store,
            playbackEngine = FakePlaybackEngine(),
            settingsStore = FakeSettingsStore(AppSettings(stepFunKeyConfigured = true)),
            timeProvider = FixedTimeProvider(),
            scope = scope,
        )
        return Harness(service, store)
    }

    private fun addResource(store: FakeLibraryStore, kind: SourceKind) {
        store.addResource(
            resource = Resource(
                id = RESOURCE_ID,
                title = "测试标题",
                sourceKind = kind,
                sourceUrl = if (kind == SourceKind.URL) "https://example.com/a" else null,
                byline = null,
                publishedAt = null,
                extractor = if (kind == SourceKind.URL) "readability" else "文本输入",
                snapshotPath = null,
                currentSegmentIndex = 0,
                currentPositionMs = 0,
                activeTrackId = null,
                createdAt = 1L,
                lastOpenedAt = 0L,
            ),
            segments = listOf(
                testSegment(RESOURCE_ID, 0, "第一句要朗读的文字。"),
                testSegment(RESOURCE_ID, 1, "第二句要朗读的文字。"),
            ),
        )
    }

    @Test
    fun `opening a text resource exposes its markdown snapshot`() = runTest {
        val store = FakeLibraryStore()
        addResource(store, SourceKind.TEXT)
        val snapshot = "# 测试标题\n\n第一段原文。"
        store.setSnapshot(RESOURCE_ID, snapshot)
        val h = harness(store)
        advanceUntilIdle()

        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        val player = h.service.player.value
        assertEquals(snapshot, player.snapshotText)
        assertNull(player.snapshotError)
    }

    @Test
    fun `opening a url resource exposes its markdown snapshot`() = runTest {
        val store = FakeLibraryStore()
        addResource(store, SourceKind.URL)
        val snapshot = "> 引用原文\n\n第二段原文。"
        store.setSnapshot(RESOURCE_ID, snapshot)
        val h = harness(store)
        advanceUntilIdle()

        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        assertEquals(snapshot, h.service.player.value.snapshotText)
    }

    @Test
    fun `missing snapshot degrades to a readable error`() = runTest {
        val store = FakeLibraryStore()
        addResource(store, SourceKind.TEXT)
        val h = harness(store)
        advanceUntilIdle()

        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        val player = h.service.player.value
        assertEquals(RESOURCE_ID, player.resourceId)
        assertNull(player.snapshotText)
        assertEquals("原文快照不可用", player.snapshotError)
    }

    @Test
    fun `snapshot read failure does not break opening the resource`() = runTest {
        val store = FakeLibraryStore()
        addResource(store, SourceKind.URL)
        store.failSnapshot = true
        val h = harness(store)
        advanceUntilIdle()

        h.service.openResource(RESOURCE_ID)
        advanceUntilIdle()

        val player = h.service.player.value
        assertEquals(RESOURCE_ID, player.resourceId)
        assertEquals(2, player.segments.size)
        assertNull(player.snapshotText)
        assertEquals("原文快照读取失败", player.snapshotError)
        assertTrue(player.snapshotError!!.isNotBlank())
    }

    private companion object {
        const val RESOURCE_ID = "r1"
    }
}
