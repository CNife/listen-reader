package com.cnife.listenreader.service

import com.cnife.listenreader.model.ThemeMode
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultReadingServiceTest {

    private class Harness(val service: DefaultReadingService, val scope: CoroutineScope)

    private fun TestScope.createService(
        libraryStore: FakeLibraryStore = FakeLibraryStore(),
        settingsStore: FakeSettingsStore = FakeSettingsStore(),
    ): Harness {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = DefaultReadingService(
            pageSession = FakePageSession(),
            scriptCleaner = FakeScriptCleaner(),
            speechSynthesizer = FakeSpeechSynthesizer(),
            libraryStore = libraryStore,
            playbackEngine = FakePlaybackEngine(),
            settingsStore = settingsStore,
            timeProvider = FixedTimeProvider(),
            scope = scope,
        )
        return Harness(service, scope)
    }

    @Test
    fun `empty library finishes loading with no items`() = runTest {
        val harness = createService()

        advanceUntilIdle()

        val state = harness.service.library.value
        assertFalse(state.isLoading)
        assertTrue(state.items.isEmpty())
        assertNull(state.continueListening)
        assertNull(state.error)
        harness.scope.cancel()
    }

    @Test
    fun `refresh library keeps empty state and stops loading`() = runTest {
        val harness = createService()
        advanceUntilIdle()

        harness.service.refreshLibrary()
        advanceUntilIdle()

        val state = harness.service.library.value
        assertFalse(state.isLoading)
        assertTrue(state.items.isEmpty())
        harness.scope.cancel()
    }

    @Test
    fun `empty text capture shows no preview and a hint`() = runTest {
        val harness = createService()

        harness.service.beginTextCapture("   ")
        advanceUntilIdle()

        assertEquals(CaptureMode.TEXT, harness.service.capture.value.mode)
        assertNull(harness.service.capture.value.preview)
        assertNotNull(harness.service.capture.value.error)
        harness.scope.cancel()
    }

    @Test
    fun `save failure clears saved id and reports readable error`() = runTest {
        val store = FakeLibraryStore().apply { failCreate = true }
        val harness = createService(libraryStore = store)
        advanceUntilIdle()

        harness.service.beginTextCapture("文章标题\n\n正文足够长的句子内容。")
        val returnedId = harness.service.saveCapture()
        assertNotNull(returnedId)
        advanceUntilIdle()

        val capture = harness.service.capture.value
        assertFalse(capture.isSaving)
        assertNull(capture.savedResourceId)
        assertEquals("保存失败，请稍后重试", capture.error?.message)
        harness.scope.cancel()
    }

    @Test
    fun `save is ignored while a previous save is still running`() = runTest {
        val harness = createService()
        advanceUntilIdle()

        harness.service.beginTextCapture("文章标题\n\n正文足够长的句子内容。")
        val first = harness.service.saveCapture()
        assertNotNull(first)
        assertNull(harness.service.saveCapture())
        advanceUntilIdle()

        val capture = harness.service.capture.value
        assertFalse(capture.isSaving)
        assertEquals(first, capture.savedResourceId)
        harness.scope.cancel()
    }

    @Test
    fun `save completion from previous capture does not leak into a new capture`() = runTest {
        val harness = createService()
        advanceUntilIdle()

        harness.service.beginTextCapture("旧标题\n\n旧正文足够长的句子内容。")
        assertNotNull(harness.service.saveCapture())
        harness.service.beginTextCapture("新标题\n\n新正文足够长的句子内容。")
        advanceUntilIdle()

        val capture = harness.service.capture.value
        assertFalse(capture.isSaving)
        assertNull(capture.savedResourceId)
        assertEquals("新标题", capture.preview?.title)
        harness.scope.cancel()
    }

    @Test
    fun `theme change updates state and persists to settings store`() = runTest {
        val settingsStore = FakeSettingsStore()
        val harness = createService(settingsStore = settingsStore)
        advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, harness.service.settings.value.themeMode)

        harness.service.setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, harness.service.settings.value.themeMode)
        assertEquals(ThemeMode.DARK, settingsStore.current.themeMode)

        harness.service.setThemeMode(ThemeMode.LIGHT)
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, harness.service.settings.value.themeMode)
        assertEquals(ThemeMode.LIGHT, settingsStore.current.themeMode)
        harness.scope.cancel()
    }
}
