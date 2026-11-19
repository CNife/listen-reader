package com.cnife.listenreader.model

enum class SourceKind { URL, TEXT }

enum class SegmentKind { HEADING, PARAGRAPH, QUOTE }

enum class ClipStatus { PENDING, SYNTHESIZING, READY, ERROR }

enum class SegmentationSource { RULE, L2 }

enum class L2Status { NOT_CONFIGURED, NOT_RUN, RUNNING, SUCCEEDED, FAILED }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LibraryFilter { ALL, WEB, TEXT, CACHED }

data class SegmentDraft(val text: String, val kind: SegmentKind)

data class Resource(
    val id: String,
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

data class Segment(
    val resourceId: String,
    val index: Int,
    val text: String,
    val kind: SegmentKind,
)

data class AudioTrack(
    val id: String,
    val resourceId: String,
    val model: String,
    val voice: String,
    val createdAt: Long,
)

data class AudioClip(
    val id: String,
    val trackId: String,
    val segmentIndex: Int,
    val filePath: String?,
    val durationMs: Long,
    val status: ClipStatus,
    val error: String?,
)

data class LibraryItem(
    val id: String,
    val title: String,
    val sourceKind: SourceKind,
    val sourceLabel: String,
    val segmentCount: Int,
    val durationMs: Long,
    val hasCachedAudio: Boolean,
    val currentSegmentIndex: Int,
    val currentPositionMs: Long,
    val lastOpenedAt: Long,
)
