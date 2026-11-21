package com.cnife.listenreader.data.library

import com.cnife.listenreader.model.LibraryItem
import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.SourceLabels

data class LibraryItemStats(
    val segmentCount: Int,
    val readyClipCount: Int,
    val durationMs: Long,
)

fun Resource.toLibraryItem(stats: LibraryItemStats): LibraryItem = LibraryItem(
    id = id,
    title = title,
    sourceKind = sourceKind,
    sourceLabel = SourceLabels.of(sourceKind, sourceUrl),
    segmentCount = stats.segmentCount,
    durationMs = stats.durationMs,
    hasCachedAudio = activeTrackId != null && stats.segmentCount > 0 && stats.readyClipCount >= stats.segmentCount,
    currentSegmentIndex = currentSegmentIndex,
    currentPositionMs = currentPositionMs,
    lastOpenedAt = lastOpenedAt,
    createdAt = createdAt,
)
