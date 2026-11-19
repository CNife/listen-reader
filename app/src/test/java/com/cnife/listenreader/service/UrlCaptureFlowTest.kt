package com.cnife.listenreader.service

import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.model.SegmentationSource
import com.cnife.listenreader.model.SourceKind
import com.cnife.listenreader.service.ports.PageExtraction
import com.cnife.listenreader.service.ports.PageExtractionException
import com.cnife.listenreader.service.ports.PageSessionState
import com.cnife.listenreader.testing.FakeLibraryStore
import com.cnife.listenreader.testing.FakePageSession
import com.cnife.listenreader.testing.FakePlaybackEngine
import com.cnife.listenreader.testing.FakeScriptCleaner
import com.cnife.listenreader.testing.FakeSettingsStore
import com.cnife.listenreader.testing.FakeSpeechSynthesizer
import com.cnife.listenreader.testing.FixedTimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UrlCaptureFlowTest {

    private class Harness(
        val service: DefaultReadingService,
        val page: FakePageSession,
        val store: FakeLibraryStore,
        val scope: CoroutineScope,
    )

    private fun TestScope.createService(
        page: FakePageSession = FakePageSession(),
        store: FakeLibraryStore = FakeLibraryStore(),
    ): Harness {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = DefaultReadingService(
            pageSession = page,
            scriptCleaner = FakeScriptCleaner(),
            speechSynthesizer = FakeSpeechSynthesizer(),
            libraryStore = store,
            playbackEngine = FakePlaybackEngine(),
            settingsStore = FakeSettingsStore(),
            timeProvider = FixedTimeProvider(),
            scope = scope,
        )
        return Harness(service, page, store, scope)
    }

    private fun articleExtraction() = PageExtraction(
        title = "一篇文章",
        byline = "作者甲",
        publishedAt = "2024-05-01T08:00:00Z",
        markdown = "# 一篇文章\n\n第一段足够长的正文句子。\n\n第二段足够长的正文句子。",
        text = "一篇文章\n\n第一段足够长的正文句子。\n\n第二段足够长的正文句子。",
        pageText = "导航 一篇文章 第一段足够长的正文句子。 第二段足够长的正文句子。 版权",
        extractor = "readability",
        score = 0.82,
        confidence = 0.7,
        needsExpand = false,
        html = "<article><h1>一篇文章</h1><p>第一段足够长的正文句子。</p></article>",
    )

    @Test
    fun `url capture builds preview from l1 extraction`() = runTest {
        val harness = createService()
        harness.page.extraction = articleExtraction()

        harness.service.beginUrlCapture("https://www.example.com/post/1")
        advanceUntilIdle()
        assertEquals("https://www.example.com/post/1", harness.page.loadedUrl)
        assertFalse(harness.service.capture.value.url.isBlank())

        harness.service.extractPage()
        advanceUntilIdle()

        val state = harness.service.capture.value
        val preview = state.preview!!
        assertFalse(state.isExtracting)
        assertNull(state.error)
        assertEquals(SourceKind.URL, preview.sourceKind)
        assertEquals("example.com", preview.sourceLabel)
        assertEquals("https://www.example.com/post/1", preview.sourceUrl)
        assertEquals("一篇文章", preview.title)
        assertEquals("readability", preview.extractor)
        assertEquals(0.82, preview.score!!, 0.0001)
        assertEquals("作者甲", preview.byline)
        assertEquals("2024-05-01T08:00:00Z", preview.publishedAt)
        assertEquals(SegmentationSource.RULE, preview.segmentation)
        assertEquals(L2Status.NOT_CONFIGURED, preview.l2Status)
        assertEquals(3, preview.segmentDrafts.size)

        val extraction = state.extraction!!
        assertEquals("readability", extraction.extractor)
        assertFalse(extraction.needsExpand)

        harness.scope.cancel()
    }

    @Test
    fun `saving url capture stores source metadata and markdown html snapshot`() = runTest {
        val harness = createService()
        val extraction = articleExtraction()
        harness.page.extraction = extraction
        harness.service.beginUrlCapture("https://www.example.com/post/1")
        advanceUntilIdle()
        harness.service.extractPage()
        advanceUntilIdle()

        val id = harness.service.saveCapture()!!
        advanceUntilIdle()
        assertEquals(id, harness.service.capture.value.savedResourceId)
        assertFalse(harness.service.capture.value.isSaving)

        val resource = harness.store.createdResource!!
        assertEquals(SourceKind.URL, resource.sourceKind)
        assertEquals("https://www.example.com/post/1", resource.sourceUrl)
        assertEquals("readability", resource.extractor)
        assertEquals("作者甲", resource.byline)
        assertEquals("2024-05-01T08:00:00Z", resource.publishedAt)
        assertEquals("一篇文章", resource.title)
        assertEquals(extraction.markdown, harness.store.createdSourceText)
        assertEquals(extraction.html, harness.store.createdHtml)
        assertEquals(3, harness.store.savedSegments.size)

        harness.scope.cancel()
    }

    @Test
    fun `page quality gate is surfaced for folded or short content`() = runTest {
        val harness = createService()
        harness.page.extraction = articleExtraction().copy(
            text = "太短的正文。",
            markdown = "太短的正文。",
            needsExpand = true,
            degradedReason = "正文过短（6 字）",
            score = 0.2,
            confidence = 0.3,
        )
        harness.service.beginUrlCapture("https://example.com/short")
        advanceUntilIdle()
        harness.service.extractPage()
        advanceUntilIdle()

        val info = harness.service.capture.value.extraction!!
        assertTrue(info.needsExpand)
        assertEquals("正文过短（6 字）", info.degradedReason)
        assertEquals("example.com", harness.service.capture.value.preview!!.sourceLabel)

        harness.scope.cancel()
    }

    @Test
    fun `needs user action blocks extraction with readable reason`() = runTest {
        val harness = createService()
        harness.service.beginUrlCapture("https://example.com/login")
        advanceUntilIdle()
        harness.page.pageStateValue = PageSessionState.NeedsUserAction("页面需要登录，请在下方登录后点「提取正文」")
        advanceUntilIdle()
        assertTrue(harness.service.capture.value.page is PageUiState.NeedsUserAction)

        harness.service.extractPage()
        advanceUntilIdle()

        assertNull(harness.service.capture.value.preview)
        val message = harness.service.capture.value.error!!.message
        assertTrue(message.contains("登录"))

        harness.scope.cancel()
    }

    @Test
    fun `extraction failure keeps page and shows retryable error`() = runTest {
        val harness = createService()
        harness.page.extractionError = PageExtractionException("网页提取超时，请重试")
        harness.service.beginUrlCapture("https://example.com/post")
        advanceUntilIdle()

        harness.service.extractPage()
        advanceUntilIdle()

        val state = harness.service.capture.value
        assertNull(state.preview)
        assertFalse(state.isExtracting)
        assertEquals("网页提取超时，请重试", state.error!!.message)
        assertTrue(state.error!!.retryable)

        harness.scope.cancel()
    }

    @Test
    fun `invalid url is rejected without loading a page`() = runTest {
        val harness = createService()

        harness.service.loadUrl("ftp://example.com/file")
        advanceUntilIdle()

        assertNull(harness.page.loadedUrl)
        assertEquals("仅支持 http/https 网址", harness.service.capture.value.error!!.message)

        harness.service.loadUrl("   ")
        advanceUntilIdle()
        assertEquals("请输入网址", harness.service.capture.value.error!!.message)

        harness.scope.cancel()
    }

    @Test
    fun `short page is expanded through l3 before preview`() = runTest {
        val harness = createService()
        harness.page.extraction = articleExtraction().copy(
            text = "太短的正文。",
            markdown = "太短的正文。",
            needsExpand = true,
            degradedReason = "正文过短（6 字）",
        )
        harness.page.expandedExtraction = articleExtraction().copy(
            text = "一篇文章\n\n" + "展开后的完整正文段落。".repeat(20),
            markdown = "# 一篇文章\n\n" + "展开后的完整正文段落。".repeat(20),
            needsExpand = false,
            expandRounds = 1,
        )

        harness.service.beginUrlCapture("https://example.com/short")
        advanceUntilIdle()
        harness.service.extractPage()
        advanceUntilIdle()

        val state = harness.service.capture.value
        assertEquals(1, harness.page.expandCalls)
        assertFalse(state.isExtracting)
        assertNull(state.extractionStage)
        assertFalse(state.extraction!!.needsExpand)
        assertEquals(1, state.extraction!!.expandRounds)
        assertTrue(state.preview!!.body.contains("展开后的完整正文段落"))

        harness.scope.cancel()
    }

    @Test
    fun `still degraded extraction offers page text fallback that is editable and saveable`() = runTest {
        val harness = createService()
        harness.page.extraction = articleExtraction().copy(
            text = "太短的正文。",
            markdown = "太短的正文。",
            pageText = "页面全文第一段，包含导航和页脚。\n页面全文第二段，仍然可以朗读。",
            needsExpand = true,
            degradedReason = "正文过短（6 字）",
        )

        harness.service.beginUrlCapture("https://example.com/short")
        advanceUntilIdle()
        harness.service.extractPage()
        advanceUntilIdle()
        assertTrue(harness.service.capture.value.extraction!!.needsExpand)

        harness.service.useFullPage()
        advanceUntilIdle()

        val state = harness.service.capture.value
        val info = state.extraction!!
        assertTrue(info.usedFullPage)
        assertTrue(info.needsExpand)
        assertEquals("page-text", info.extractor)
        val preview = state.preview!!
        assertTrue(preview.body.contains("页面全文第一段"))
        assertEquals(preview.body.length, preview.charCount)
        assertTrue(preview.segmentDrafts.isNotEmpty())

        harness.service.updatePreviewBody("编辑后的页面全文。")
        assertEquals("编辑后的页面全文。", harness.service.capture.value.preview!!.body)

        val saved = harness.service.saveCapture()
        advanceUntilIdle()
        assertEquals(saved, harness.service.capture.value.savedResourceId)
        assertTrue(harness.store.savedSegments.any { it.text.contains("编辑后的页面全文。") })

        harness.scope.cancel()
    }

    @Test
    fun `use full page before extraction reports readable error`() = runTest {
        val harness = createService()
        harness.service.beginUrlCapture("https://example.com/post")
        advanceUntilIdle()

        harness.service.useFullPage()
        advanceUntilIdle()

        assertNull(harness.service.capture.value.preview)
        assertTrue(harness.service.capture.value.error!!.message.contains("页面全文"))

        harness.scope.cancel()
    }

    @Test
    fun `share intent routes urls to page capture and other text to text capture`() = runTest {
        val harness = createService()

        harness.service.handleShareIntent("推荐 https://example.com/post/9 很好")
        advanceUntilIdle()
        assertEquals(CaptureMode.URL, harness.service.capture.value.mode)
        assertEquals("https://example.com/post/9", harness.page.loadedUrl)

        harness.service.handleShareIntent("只是普通文本，没有链接。")
        advanceUntilIdle()
        assertEquals(CaptureMode.TEXT, harness.service.capture.value.mode)
        assertEquals("粘贴文本", harness.service.capture.value.preview!!.title)
        assertEquals("只是普通文本，没有链接。", harness.service.capture.value.preview!!.body)

        harness.scope.cancel()
    }
}