package com.cnife.listenreader.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.data.db.ListenReaderDatabase
import com.cnife.listenreader.data.library.RoomLibraryStore
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.model.SegmentationSource
import com.cnife.listenreader.testing.FakeSettingsStore
import com.cnife.listenreader.testing.FixedTimeProvider
import com.cnife.listenreader.testing.testReadingService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextCaptureFlowTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: ListenReaderDatabase
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ListenReaderDatabase::class.java,
        ).allowMainThreadQueries().build()
        filesDir = tempFolder.newFolder("files")
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `text capture preview edit save and reload after service rebuild`() = runBlocking {
        val settings = FakeSettingsStore()
        val store = newStore(settings)
        val scope = newScope()
        val service = newService(store, settings, scope)

        awaitState { !service.library.value.isLoading }

        service.beginTextCapture("我的标题\n\n第一段足够长的句子。\n\n第二段足够长的句子。")

        val preview = service.capture.value.preview!!
        assertEquals(CaptureMode.TEXT, service.capture.value.mode)
        assertEquals("我的标题", preview.title)
        assertEquals(SegmentationSource.RULE, preview.segmentation)
        assertEquals(L2Status.NOT_CONFIGURED, preview.l2Status)
        assertEquals(3, preview.segmentDrafts.size)

        service.updatePreviewBody("改过的正文足够长的句子。")
        val edited = service.capture.value.preview!!
        assertEquals(12, edited.charCount)
        assertEquals(2, edited.segmentDrafts.size)
        assertEquals(SegmentationSource.RULE, edited.segmentation)
        assertEquals(L2Status.NOT_CONFIGURED, edited.l2Status)

        val resourceId = service.saveCapture()!!
        awaitState { service.capture.value.savedResourceId == resourceId }
        assertFalse(service.capture.value.isSaving)
        assertNull(service.capture.value.error)

        service.refreshLibrary()
        awaitState { !service.library.value.isLoading }
        val library = service.library.value
        assertEquals(listOf(resourceId), library.items.map { it.id })
        assertEquals("我的标题", library.items.single().title)
        assertEquals(2, library.items.single().segmentCount)

        // 重建 service（同一数据库/文件库）后资源仍可见。
        val rebuiltScope = newScope()
        val rebuilt = newService(store, settings, rebuiltScope)
        awaitState { !rebuilt.library.value.isLoading }
        rebuilt.refreshLibrary()
        awaitState { !rebuilt.library.value.isLoading }
        assertEquals(listOf(resourceId), rebuilt.library.value.items.map { it.id })

        scope.cancel()
        rebuiltScope.cancel()
    }

    @Test
    fun `open resource marks it opened and exposes title to player`() = runBlocking {
        val settings = FakeSettingsStore()
        val store = newStore(settings)
        val scope = newScope()
        val service = newService(store, settings, scope)
        awaitState { !service.library.value.isLoading }

        service.beginTextCapture("标题\n\n正文足够长的句子内容。")
        val id = service.saveCapture()!!
        awaitState { service.capture.value.savedResourceId == id }

        service.openResource(id)
        awaitState { service.player.value.resourceId == id && service.player.value.title == "标题" }

        val saved = store.getResource(id)!!
        assertTrue(saved.lastOpenedAt > 0)
        assertEquals(id, store.observeItems().first().single { it.id == id }.id)

        scope.cancel()
    }

    @Test
    fun `title-less pasted text is spoken without the paste placeholder`() = runBlocking {
        val settings = FakeSettingsStore()
        val store = newStore(settings)
        val scope = newScope()
        val service = newService(store, settings, scope)
        awaitState { !service.library.value.isLoading }

        service.beginTextCapture("第一句是足够长的句子内容。\n\n第二句也是足够长的句子内容。")

        val preview = service.capture.value.preview!!
        // 展示用的标题仍回退到占位文案。
        assertEquals("粘贴文本", preview.title)
        assertTrue(preview.segmentDrafts.none { it.text == "粘贴文本" })
        assertEquals("第一句是足够长的句子内容。", preview.segmentDrafts.first().text)

        val id = service.saveCapture()!!
        awaitState { service.capture.value.savedResourceId == id }

        assertEquals("粘贴文本", store.getResource(id)!!.title)
        val saved = store.getSegments(id)
        assertTrue(saved.none { it.text == "粘贴文本" })
        assertEquals("第一句是足够长的句子内容。", saved.first().text)

        scope.cancel()
    }

    @Test
    fun `title-like first line still yields a heading segment`() = runBlocking {
        val settings = FakeSettingsStore()
        val store = newStore(settings)
        val scope = newScope()
        val service = newService(store, settings, scope)
        awaitState { !service.library.value.isLoading }

        service.beginTextCapture("我的标题\n\n第一段足够长的句子内容。")

        val preview = service.capture.value.preview!!
        assertEquals("我的标题", preview.title)
        assertEquals("我的标题", preview.segmentDrafts.first().text)
        assertEquals(SegmentKind.HEADING, preview.segmentDrafts.first().kind)

        scope.cancel()
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun newService(
        store: RoomLibraryStore,
        settings: FakeSettingsStore,
        scope: CoroutineScope,
    ): DefaultReadingService = testReadingService(
        scope = scope,
        libraryStore = store,
        settingsStore = settings,
        timeProvider = FixedTimeProvider(1_000L),
    )

    private fun newStore(settings: FakeSettingsStore): RoomLibraryStore = RoomLibraryStore(
        db = db,
        filesDir = filesDir,
        settingsStore = settings,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private suspend fun awaitState(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("state timeout")
            }
            delay(10)
        }
    }
}
