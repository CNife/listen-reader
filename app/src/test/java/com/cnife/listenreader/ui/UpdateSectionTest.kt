package com.cnife.listenreader.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.service.AvailableUpdate
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.service.UpdateState
import com.cnife.listenreader.testing.FakeReadingService
import com.cnife.listenreader.ui.library.LibraryScreen
import com.cnife.listenreader.ui.settings.SettingsScreen
import com.cnife.listenreader.ui.theme.ListenReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class UpdateSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun updateState(ignored: Boolean = false) = UpdateState(
        currentVersion = "0.1.0",
        release = AvailableUpdate(
            version = "0.2.0",
            url = "https://example.test/listen-reader-0.2.0.apk",
            notes = "修复若干问题",
        ),
        ignored = ignored,
    )

    @Test
    fun `library notice shows the new version and dismissal removes it`() {
        val service = FakeReadingService(
            initialSettings = SettingsState(update = updateState()),
        )
        composeRule.setContent {
            ListenReaderTheme {
                LibraryScreen(
                    service = service,
                    onNewCapture = {},
                    onOpenSettings = {},
                    onOpenResource = {},
                )
            }
        }

        composeRule.onNodeWithTag("library-update").assertIsDisplayed()
        composeRule.onNodeWithText("发现新版本 v0.2.0").assertIsDisplayed()
        composeRule.onNodeWithTag("library-update-dismiss").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("library-update").assertDoesNotExist()
    }

    @Test
    fun `ignored release stays hidden in the library`() {
        val service = FakeReadingService(
            initialSettings = SettingsState(update = updateState(ignored = true)),
        )
        composeRule.setContent {
            ListenReaderTheme {
                LibraryScreen(
                    service = service,
                    onNewCapture = {},
                    onOpenSettings = {},
                    onOpenResource = {},
                )
            }
        }

        composeRule.onNodeWithTag("library-update").assertDoesNotExist()
    }

    @Test
    fun `settings section shows current version notes and forwards actions`() {
        val service = FakeReadingService(
            initialSettings = SettingsState(
                update = updateState().copy(autoCheck = false),
            ),
        )
        composeRule.setContent {
            ListenReaderTheme {
                SettingsScreen(service = service, onBack = {})
            }
        }

        composeRule.onNodeWithTag("update-section").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("update-current").assertTextContains("v0.1.0", substring = true)
        composeRule.onNodeWithTag("update-available").assertTextContains("v0.2.0", substring = true)
        composeRule.onNodeWithTag("update-notes").assertTextContains("修复若干问题", substring = true)

        composeRule.onNodeWithTag("update-auto-true").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(true), service.autoCheckChanges)

        composeRule.onNodeWithTag("update-check").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, service.checkForUpdateCalls)
        composeRule.onNodeWithTag("update-latest").assertTextContains("已是最新版本 v0.1.0")
    }

    @Test
    fun `ignored release is still visible in settings with a hint`() {
        val service = FakeReadingService(
            initialSettings = SettingsState(update = updateState(ignored = true)),
        )
        composeRule.setContent {
            ListenReaderTheme {
                SettingsScreen(service = service, onBack = {})
            }
        }

        composeRule.onNodeWithTag("update-available").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("update-ignored").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("update-open").performScrollTo().assertIsDisplayed()
    }
}
