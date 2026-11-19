package com.cnife.listenreader.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cnife.listenreader.ListenReaderApp
import com.cnife.listenreader.model.ThemeMode
import com.cnife.listenreader.service.CaptureMode
import com.cnife.listenreader.testing.FakeReadingService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppNavHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyLibrary_showsEmptyStateWithNewAndSettingsEntries() {
        val service = FakeReadingService()

        composeRule.setContent { ListenReaderApp(service) }

        composeRule.onNodeWithText("还没有内容").assertIsDisplayed()
        composeRule.onNodeWithText("新建").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("设置").assertIsDisplayed()
    }

    @Test
    fun tappingNew_opensCapturePlaceholder() {
        val service = FakeReadingService()
        composeRule.setContent { ListenReaderApp(service) }

        composeRule.onNodeWithText("新建").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("采集").assertIsDisplayed()
        composeRule.onNodeWithText("内置浏览器与正文提取将在后续版本接入。").assertIsDisplayed()
        assertEquals(CaptureMode.URL, service.capture.value.mode)
    }

    @Test
    fun settings_themeSwitchTakesEffect() {
        val service = FakeReadingService()
        composeRule.setContent { ListenReaderApp(service) }

        composeRule.onNodeWithContentDescription("设置").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("外观").assertIsDisplayed()

        composeRule.onNodeWithTag("theme-DARK").performClick()
        composeRule.waitForIdle()

        assertEquals(ThemeMode.DARK, service.lastThemeMode)
        composeRule.onNodeWithTag("theme-DARK").assertIsSelected()
        composeRule.onNodeWithTag("theme-SYSTEM").assertIsNotSelected()
    }
}
