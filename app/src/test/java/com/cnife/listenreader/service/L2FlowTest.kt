package com.cnife.listenreader.service

import com.cnife.listenreader.core.RuleSegmenter
import com.cnife.listenreader.model.L2Status
import com.cnife.listenreader.model.SegmentDraft
import com.cnife.listenreader.model.SegmentKind
import com.cnife.listenreader.model.SegmentationSource
import com.cnife.listenreader.service.ports.CleanResult
import com.cnife.listenreader.service.ports.ConnectionTestResult
import com.cnife.listenreader.testing.FakeLibraryStore
import com.cnife.listenreader.testing.FakePageSession
import com.cnife.listenreader.testing.FakePlaybackEngine
import com.cnife.listenreader.testing.FakeScriptCleaner
import com.cnife.listenreader.testing.FakeSettingsStore
import com.cnife.listenreader.testing.FakeSpeechSynthesizer
import com.cnife.listenreader.testing.FixedTimeProvider
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class L2FlowTest {

    private class Harness(
        val service: DefaultReadingService,
        val store: FakeLibraryStore,
        val settings: FakeSettingsStore,
        val scope: CoroutineScope,
    )

    private fun TestScope.createHarness(
        cleaner: FakeScriptCleaner,
        store: FakeLibraryStore = FakeLibraryStore(),
        settings: FakeSettingsStore = FakeSettingsStore(),
    ): Harness {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = DefaultReadingService(
            pageSession = FakePageSession(),
            scriptCleaner = cleaner,
            speechSynthesizer = FakeSpeechSynthesizer(),
            libraryStore = store,
            playbackEngine = FakePlaybackEngine(),
            settingsStore = settings,
            timeProvider = FixedTimeProvider(),
            scope = scope,
        )
        return Harness(service, store, settings, scope)
    }

    private val l2Segments = listOf(
        SegmentDraft("文章标题", SegmentKind.HEADING),
        SegmentDraft("LLM 清洗后的第一段。", SegmentKind.PARAGRAPH),
    )

    @Test
    fun `configured cleaner runs l2 automatically and replaces rule drafts`() = runTest {
        val cleaner = FakeScriptCleaner(configured = true, result = CleanResult.Success(l2Segments))
        val harness = createHarness(cleaner)
        advanceUntilIdle()

        harness.service.beginTextCapture("文章标题\n\n规则分段的原文句子。")

        assertEquals(L2Status.RUNNING, harness.service.capture.value.preview?.l2Status)
        advanceUntilIdle()

        val preview = harness.service.capture.value.preview!!
        assertEquals(L2Status.SUCCEEDED, preview.l2Status)
        assertEquals(SegmentationSource.L2, preview.segmentation)
        assertEquals(l2Segments, preview.segmentDrafts)
        assertNull(preview.l2Error)
        assertEquals(listOf("文章标题" to "规则分段的原文句子。"), cleaner.calls)
        harness.scope.cancel()
    }

    @Test
    fun `l2 failure falls back to rule drafts and keeps a readable error`() = runTest {
        val cleaner = FakeScriptCleaner(
            configured = true,
            result = CleanResult.Failure("LLM 返回的清洗结果不是有效 JSON，已重试一次", retryable = true),
        )
        val harness = createHarness(cleaner)
        advanceUntilIdle()

        harness.service.beginTextCapture("文章标题\n\n规则分段的原文句子。")
        advanceUntilIdle()

        val preview = harness.service.capture.value.preview!!
        assertEquals(L2Status.FAILED, preview.l2Status)
        assertEquals(SegmentationSource.RULE, preview.segmentation)
        assertEquals("LLM 返回的清洗结果不是有效 JSON，已重试一次", preview.l2Error)
        assertEquals(
            RuleSegmenter.segment("文章标题", "规则分段的原文句子。"),
            preview.segmentDrafts,
        )
        harness.scope.cancel()
    }

    @Test
    fun `retry l2 succeeds after an earlier failure`() = runTest {
        val cleaner = FakeScriptCleaner(
            configured = true,
            result = CleanResult.Failure("LLM 服务暂时不可用（HTTP 500），请稍后重试"),
        )
        val harness = createHarness(cleaner)
        advanceUntilIdle()

        harness.service.beginTextCapture("文章标题\n\n规则分段的原文句子。")
        advanceUntilIdle()
        assertEquals(L2Status.FAILED, harness.service.capture.value.preview?.l2Status)

        cleaner.result = CleanResult.Success(l2Segments)
        harness.service.retryL2()
        assertEquals(L2Status.RUNNING, harness.service.capture.value.preview?.l2Status)
        advanceUntilIdle()

        val preview = harness.service.capture.value.preview!!
        assertEquals(L2Status.SUCCEEDED, preview.l2Status)
        assertEquals(SegmentationSource.L2, preview.segmentation)
        assertEquals(l2Segments, preview.segmentDrafts)
        assertEquals(2, cleaner.calls.size)
        harness.scope.cancel()
    }

    @Test
    fun `without an llm key l2 is blocked with guidance`() = runTest {
        val cleaner = FakeScriptCleaner(configured = false)
        val harness = createHarness(cleaner)
        advanceUntilIdle()

        harness.service.beginTextCapture("文章标题\n\n规则分段的原文句子。")
        advanceUntilIdle()

        val preview = harness.service.capture.value.preview!!
        assertEquals(L2Status.NOT_CONFIGURED, preview.l2Status)
        assertEquals(SegmentationSource.RULE, preview.segmentation)
        assertNotNull(preview.l2Error)
        assertTrue(preview.l2Error!!.contains("未配置"))
        assertEquals(
            RuleSegmenter.segment("文章标题", "规则分段的原文句子。"),
            preview.segmentDrafts,
        )
        assertTrue(cleaner.calls.isEmpty())

        harness.service.retryL2()
        advanceUntilIdle()
        assertEquals(L2Status.NOT_CONFIGURED, harness.service.capture.value.preview?.l2Status)
        assertTrue(cleaner.calls.isEmpty())
        harness.scope.cancel()
    }

    @Test
    fun `editing the body returns to rule drafts without calling l2 again`() = runTest {
        val cleaner = FakeScriptCleaner(configured = true, result = CleanResult.Success(l2Segments))
        val harness = createHarness(cleaner)
        advanceUntilIdle()

        harness.service.beginTextCapture("文章标题\n\n规则分段的原文句子。")
        advanceUntilIdle()
        assertEquals(L2Status.SUCCEEDED, harness.service.capture.value.preview?.l2Status)

        harness.service.updatePreviewBody("修改后的正文足够长的句子。")

        val preview = harness.service.capture.value.preview!!
        assertEquals(SegmentationSource.RULE, preview.segmentation)
        assertEquals(L2Status.NOT_RUN, preview.l2Status)
        assertEquals(
            RuleSegmenter.segment("文章标题", "修改后的正文足够长的句子。"),
            preview.segmentDrafts,
        )
        assertEquals(1, cleaner.calls.size)
        harness.scope.cancel()
    }

    @Test
    fun `save persists the current l2 drafts`() = runTest {
        val cleaner = FakeScriptCleaner(configured = true, result = CleanResult.Success(l2Segments))
        val harness = createHarness(cleaner)
        advanceUntilIdle()

        harness.service.beginTextCapture("文章标题\n\n规则分段的原文句子。")
        advanceUntilIdle()

        val resourceId = harness.service.saveCapture()
        assertNotNull(resourceId)
        advanceUntilIdle()

        assertEquals(resourceId, harness.service.capture.value.savedResourceId)
        assertEquals(l2Segments, harness.store.savedSegments)
        harness.scope.cancel()
    }

    @Test
    fun `save persists rule drafts when l2 is not configured`() = runTest {
        val cleaner = FakeScriptCleaner(configured = false)
        val harness = createHarness(cleaner)
        advanceUntilIdle()

        harness.service.beginTextCapture("文章标题\n\n规则分段的原文句子。")
        val resourceId = harness.service.saveCapture()
        advanceUntilIdle()

        assertNotNull(resourceId)
        assertEquals(
            RuleSegmenter.segment("文章标题", "规则分段的原文句子。"),
            harness.store.savedSegments,
        )
        harness.scope.cancel()
    }

    @Test
    fun `stale l2 result is discarded after the body is edited`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val cleaner = FakeScriptCleaner(
            configured = true,
            result = CleanResult.Success(l2Segments),
            gate = gate,
        )
        val harness = createHarness(cleaner)
        advanceUntilIdle()

        harness.service.beginTextCapture("旧标题\n\n旧正文足够长的句子内容。")
        advanceUntilIdle()
        assertEquals(1, cleaner.calls.size)
        assertEquals(L2Status.RUNNING, harness.service.capture.value.preview?.l2Status)

        harness.service.updatePreviewBody("新正文足够长的句子内容。")
        gate.complete(Unit)
        advanceUntilIdle()

        val preview = harness.service.capture.value.preview!!
        assertEquals("新正文足够长的句子内容。", preview.body)
        assertEquals(SegmentationSource.RULE, preview.segmentation)
        assertEquals(L2Status.NOT_RUN, preview.l2Status)
        assertEquals(
            RuleSegmenter.segment("旧标题", "新正文足够长的句子内容。"),
            preview.segmentDrafts,
        )
        harness.scope.cancel()
    }

    @Test
    fun `test llm reports success and failure in settings state`() = runTest {
        val cleaner = FakeScriptCleaner(
            configured = true,
            testResult = ConnectionTestResult(true, "LLM 连接正常"),
        )
        val harness = createHarness(cleaner)
        advanceUntilIdle()

        harness.service.testLlm()
        assertEquals(TestState.Running, harness.service.settings.value.llmTest)
        advanceUntilIdle()
        assertTrue(harness.service.settings.value.llmTest is TestState.Success)

        cleaner.testResult = ConnectionTestResult(false, "LLM Key 无效或已过期，请到设置检查")
        harness.service.testLlm()
        advanceUntilIdle()
        val failure = harness.service.settings.value.llmTest
        assertTrue(failure is TestState.Failure)
        assertEquals("LLM Key 无效或已过期，请到设置检查", (failure as TestState.Failure).message)
        harness.scope.cancel()
    }

    @Test
    fun `saving llm config with blank key keeps the stored key`() = runTest {
        val settings = FakeSettingsStore()
        settings.setLlmConfig("http://old.example/v1", "old-model", "sk-existing")
        val harness = createHarness(FakeScriptCleaner(), settings = settings)
        advanceUntilIdle()

        harness.service.saveLlmConfig("http://new.example/v1", "", "new-model")
        advanceUntilIdle()

        assertEquals("http://new.example/v1", settings.llmEndpoint())
        assertEquals("new-model", settings.llmModel())
        assertEquals("sk-existing", settings.llmKey())
        assertTrue(harness.service.settings.value.llmKeyConfigured)

        harness.service.setLlmKey("")
        advanceUntilIdle()
        assertNull(settings.llmKey())
        assertFalse(harness.service.settings.value.llmKeyConfigured)
        harness.scope.cancel()
    }
}
