package com.cnife.listenreader.ui

import android.app.Application
import com.cnife.listenreader.ui.theme.ListenReaderTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.service.SettingsState
import com.cnife.listenreader.service.StorageUsage
import com.cnife.listenreader.testing.FakeReadingService
import com.cnife.listenreader.ui.settings.SettingsScreen
import com.cnife.listenreader.ui.settings.StorageContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class StorageSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `storage content shows usage and clears old tracks`() {
        var cleared = false
        composeRule.setContent {
            ListenReaderTheme {
                StorageContent(
                    state = SettingsState(storage = StorageUsage(4 * 1024L, 2 * 1024L)),
                    onClear = { cleared = true },
                )
            }
        }

        composeRule.onNodeWithTag("storage-active-bytes").assertTextContains("4.0 KB", substring = true)
        composeRule.onNodeWithTag("storage-old-bytes").assertTextContains("2.0 KB", substring = true)
        composeRule.onNodeWithTag("storage-clear").performClick()
        composeRule.waitForIdle()
        assertTrue(cleared)
    }

    @Test
    fun `settings screen loads and clears storage`() {
        val service = FakeReadingService()
        service.storageUsage = StorageUsage(8 * 1024L, 3 * 1024L)
        composeRule.setContent {
            ListenReaderTheme {
                SettingsScreen(service = service, onBack = {})
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("storage-section").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("storage-old-bytes").assertTextContains("3.0 KB", substring = true)
        composeRule.onNodeWithTag("storage-clear").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(1, service.clearOldAudioTracksCalls)
        composeRule.onNodeWithTag("storage-old-bytes").assertTextContains("0 B", substring = true)
        composeRule.onNodeWithTag("storage-message").assertTextContains("已清理", substring = true)
    }
}
