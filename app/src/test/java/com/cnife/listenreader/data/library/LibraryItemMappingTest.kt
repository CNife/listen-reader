package com.cnife.listenreader.data.library

import com.cnife.listenreader.model.Resource
import com.cnife.listenreader.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryItemMappingTest {

    @Test
    fun `all segments ready marks cached audio and keeps total duration`() {
        val item = resource(activeTrackId = "track-1").toLibraryItem(
            LibraryItemStats(segmentCount = 3, readyClipCount = 3, durationMs = 9_000),
        )

        assertTrue(item.hasCachedAudio)
        assertEquals(3, item.segmentCount)
        assertEquals(9_000L, item.durationMs)
        assertEquals("微信公众号", item.sourceLabel)
        assertEquals(2, item.currentSegmentIndex)
        assertEquals(1_500L, item.currentPositionMs)
        assertEquals(4_000L, item.createdAt)
        assertEquals(5_000L, item.lastOpenedAt)
    }

    @Test
    fun `partially ready track is not cached audio`() {
        val item = resource(activeTrackId = "track-1").toLibraryItem(
            LibraryItemStats(segmentCount = 3, readyClipCount = 2, durationMs = 6_000),
        )

        assertFalse(item.hasCachedAudio)
        assertEquals(6_000L, item.durationMs)
    }

    @Test
    fun `resource without active track is not cached audio`() {
        val item = resource(activeTrackId = null).toLibraryItem(
            LibraryItemStats(segmentCount = 3, readyClipCount = 0, durationMs = 0),
        )

        assertFalse(item.hasCachedAudio)
        assertEquals(0L, item.durationMs)
    }

    @Test
    fun `resource without segments is never cached audio`() {
        val item = resource(activeTrackId = "track-1").toLibraryItem(
            LibraryItemStats(segmentCount = 0, readyClipCount = 0, durationMs = 0),
        )

        assertFalse(item.hasCachedAudio)
    }

    private fun resource(activeTrackId: String?) = Resource(
        id = "resource-1",
        title = "通勤读物",
        sourceKind = SourceKind.URL,
        sourceUrl = "https://mp.weixin.qq.com/s/demo",
        byline = null,
        publishedAt = null,
        extractor = null,
        snapshotPath = null,
        currentSegmentIndex = 2,
        currentPositionMs = 1_500,
        activeTrackId = activeTrackId,
        createdAt = 4_000,
        lastOpenedAt = 5_000,
    )
}
