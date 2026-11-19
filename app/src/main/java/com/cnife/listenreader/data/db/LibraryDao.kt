package com.cnife.listenreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Insert
    suspend fun insertResource(resource: ResourceEntity)

    @Query("SELECT * FROM resources WHERE id = :id")
    suspend fun resource(id: String): ResourceEntity?

    @Query("SELECT * FROM resources")
    suspend fun resources(): List<ResourceEntity>

    @Query("UPDATE resources SET lastOpenedAt = :at WHERE id = :id")
    suspend fun markOpened(id: String, at: Long)

    @Query("UPDATE resources SET currentSegmentIndex = :segmentIndex, currentPositionMs = :positionMs WHERE id = :id")
    suspend fun updateProgress(id: String, segmentIndex: Int, positionMs: Long)

    @Query("UPDATE resources SET activeTrackId = :trackId WHERE id = :id")
    suspend fun setActiveTrack(id: String, trackId: String?)

    @Insert
    suspend fun insertSegments(segments: List<SegmentEntity>)

    @Query("SELECT * FROM segments WHERE resourceId = :resourceId ORDER BY segmentIndex ASC")
    suspend fun segments(resourceId: String): List<SegmentEntity>

    @Insert
    suspend fun insertTrack(track: AudioTrackEntity)

    @Query("SELECT * FROM audio_tracks WHERE resourceId = :resourceId ORDER BY createdAt ASC")
    suspend fun tracks(resourceId: String): List<AudioTrackEntity>

    @Query("SELECT * FROM audio_tracks")
    suspend fun allTracks(): List<AudioTrackEntity>

    @Query("DELETE FROM audio_tracks WHERE id IN (:ids)")
    suspend fun deleteTracks(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClip(clip: AudioClipEntity)

    @Query("SELECT * FROM audio_clips WHERE trackId = :trackId ORDER BY segmentIndex ASC")
    suspend fun clips(trackId: String): List<AudioClipEntity>

    @Query("DELETE FROM resources WHERE id = :id")
    suspend fun deleteResource(id: String)

    @Query(
        """
        SELECT r.id AS id,
               r.title AS title,
               r.sourceKind AS sourceKind,
               r.sourceUrl AS sourceUrl,
               r.currentSegmentIndex AS currentSegmentIndex,
               r.currentPositionMs AS currentPositionMs,
               r.lastOpenedAt AS lastOpenedAt,
               r.createdAt AS createdAt,
               r.activeTrackId AS activeTrackId,
               (SELECT COUNT(*) FROM segments s WHERE s.resourceId = r.id) AS segmentCount,
               (SELECT COUNT(*) FROM audio_clips c
                    INNER JOIN audio_tracks t ON c.trackId = t.id
                    WHERE t.resourceId = r.id AND t.id = r.activeTrackId AND c.status = 'READY') AS readyClipCount,
               (SELECT COALESCE(SUM(c.durationMs), 0) FROM audio_clips c
                    INNER JOIN audio_tracks t ON c.trackId = t.id
                    WHERE t.resourceId = r.id AND t.id = r.activeTrackId AND c.status = 'READY') AS durationMs
        FROM resources r
        ORDER BY r.lastOpenedAt DESC, r.createdAt DESC
        """
    )
    fun observeLibraryItems(): Flow<List<LibraryItemRow>>

    @Query("SELECT COUNT(*) FROM resources")
    suspend fun resourceCount(): Int

    @Query("SELECT COUNT(*) FROM segments")
    suspend fun segmentCount(): Int

    @Query("SELECT COUNT(*) FROM audio_tracks")
    suspend fun trackCount(): Int

    @Query("SELECT COUNT(*) FROM audio_clips")
    suspend fun clipCount(): Int
}
