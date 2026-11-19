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
