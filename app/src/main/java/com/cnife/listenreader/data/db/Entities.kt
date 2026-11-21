package com.cnife.listenreader.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.cnife.listenreader.model.ClipStatus
import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.model.SourceKind

@Entity(tableName = "resources")
data class ResourceEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceKind: SourceKind,
    val sourceUrl: String?,
    val byline: String?,
    val publishedAt: String?,
    val extractor: String?,
    val snapshotPath: String?,
    val currentSegmentIndex: Int,
    val currentPositionMs: Long,
    val activeTrackId: String?,
    val createdAt: Long,
    val lastOpenedAt: Long,
)

@Entity(
    tableName = "segments",
    primaryKeys = ["resourceId", "segmentIndex"],
    foreignKeys = [
        ForeignKey(
            entity = ResourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["resourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("resourceId")],
)
data class SegmentEntity(
    val resourceId: String,
    @ColumnInfo(name = "segmentIndex") val index: Int,
    val text: String,
    val kind: SegmentKind,
)

@Entity(
    tableName = "audio_tracks",
    foreignKeys = [
        ForeignKey(
            entity = ResourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["resourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("resourceId")],
)
data class AudioTrackEntity(
    @PrimaryKey val id: String,
    val resourceId: String,
    val model: String,
    val voice: String,
    val createdAt: Long,
)

@Entity(
    tableName = "audio_clips",
    foreignKeys = [
        ForeignKey(
            entity = AudioTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trackId", "segmentIndex"], unique = true)],
)
data class AudioClipEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val segmentIndex: Int,
    val filePath: String?,
    val durationMs: Long,
    val status: ClipStatus,
    val error: String?,
)

data class LibraryItemRow(
    val id: String,
    val title: String,
    val sourceKind: String,
    val sourceUrl: String?,
    val currentSegmentIndex: Int,
    val currentPositionMs: Long,
    val lastOpenedAt: Long,
    val createdAt: Long,
    val activeTrackId: String?,
    val segmentCount: Int,
    val readyClipCount: Int,
    val durationMs: Long,
)

class Converters {
    @TypeConverter
    fun fromSourceKind(value: SourceKind): String = value.name

    @TypeConverter
    fun toSourceKind(value: String): SourceKind = SourceKind.valueOf(value)

    @TypeConverter
    fun fromSegmentKind(value: SegmentKind): String = value.name

    @TypeConverter
    fun toSegmentKind(value: String): SegmentKind = SegmentKind.valueOf(value)

    @TypeConverter
    fun fromClipStatus(value: ClipStatus): String = value.name

    @TypeConverter
    fun toClipStatus(value: String): ClipStatus = ClipStatus.valueOf(value)
}
