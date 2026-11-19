package com.cnife.listenreader.data.library

import androidx.room.withTransaction
import com.cnife.listenreader.core.RuleSegmenter
import com.cnife.listenreader.data.db.AudioClipEntity
import com.cnife.listenreader.data.db.AudioTrackEntity
import com.cnife.listenreader.data.db.LibraryItemRow
import com.cnife.listenreader.data.db.ListenReaderDatabase
import com.cnife.listenreader.data.db.ResourceEntity
import com.cnife.listenreader.data.db.SegmentEntity
import com.cnife.listenreader.model.AudioClip
import com.cnife.listenreader.model.AudioTrack
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.Segment
import com.cnife.listenreader.model.SegmentDraft
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.service.StorageUsage
import com.cnife.listenreader.service.ports.LibraryStore
import com.cnife.listenreader.service.ports.SettingsStore
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room v1 + 应用私有文件目录的 LibraryStore 实现。
 * 音频文件：`filesDir/audio/<resourceId>/<trackId>/<segmentIndex>.mp3`；
 * Markdown 快照：`filesDir/sources/<resourceId>.md`。
 */
class RoomLibraryStore(
    private val db: ListenReaderDatabase,
    private val filesDir: File,
    private val settingsStore: SettingsStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LibraryStore {

    private val dao get() = db.libraryDao()
    private val audioRoot = File(filesDir, "audio")
    private val snapshotRoot = File(filesDir, "sources")

    /** 进程内缓存活动轨，仅用于同步的存储统计；权威值始终在 Room。 */
    private val activeTracks = ConcurrentHashMap<String, String>()
    private var lastOpened: String? = null

    override fun observeItems(): Flow<List<LibraryItem>> =
        dao.observeLibraryItems().map { rows -> rows.map { it.toItem() } }

    override suspend fun getResource(id: String): Resource? =
        withContext(ioDispatcher) { dao.resource(id)?.toModel() }

    override suspend fun getSegments(resourceId: String): List<Segment> =
        withContext(ioDispatcher) { dao.segments(resourceId).map { it.toModel() } }

    override suspend fun getTracks(resourceId: String): List<AudioTrack> =
        withContext(ioDispatcher) { dao.tracks(resourceId).map { it.toModel() } }

    override suspend fun getClips(trackId: String): List<AudioClip> =
        withContext(ioDispatcher) { dao.clips(trackId).map { it.toModel() } }

    override suspend fun createResource(
        resource: Resource,
        sourceText: String,
        html: String?,
        segments: List<SegmentDraft>,
    ): String =
        withContext(ioDispatcher) {
            val snapshot = snapshotFile(resource.id)
            snapshot.parentFile?.mkdirs()
            snapshot.writeText(sourceText)
            val snapshotHtml = File(snapshotRoot, "${resource.id}.html")
            if (html != null) snapshotHtml.writeText(html)

            val trackId = UUID.randomUUID().toString()
            try {
                db.withTransaction {
                    val appSettings = settingsStore.settings.first()
                    val drafts = segments.ifEmpty { RuleSegmenter.segment(resource.title, sourceText) }
                    dao.insertResource(
                        resource.toEntity().copy(
                            snapshotPath = snapshot.absolutePath,
                            activeTrackId = trackId,
                        ),
                    )
                    if (drafts.isNotEmpty()) {
                        dao.insertSegments(
                            drafts.mapIndexed { index, draft ->
                                SegmentEntity(
                                    resourceId = resource.id,
                                    index = index,
                                    text = draft.text,
                                    kind = draft.kind,
                                )
                            },
                        )
                    }
                    dao.insertTrack(
                        AudioTrackEntity(
                            id = trackId,
                            resourceId = resource.id,
                            model = appSettings.ttsModel,
                            voice = appSettings.voice,
                            createdAt = resource.createdAt,
                        ),
                    )
                }
                activeTracks[resource.id] = trackId
                resource.id
            } catch (t: Throwable) {
                snapshot.delete()
                snapshotHtml.delete()
                throw t
            }
        }

    override suspend fun updateProgress(resourceId: String, segmentIndex: Int, positionMs: Long) {
        withContext(ioDispatcher) { dao.updateProgress(resourceId, segmentIndex, positionMs) }
    }

    override suspend fun markOpened(resourceId: String, at: Long) {
        withContext(ioDispatcher) { dao.markOpened(resourceId, at) }
        lastOpened = resourceId
    }

    override suspend fun setActiveTrack(resourceId: String, trackId: String?) {
        withContext(ioDispatcher) { dao.setActiveTrack(resourceId, trackId) }
        if (trackId == null) activeTracks.remove(resourceId) else activeTracks[resourceId] = trackId
    }

    override suspend fun createTrack(track: AudioTrack) {
        withContext(ioDispatcher) { dao.insertTrack(track.toEntity()) }
    }

    override suspend fun upsertClip(clip: AudioClip) {
        withContext(ioDispatcher) { dao.upsertClip(clip.toEntity()) }
    }

    override suspend fun deleteResource(resourceId: String) {
        withContext(ioDispatcher) {
            db.withTransaction { dao.deleteResource(resourceId) }
            File(audioRoot, resourceId).deleteRecursively()
            snapshotFile(resourceId).delete()
            File(snapshotRoot, "$resourceId.html").delete()
            activeTracks.remove(resourceId)
            if (lastOpened == resourceId) lastOpened = null
        }
    }

    override suspend fun snapshotText(resourceId: String): String? {
        val path = getResource(resourceId)?.snapshotPath
        val fromResource = path?.let { readSnapshotFile(it) }
        return fromResource ?: readSnapshotFile(snapshotFile(resourceId).absolutePath)
    }

    private suspend fun readSnapshotFile(path: String): String? = withContext(ioDispatcher) {
        runCatching {
            val file = resolve(path)
            if (file.isFile) file.readText() else null
        }.getOrNull()
    }

    override fun audioFile(resourceId: String, trackId: String, segmentIndex: Int): File =
        File(audioRoot, "$resourceId/$trackId/$segmentIndex.mp3")

    override fun snapshotFile(resourceId: String): File = File(snapshotRoot, "$resourceId.md")

    override suspend fun deleteUnusedTracks(): Long = withContext(ioDispatcher) {
        val active = dao.resources().mapNotNull { it.activeTrackId }.toSet()
        val unused = dao.allTracks().filter { it.id !in active }
        var freed = 0L
        unused.forEach { track ->
            freed += directorySize(File(audioRoot, "${track.resourceId}/${track.id}"))
            activeTracks.remove(track.resourceId, track.id)
        }
        if (unused.isNotEmpty()) {
            db.withTransaction { dao.deleteTracks(unused.map { it.id }) }
        }
        unused.forEach { File(audioRoot, "${it.resourceId}/${it.id}").deleteRecursively() }
        freed
    }

    override suspend fun lastOpenedResourceId(): String? {
        lastOpened?.let { return it }
        val stored = runCatching { dao.lastOpenedResourceId() }.getOrNull()
        lastOpened = stored
        return stored
    }

    override fun saveLastOpenedResourceId(resourceId: String?) {
        lastOpened = resourceId
    }

    override suspend fun computeStorageUsage(): StorageUsage = withContext(ioDispatcher) {
        // 活动轨以 Room 为准，避免进程重启后内存缓存为空而把活动轨误算成旧轨。
        val activePairs = runCatching {
            dao.resources()
                .mapNotNull { row -> row.activeTrackId?.let { trackId -> row.id to trackId } }
                .toSet()
        }.getOrNull() ?: activeTracks.entries.map { (resourceId, trackId) -> resourceId to trackId }.toSet()
        var activeBytes = 0L
        var oldBytes = 0L
        val resourceDirs = audioRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        resourceDirs.forEach { resourceDir ->
            val trackDirs = resourceDir.listFiles()?.filter { it.isDirectory }.orEmpty()
            trackDirs.forEach { trackDir ->
                val bytes = directorySize(trackDir)
                if ((resourceDir.name to trackDir.name) in activePairs) {
                    activeBytes += bytes
                } else {
                    oldBytes += bytes
                }
            }
        }
        StorageUsage(activeBytes, oldBytes)
    }

    private fun resolve(path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(filesDir, path)
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun sourceLabel(kind: SourceKind, url: String?): String {
        if (kind == SourceKind.TEXT) return "粘贴文本"
        val host = runCatching { URI(url.orEmpty()).host }.getOrNull()
        return host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: "网页"
    }
}

private fun ResourceEntity.toModel(): Resource = Resource(
    id = id,
    title = title,
    sourceKind = sourceKind,
    sourceUrl = sourceUrl,
    byline = byline,
    publishedAt = publishedAt,
    extractor = extractor,
    snapshotPath = snapshotPath,
    currentSegmentIndex = currentSegmentIndex,
    currentPositionMs = currentPositionMs,
    activeTrackId = activeTrackId,
    createdAt = createdAt,
    lastOpenedAt = lastOpenedAt,
)

private fun Resource.toEntity(): ResourceEntity = ResourceEntity(
    id = id,
    title = title,
    sourceKind = sourceKind,
    sourceUrl = sourceUrl,
    byline = byline,
    publishedAt = publishedAt,
    extractor = extractor,
    snapshotPath = snapshotPath,
    currentSegmentIndex = currentSegmentIndex,
    currentPositionMs = currentPositionMs,
    activeTrackId = activeTrackId,
    createdAt = createdAt,
    lastOpenedAt = lastOpenedAt,
)

private fun SegmentEntity.toModel(): Segment = Segment(
    resourceId = resourceId,
    index = index,
    text = text,
    kind = kind,
)

private fun AudioTrackEntity.toModel(): AudioTrack = AudioTrack(
    id = id,
    resourceId = resourceId,
    model = model,
    voice = voice,
    createdAt = createdAt,
)

private fun AudioTrack.toEntity(): AudioTrackEntity = AudioTrackEntity(
    id = id,
    resourceId = resourceId,
    model = model,
    voice = voice,
    createdAt = createdAt,
)

private fun AudioClipEntity.toModel(): AudioClip = AudioClip(
    id = id,
    trackId = trackId,
    segmentIndex = segmentIndex,
    filePath = filePath,
    durationMs = durationMs,
    status = status,
    error = error,
)

private fun AudioClip.toEntity(): AudioClipEntity = AudioClipEntity(
    id = id,
    trackId = trackId,
    segmentIndex = segmentIndex,
    filePath = filePath,
    durationMs = durationMs,
    status = status,
    error = error,
)

private fun LibraryItemRow.toItem(): LibraryItem {
    val kind = SourceKind.valueOf(sourceKind)
    return LibraryItem(
        id = id,
        title = title,
        sourceKind = kind,
        sourceLabel = when (kind) {
            SourceKind.TEXT -> "粘贴文本"
            SourceKind.URL -> {
                val host = runCatching { URI(sourceUrl.orEmpty()).host }.getOrNull()
                host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: "网页"
            }
        },
        segmentCount = segmentCount,
        durationMs = durationMs,
        hasCachedAudio = activeTrackId != null && segmentCount > 0 && readyClipCount >= segmentCount,
        currentSegmentIndex = currentSegmentIndex,
        currentPositionMs = currentPositionMs,
        lastOpenedAt = lastOpenedAt,
    )
}
