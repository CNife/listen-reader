package com.cnife.listenreader.service.ports

import com.cnife.listenreader.model.AudioClip
import com.cnife.listenreader.model.AudioTrack
import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.Segment
import com.cnife.listenreader.model.SegmentDraft
import com.cnife.listenreader.service.StorageUsage
import java.io.File
import kotlinx.coroutines.flow.Flow

interface LibraryStore {
    fun observeItems(): Flow<List<LibraryItem>>
    suspend fun getResource(id: String): Resource?
    suspend fun getSegments(resourceId: String): List<Segment>
    suspend fun getTracks(resourceId: String): List<AudioTrack>
    suspend fun getClips(trackId: String): List<AudioClip>
    suspend fun createResource(
        resource: Resource,
        sourceText: String,
        html: String?,
        segments: List<SegmentDraft> = emptyList(),
    ): String
    suspend fun updateProgress(resourceId: String, segmentIndex: Int, positionMs: Long)
    suspend fun markOpened(resourceId: String, at: Long)
    suspend fun setActiveTrack(resourceId: String, trackId: String?)
    suspend fun createTrack(track: AudioTrack)
    suspend fun upsertClip(clip: AudioClip)
    suspend fun deleteResource(resourceId: String)
    fun audioFile(resourceId: String, trackId: String, segmentIndex: Int): File
    suspend fun deleteUnusedTracks(): Long
    suspend fun lastOpenedResourceId(): String?
    fun saveLastOpenedResourceId(resourceId: String?)
    suspend fun computeStorageUsage(): StorageUsage
}
