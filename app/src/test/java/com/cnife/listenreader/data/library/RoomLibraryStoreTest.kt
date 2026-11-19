package com.cnife.listenreader.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.data.db.ListenReaderDatabase
import com.cnife.listenreader.model.AudioClip
import com.cnife.listenreader.model.AudioTrack
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.SegmentDraft
import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.model.TtsModels
import com.cnife.listenreader.model.Voices
import com.cnife.listenreader.service.ports.AppSettings
import com.cnife.listenreader.testing.FakeSettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
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
class RoomLibraryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: ListenReaderDatabase
    private lateinit var store: RoomLibraryStore
    private lateinit var filesDir: File
    private val settingsStore = FakeSettingsStore(
        AppSettings(
            ttsModel = TtsModels.MINI,
            voice = Voices.LINJIA,
        ),
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ListenReaderDatabase::class.java,
        ).allowMainThreadQueries().build()
        filesDir = tempFolder.newFolder("files")
        store = RoomLibraryStore(
            db = db,
            filesDir = filesDir,
            settingsStore = settingsStore,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `createResource stores snapshot ordered segments and initial track`() = runBlocking {
        val id = "r1"
        store.createResource(resource(id, title = "文章标题"), TEXT_BODY, null)

        val saved = store.getResource(id)
        assertEquals("文章标题", saved?.title)
        assertEquals(SourceKind.TEXT, saved?.sourceKind)
        assertEquals(store.snapshotFile(id).absolutePath, saved?.snapshotPath)

        val segments = store.getSegments(id)
        assertEquals(3, segments.size)
        assertEquals(listOf(0, 1, 2), segments.map { it.index })
        assertEquals("文章标题", segments[0].text)
        assertEquals("第一段足够长的句子。", segments[1].text)
        assertEquals("第二段足够长的句子。", segments[2].text)

        val tracks = store.getTracks(id)
        assertEquals(1, tracks.size)
        assertEquals(TtsModels.MINI, tracks[0].model)
        assertEquals(Voices.LINJIA, tracks[0].voice)
        assertEquals(tracks[0].id, saved?.activeTrackId)

        assertEquals(TEXT_BODY, store.snapshotText(id))
        assertEquals(TEXT_BODY, store.readSnapshot("sources/$id.md"))
        assertTrue(store.snapshotFile(id).isFile)
    }

    @Test
    fun `library sorts opened resources first then newest created`() = runBlocking {
        store.createResource(resource("a", createdAt = 1_000), TEXT_BODY, null)
        store.createResource(resource("b", createdAt = 2_000), TEXT_BODY, null)

        assertEquals(listOf("b", "a"), store.observeItems().first().map { it.id })

        store.markOpened("a", 3_000)
        assertEquals(listOf("a", "b"), store.observeItems().first().map { it.id })
    }

    @Test
    fun `text and url resources get readable source labels`() = runBlocking {
        store.createResource(resource("text"), TEXT_BODY, null)
        store.createResource(
            resource("url", kind = SourceKind.URL, url = "https://www.example.com/post/1"),
            TEXT_BODY,
            null,
        )

        val items = store.observeItems().first().associateBy { it.id }
        assertEquals("粘贴文本", items.getValue("text").sourceLabel)
        assertEquals("example.com", items.getValue("url").sourceLabel)
    }

    @Test
    fun `createResource persists provided segments instead of rule segmentation`() = runBlocking {
        val id = "l2"
        val drafts = listOf(
            SegmentDraft("LLM 标题", SegmentKind.HEADING),
            SegmentDraft("LLM 清洗后的段落，足够长。", SegmentKind.PARAGRAPH),
        )

        store.createResource(resource(id, title = "LLM 标题"), TEXT_BODY, null, drafts)

        val segments = store.getSegments(id)
        assertEquals(drafts.map { it.text }, segments.map { it.text })
        assertEquals(drafts.map { it.kind }, segments.map { it.kind })
        assertEquals(listOf(0, 1), segments.map { it.index })
    }

    @Test
    fun `hasCachedAudio requires every segment of the active track to be ready`() = runBlocking {
        val id = "r1"
        store.createResource(resource(id), TEXT_BODY, null)
        val track = store.getTracks(id).single()
        val segments = store.getSegments(id)

        segments.forEach { segment ->
            store.upsertClip(
                AudioClip(
                    id = "clip-${segment.index}",
                    trackId = track.id,
                    segmentIndex = segment.index,
                    filePath = null,
                    durationMs = 1_000,
                    status = ClipStatus.READY,
                    error = null,
                ),
            )
        }

        var item = store.observeItems().first().single()
        assertTrue(item.hasCachedAudio)
        assertEquals(segments.size * 1_000L, item.durationMs)

        store.upsertClip(
            AudioClip("clip-0", track.id, 0, null, 0, ClipStatus.ERROR, "boom"),
        )
        item = store.observeItems().first().single()
        assertFalse(item.hasCachedAudio)
    }

    @Test
    fun `deleteResource cascades database rows audio directory and snapshot`() = runBlocking {
        val id = "r1"
        store.createResource(resource(id), TEXT_BODY, null)
        val track = store.getTracks(id).single()
        val audioFile = store.audioFile(id, track.id, 0)
        audioFile.parentFile?.mkdirs()
        audioFile.writeText("fake mp3")
        store.upsertClip(
            AudioClip(
                id = "clip-0",
                trackId = track.id,
                segmentIndex = 0,
                filePath = audioFile.absolutePath,
                durationMs = 1_000,
                status = ClipStatus.READY,
                error = null,
            ),
        )
        val htmlSnapshot = File(filesDir, "sources/$id.html").apply {
            parentFile?.mkdirs()
            writeText("<html></html>")
        }
        assertTrue(store.snapshotFile(id).isFile)

        store.deleteResource(id)

        assertNull(store.getResource(id))
        assertTrue(store.getSegments(id).isEmpty())
        assertTrue(store.getTracks(id).isEmpty())
        assertTrue(store.getClips(track.id).isEmpty())
        assertEquals(0, db.libraryDao().resourceCount())
        assertEquals(0, db.libraryDao().segmentCount())
        assertEquals(0, db.libraryDao().trackCount())
        assertEquals(0, db.libraryDao().clipCount())
        assertFalse(audioFile.exists())
        assertFalse(store.snapshotFile(id).exists())
        assertFalse(htmlSnapshot.exists())
        assertTrue(store.observeItems().first().isEmpty())
    }

    @Test
    fun `deleteUnusedTracks frees inactive track files and keeps active track`() = runBlocking {
        val id = "r1"
        store.createResource(resource(id), TEXT_BODY, null)
        val oldTrack = store.getTracks(id).single()
        val oldFile = store.audioFile(id, oldTrack.id, 0)
        oldFile.parentFile?.mkdirs()
        oldFile.writeText("old audio")

        val newTrack = AudioTrack("track-2", id, TtsModels.AUDIO3, Voices.WENROU, 2_000)
        store.createTrack(newTrack)
        store.setActiveTrack(id, newTrack.id)

        val freed = store.deleteUnusedTracks()

        assertEquals("old audio".length.toLong(), freed)
        assertFalse(oldFile.exists())
        assertTrue(store.getTracks(id).any { it.id == newTrack.id })
        assertFalse(store.getTracks(id).any { it.id == oldTrack.id })
    }

    @Test
    fun `last opened resource is derived from persisted lastOpenedAt`() = runBlocking {
        store.createResource(resource("a", createdAt = 1_000), TEXT_BODY, null)
        store.createResource(resource("b", createdAt = 2_000), TEXT_BODY, null)
        assertNull(store.lastOpenedResourceId())

        store.markOpened("a", 5_000)
        assertEquals("a", store.lastOpenedResourceId())

        val freshStore = RoomLibraryStore(
            db = db,
            filesDir = filesDir,
            settingsStore = settingsStore,
            ioDispatcher = Dispatchers.Unconfined,
        )
        assertEquals("a", freshStore.lastOpenedResourceId())

        freshStore.deleteResource("a")
        assertNull(freshStore.lastOpenedResourceId())
    }

    private fun resource(
        id: String,
        title: String = "文章标题",
        kind: SourceKind = SourceKind.TEXT,
        url: String? = null,
        createdAt: Long = 1_000,
    ): Resource = Resource(
        id = id,
        title = title,
        sourceKind = kind,
        sourceUrl = url,
        byline = null,
        publishedAt = null,
        extractor = null,
        snapshotPath = null,
        currentSegmentIndex = 0,
        currentPositionMs = 0,
        activeTrackId = null,
        createdAt = createdAt,
        lastOpenedAt = 0,
    )

    private companion object {
        const val TEXT_BODY = "第一段足够长的句子。\n\n第二段足够长的句子。"
    }
}
