package com.cnife.listenreader.service

import com.cnife.listenreader.service.ports.AppSettings
import com.cnife.listenreader.service.ports.RemoteRelease
import com.cnife.listenreader.service.ports.UpdateCheckException
import com.cnife.listenreader.testing.FakeLibraryStore
import com.cnife.listenreader.testing.FakeSettingsStore
import com.cnife.listenreader.testing.FakeUpdateChecker
import com.cnife.listenreader.testing.FixedTimeProvider
import com.cnife.listenreader.testing.testReadingService
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
class UpdateCheckFlowTest {

    private class Harness(
        val service: DefaultReadingService,
        val scope: CoroutineScope,
        val settings: FakeSettingsStore,
        val checker: FakeUpdateChecker,
    )

    /** 真实时钟下必然已超过 24 小时冷却的「很晚」时间点。 */
    private val dueNow = 90_000_000L

    private fun TestScope.createHarness(
        initial: AppSettings = AppSettings(),
        appVersion: String = "0.1.0",
        nowMs: Long = NOW,
        checker: FakeUpdateChecker = FakeUpdateChecker(),
    ): Harness {
        val store = FakeSettingsStore(initial)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = testReadingService(
            scope = scope,
            settingsStore = store,
            timeProvider = FixedTimeProvider(nowMs),
            updateChecker = checker,
            appVersion = appVersion,
        )
        return Harness(service, scope, store, checker)
    }

    private fun cachedRelease(version: String = "0.2.0") = RemoteRelease(
        version = version,
        url = "https://example.test/listen-reader-$version.apk",
        notes = "更新说明",
    )

    @Test
    fun `cached remote version is shown on cold start without hitting the network`() = runTest {
        val h = createHarness(
            initial = AppSettings(
                lastUpdateCheckAt = NOW,
                knownReleaseVersion = "0.2.0",
                knownReleaseUrl = "https://example.test/listen-reader-0.2.0.apk",
                knownReleaseNotes = "更新说明",
            ),
        )

        advanceUntilIdle()

        val update = h.service.settings.value.update
        assertEquals("0.2.0", update.release?.version)
        assertEquals("https://example.test/listen-reader-0.2.0.apk", update.release?.url)
        assertEquals("更新说明", update.release?.notes)
        assertEquals("0.1.0", update.currentVersion)
        assertEquals(0, h.checker.calls)
        h.scope.cancel()
    }

    @Test
    fun `auto check runs after the cooldown and records the result`() = runTest {
        val h = createHarness(initial = AppSettings(lastUpdateCheckAt = 0), nowMs = dueNow)

        advanceUntilIdle()

        assertEquals(1, h.checker.calls)
        assertEquals("0.2.0", h.service.settings.value.update.release?.version)
        assertEquals(dueNow, h.settings.current.lastUpdateCheckAt)
        assertEquals("0.2.0", h.settings.current.knownReleaseVersion)
        assertFalse(h.service.settings.value.update.ignored)
        h.scope.cancel()
    }

    @Test
    fun `auto check is skipped inside the cooldown`() = runTest {
        val h = createHarness(initial = AppSettings(lastUpdateCheckAt = NOW))

        advanceUntilIdle()

        assertEquals(0, h.checker.calls)
        assertNull(h.service.settings.value.update.release)
        h.scope.cancel()
    }

    @Test
    fun `auto check is skipped when disabled in settings`() = runTest {
        val h = createHarness(
            initial = AppSettings(autoCheckUpdate = false, lastUpdateCheckAt = 0),
            nowMs = dueNow,
        )

        advanceUntilIdle()

        assertEquals(0, h.checker.calls)
        assertFalse(h.service.settings.value.update.autoCheck)
        h.scope.cancel()
    }

    @Test
    fun `auto check failure stays silent and keeps the cooldown unstamped`() = runTest {
        val h = createHarness(
            initial = AppSettings(lastUpdateCheckAt = 0),
            nowMs = dueNow,
            checker = FakeUpdateChecker(failure = UpdateCheckException("离线")),
        )

        advanceUntilIdle()

        assertEquals(1, h.checker.calls)
        assertEquals(UpdateCheckState.Idle, h.service.settings.value.update.check)
        assertNull(h.service.settings.value.error)
        assertEquals(0L, h.settings.current.lastUpdateCheckAt)
        h.scope.cancel()
    }

    @Test
    fun `manual check ignores the cooldown and reports a failure in the version section`() = runTest {
        val h = createHarness(
            initial = AppSettings(lastUpdateCheckAt = NOW),
            checker = FakeUpdateChecker(
                failure = UpdateCheckException("网络连接失败，请检查网络后重试"),
            ),
        )

        advanceUntilIdle()
        h.service.checkForUpdate()
        advanceUntilIdle()

        assertEquals(1, h.checker.calls)
        assertEquals(
            UpdateCheckState.Failed("网络连接失败，请检查网络后重试"),
            h.service.settings.value.update.check,
        )
        assertNull(h.service.settings.value.error)
        h.scope.cancel()
    }

    @Test
    fun `manual check reports up to date when the remote equals the installed version`() = runTest {
        val h = createHarness(
            initial = AppSettings(lastUpdateCheckAt = NOW),
            checker = FakeUpdateChecker(release = cachedRelease("0.1.0")),
        )

        advanceUntilIdle()
        h.service.checkForUpdate()
        advanceUntilIdle()

        assertEquals(UpdateCheckState.Latest("0.1.0"), h.service.settings.value.update.check)
        assertNull(h.service.settings.value.update.release)
        assertEquals(NOW, h.settings.current.lastUpdateCheckAt)
        h.scope.cancel()
    }

    @Test
    fun `manual check clears the ignored version so the library notice returns`() = runTest {
        val h = createHarness(
            initial = AppSettings(
                lastUpdateCheckAt = NOW,
                knownReleaseVersion = "0.2.0",
                knownReleaseUrl = "https://example.test/listen-reader-0.2.0.apk",
                ignoredUpdateVersion = "0.2.0",
            ),
        )

        advanceUntilIdle()
        assertTrue(h.service.settings.value.update.ignored)

        h.service.checkForUpdate()
        advanceUntilIdle()

        assertFalse(h.service.settings.value.update.ignored)
        assertNull(h.settings.current.ignoredUpdateVersion)
        assertEquals("0.2.0", h.service.settings.value.update.release?.version)
        h.scope.cancel()
    }

    @Test
    fun `dismissing persists the ignored version`() = runTest {
        val h = createHarness(
            initial = AppSettings(
                lastUpdateCheckAt = NOW,
                knownReleaseVersion = "0.2.0",
                knownReleaseUrl = "https://example.test/listen-reader-0.2.0.apk",
            ),
        )

        advanceUntilIdle()
        h.service.dismissUpdate()
        advanceUntilIdle()

        assertTrue(h.service.settings.value.update.ignored)
        assertEquals("0.2.0", h.settings.current.ignoredUpdateVersion)
        h.scope.cancel()
    }

    @Test
    fun `installing the cached version hides the stale release`() = runTest {
        val h = createHarness(
            initial = AppSettings(
                lastUpdateCheckAt = NOW,
                knownReleaseVersion = "0.2.0",
                knownReleaseUrl = "https://example.test/listen-reader-0.2.0.apk",
            ),
            appVersion = "0.2.0",
        )

        advanceUntilIdle()

        assertNull(h.service.settings.value.update.release)
        assertFalse(h.service.settings.value.update.ignored)
        h.scope.cancel()
    }

    private companion object {
        const val NOW = 10_000L
    }
}
