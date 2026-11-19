package com.cnife.listenreader.service

import com.cnife.listenreader.model.AudioClip
import com.cnife.listenreader.model.AudioTrack
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.LibraryFilter
import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.testing.FakeLibraryStore
import com.cnife.listenreader.testing.FakePageSession
import com.cnife.listenreader.testing.FakePlaybackEngine
import com.cnife.listenreader.testing.FakeScriptCleaner
import com.cnife.listenreader.testing.FakeSettingsStore
import com.cnife.listenreader.testing.FakeSpeechSynthesizer
import com.cnife.listenreader.testing.FixedTimeProvider
import com.cnife.listenreader.testing.testClip
import com.cnife.listenreader.testing.testSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryFlowTest {

    private class Harness(
        val service: DefaultReadingService,
        val scope: CoroutineScope,
        val store: FakeLibraryStore,
    )

    private fun TestScope.harness(store: FakeLibraryStore): Harness {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = DefaultReadingService(
            pageSession = FakePageSession(),
            scriptCleaner = FakeScriptCleaner(),
            speechSynthesizer = FakeSpeechSynthesizer(),
            libraryStore = store,
            playbackEngine = FakePlaybackEngine(),
            settingsStore = FakeSettingsStore(),
            timeProvider = FixedTimeProvider(),
            scope = scope,
        )
        return Harness(service, scope, store)
    }

    private fun addResource(
        store: FakeLibraryStore,
        id: String,
        title: String = "文章",
        kind: SourceKind = SourceKind.TEXT,
        url: String? = null,
        currentIndex: Int = 0,
        positionMs: Long = 0,
        lastOpenedAt: Long = 0,
        segmentCount: Int = 3,
        clips: List<AudioClip> = emptyList(),
        cachedAll: Boolean = false,
    ) {
        val trackId = "$id-track"
        val segments = (0 until segmentCount).map { index ->
            testSegment(id, index, "第 ${index + 1} 句要朗读的文字。")
        }
        val readyClips = if (cachedAll) {
            segments.mapIndexed { index, _ ->
                testClip(trackId, index, java.io.File("build/tmp/audio/$id/$trackId/$index.mp3"))
            }
        } else {
            clips
        }
        store.addResource(
            resource = Resource(
                id = id,
                title = title,
                sourceKind = kind,
                sourceUrl = url,
                byline = null,
                publishedAt = null,
                extractor = null,
                snapshotPath = null,
                currentSegmentIndex = currentIndex,
                currentPositionMs = positionMs,
                activeTrackId = trackId,
                createdAt = 1L,
                lastOpenedAt = lastOpenedAt,
            ),
            segments = segments,
            tracks = listOf(AudioTrack(trackId, id, TtsModels.AUDIO3, Voices.WENROU, 1L)),
            clips = readyClips,
        )
    }

    @Test
    fun `continue card shows the last opened resource even before any progress`() = runTest {
        val store = FakeLibraryStore().apply { lastOpened = "a" }
        addResource(store, "a", title = "上次读的文章", segmentCount = 4)
        addResource(store, "b", title = "另一个")
        val h = harness(store)
        advanceUntilIdle()

        val card = h.service.library.value.continueListening
        assertNotNull(card)
        assertEquals("a", card?.id)
        assertEquals("上次读的文章", card?.title)
        assertEquals(0, card?.currentSegmentIndex)
        assertEquals(4, card?.segmentCount)
        h.scope.cancel()
    }

    @Test
    fun `continue card is hidden when there is no last opened record`() = runTest {
        val store = FakeLibraryStore()
        addResource(store, "a")
        val h = harness(store)
        advanceUntilIdle()

        assertNull(h.service.library.value.continueListening)
        h.scope.cancel()
    }

    @Test
    fun `search matches title and source label case insensitively`() = runTest {
        val store = FakeLibraryStore()
        addResource(store, "web", title = "Kotlin 入门", kind = SourceKind.URL, url = "https://www.example.com/post/1")
        addResource(store, "text", title = "午饭吃什么")
        val h = harness(store)
        advanceUntilIdle()

        h.service.setLibraryQuery("KOTLIN")
        advanceUntilIdle()
        assertEquals(listOf("web"), h.service.library.value.items.map { it.id })

        h.service.setLibraryQuery("EXAMPLE")
        advanceUntilIdle()
        assertEquals(listOf("web"), h.service.library.value.items.map { it.id })

        h.service.setLibraryQuery("不存在")
        advanceUntilIdle()
        assertTrue(h.service.library.value.items.isEmpty())
        assertEquals("不存在", h.service.library.value.query)
        h.scope.cancel()
    }

    @Test
    fun `filter narrows to web text and cached resources`() = runTest {
        val store = FakeLibraryStore()
        addResource(store, "web", kind = SourceKind.URL, url = "https://example.com/a")
        addResource(store, "text")
        addResource(store, "cached", cachedAll = true)
        val h = harness(store)
        advanceUntilIdle()

        h.service.setLibraryFilter(LibraryFilter.WEB)
        advanceUntilIdle()
        assertEquals(listOf("web"), h.service.library.value.items.map { it.id })

        h.service.setLibraryFilter(LibraryFilter.TEXT)
        advanceUntilIdle()
        assertEquals(listOf("text", "cached"), h.service.library.value.items.map { it.id })

        h.service.setLibraryFilter(LibraryFilter.CACHED)
        advanceUntilIdle()
        assertEquals(listOf("cached"), h.service.library.value.items.map { it.id })

        h.service.setLibraryFilter(LibraryFilter.ALL)
        advanceUntilIdle()
        assertEquals(listOf("web", "text", "cached"), h.service.library.value.items.map { it.id })
        h.scope.cancel()
    }

    @Test
    fun `opening a resource moves the continue card to it`() = runTest {
        val store = FakeLibraryStore().apply { lastOpened = "a" }
        addResource(store, "a", title = "旧文章")
        addResource(store, "b", title = "新文章")
        val h = harness(store)
        advanceUntilIdle()
        assertEquals("a", h.service.library.value.continueListening?.id)

        h.service.openResource("b")
        advanceUntilIdle()

        val card = h.service.library.value.continueListening
        assertEquals("b", card?.id)
        assertEquals("新文章", card?.title)
        assertEquals("b", h.store.lastOpened)
        h.scope.cancel()
    }

    @Test
    fun `deleting the current resource closes the player and clears the card`() = runTest {
        val store = FakeLibraryStore().apply { lastOpened = "a" }
        addResource(store, "a", title = "要删除的文章")
        val h = harness(store)
        advanceUntilIdle()
        h.service.openResource("a")
        advanceUntilIdle()
        assertEquals("a", h.service.player.value.resourceId)

        h.service.deleteResource("a")
        advanceUntilIdle()

        assertNull(h.service.player.value.resourceId)
        assertEquals("", h.service.player.value.title)
        assertTrue(h.service.library.value.items.isEmpty())
        assertNull(h.service.library.value.continueListening)
        assertNull(store.getResource("a"))
        assertTrue(store.observeItems().first().isEmpty())
        assertNull(h.service.library.value.error)
        h.scope.cancel()
    }

    @Test
    fun `deleting another resource keeps the current player and card`() = runTest {
        val store = FakeLibraryStore().apply { lastOpened = "a" }
        addResource(store, "a", title = "正在读")
        addResource(store, "b", title = "要删除的")
        val h = harness(store)
        advanceUntilIdle()
        h.service.openResource("a")
        advanceUntilIdle()

        h.service.deleteResource("b")
        advanceUntilIdle()

        assertEquals("a", h.service.player.value.resourceId)
        assertEquals("a", h.service.library.value.continueListening?.id)
        assertEquals(listOf("a"), h.service.library.value.items.map { it.id })
        h.scope.cancel()
    }

    @Test
    fun `delete failure keeps the resource and reports a readable error`() = runTest {
        val store = FakeLibraryStore().apply { failDelete = true }
        addResource(store, "a", title = "保留的文章")
        val h = harness(store)
        advanceUntilIdle()

        h.service.deleteResource("a")
        advanceUntilIdle()

        val state = h.service.library.value
        assertEquals("删除失败，请稍后重试", state.error?.message)
        assertTrue(state.error?.retryable == true)
        assertEquals(listOf("a"), state.items.map { it.id })
        assertNotNull(store.getResource("a"))
        h.scope.cancel()
    }

    @Test
    fun `deleting an unknown resource reports an error without touching the list`() = runTest {
        val store = FakeLibraryStore()
        addResource(store, "a")
        val h = harness(store)
        advanceUntilIdle()

        h.service.deleteResource("missing")
        advanceUntilIdle()

        assertEquals("该内容已不存在，可能已被删除", h.service.library.value.error?.message)
        assertEquals(listOf("a"), h.service.library.value.items.map { it.id })
        h.scope.cancel()
    }

    @Test
    fun `library loading state settles and errors are cleared on refresh`() = runTest {
        val store = FakeLibraryStore()
        addResource(store, "a")
        val h = harness(store)

        h.service.refreshLibrary()
        advanceUntilIdle()

        assertFalse(h.service.library.value.isLoading)
        assertEquals(listOf("a"), h.service.library.value.items.map { it.id })
        assertNull(h.service.library.value.error)
        h.scope.cancel()
    }

    @Test
    fun `restored player matches the continue card after a rebuild`() = runTest {
        val store = FakeLibraryStore().apply { lastOpened = "a" }
        addResource(store, "a", title = "重开的文章", currentIndex = 2, positionMs = 120)
        val h = harness(store)
        advanceUntilIdle()

        val player = h.service.player.value
        assertEquals("a", player.resourceId)
        assertEquals(2, player.currentIndex)
        assertEquals(120L, player.positionInSegmentMs)
        assertFalse(player.isPlaying)

        val card = h.service.library.value.continueListening
        assertEquals("a", card?.id)
        assertEquals(2, card?.currentSegmentIndex)
        h.scope.cancel()
    }
}
